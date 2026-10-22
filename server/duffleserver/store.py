"""
SQLite, because a hackathon backend should run from a clone with nothing to provision.

Two rules are enforced here rather than trusted to callers:
  * the first registration of a reference wins — a later call cannot re-point it at a
    different recipient or amount;
  * a signature settles at most one request — one transaction can carry several references
    while paying once, and must not mark two requests paid.
"""
from __future__ import annotations

import sqlite3
import threading
import hashlib
import time


class Conflict(Exception):
    pass


#: Crockford-ish: no I, L, O or U. See Store.referral_code.
CODE_ALPHABET = "0123456789ABCDEFGHJKMNPQRSTVWXYZ"
CODE_LENGTH = 8

#: How long a scratch card can be scratched for, from the moment it is earned.
SCRATCH_TTL = 30 * 86_400.0
#: What `reveal_reward` returns for a card that was left past that. Not a time.
EXPIRED = -1.0

class Store:
    def __init__(self, path: str = ":memory:"):
        self._db = sqlite3.connect(path, check_same_thread=False)
        self._db.row_factory = sqlite3.Row
        self._lock = threading.Lock()
        with self._lock, self._db:
            self._db.executescript(
                """
                CREATE TABLE IF NOT EXISTS requests (
                    reference  TEXT PRIMARY KEY,
                    recipient  TEXT NOT NULL,
                    amount     TEXT NOT NULL,
                    lamports   INTEGER NOT NULL,
                    label      TEXT NOT NULL DEFAULT '',
                    created_at REAL NOT NULL,
                    status     TEXT NOT NULL DEFAULT 'open',
                    signature  TEXT UNIQUE,
                    paid_at    REAL,
                    next_check REAL NOT NULL DEFAULT 0,
                    payer      TEXT,
                    genesis    TEXT
                );
                CREATE INDEX IF NOT EXISTS due ON requests(status, next_check);
                CREATE INDEX IF NOT EXISTS by_recipient ON requests(recipient, status);
                CREATE TABLE IF NOT EXISTS rewards (
                    id         INTEGER PRIMARY KEY AUTOINCREMENT,
                    reference  TEXT NOT NULL,
                    payer      TEXT NOT NULL,
                    counterparty TEXT NOT NULL,
                    kind       TEXT NOT NULL,
                    skr        INTEGER NOT NULL,
                    -- locked (reversible) -> settled, or reversed
                    state      TEXT NOT NULL DEFAULT 'locked',
                    reason     TEXT,
                    week       INTEGER NOT NULL,
                    created_at REAL NOT NULL,
                    unlock_at  REAL NOT NULL
                );
                CREATE INDEX IF NOT EXISTS rewards_payer ON rewards(payer, created_at);
                CREATE UNIQUE INDEX IF NOT EXISTS rewards_once ON rewards(reference, kind);
                -- Every payment that was looked at, earning or not: the refusals are how we
                -- see farming attempts and answer "why didn't I get anything?".
                CREATE TABLE IF NOT EXISTS reward_attempts (
                    reference  TEXT PRIMARY KEY,
                    payer      TEXT NOT NULL,
                    counterparty TEXT NOT NULL,
                    refused    TEXT,
                    skr        INTEGER NOT NULL DEFAULT 0,
                    at         REAL NOT NULL
                );
                CREATE TABLE IF NOT EXISTS reward_config (
                    key        TEXT PRIMARY KEY,
                    value      TEXT NOT NULL,
                    updated_at REAL NOT NULL
                );
                CREATE TABLE IF NOT EXISTS offers (
                    id         TEXT PRIMARY KEY,
                    funder     TEXT NOT NULL DEFAULT 'duffle',
                    app        TEXT NOT NULL,
                    what       TEXT NOT NULL,
                    address    TEXT NOT NULL,
                    token      TEXT NOT NULL DEFAULT 'SOL',
                    minimum    INTEGER NOT NULL DEFAULT 0,
                    reward     INTEGER NOT NULL DEFAULT 0,
                    budget     INTEGER NOT NULL DEFAULT 0,
                    starts     REAL NOT NULL,
                    ends       REAL NOT NULL,
                    window_hours REAL NOT NULL DEFAULT 48,
                    link       TEXT NOT NULL DEFAULT ''
                );
                -- One row per person per offer. The `signature` is the whole point: it is the
                -- proof a dApp is eventually shown, and the reason attribution is recorded
                -- even when the reward is zero.
                CREATE TABLE IF NOT EXISTS claims (
                    offer_id   TEXT NOT NULL,
                    address    TEXT NOT NULL,
                    claimed_at REAL NOT NULL,
                    state      TEXT NOT NULL DEFAULT 'open',
                    skr        INTEGER NOT NULL DEFAULT 0,
                    signature  TEXT,
                    refused    TEXT,
                    checked_at REAL NOT NULL DEFAULT 0,
                    PRIMARY KEY (offer_id, address)
                );
                CREATE TABLE IF NOT EXISTS messages (
                    id        INTEGER PRIMARY KEY AUTOINCREMENT,
                    sender    TEXT NOT NULL,
                    recipient TEXT NOT NULL,
                    label     TEXT NOT NULL DEFAULT '',
                    body      TEXT NOT NULL,
                    sent_at   REAL NOT NULL
                );
                CREATE INDEX IF NOT EXISTS by_recipient ON messages(recipient, sent_at);
                -- Shop purchases, so a webhook has somewhere to land and somebody to tell.
                -- The app keeps its own copy; this exists because Bitrefill calls US, not the
                -- phone, and the phone is usually closed by then.
                CREATE TABLE IF NOT EXISTS purchases (
                    invoice_id TEXT PRIMARY KEY,
                    buyer      TEXT,
                    status     TEXT,
                    code       TEXT,
                    updated_at REAL
                );

                -- A short code somebody can read down a phone line, mapped to the wallet it
                -- belongs to. Stored rather than derived both ways: a code short enough to
                -- type is too short to reverse into an address, so the mapping has to live
                -- somewhere, and collisions have to be resolvable.
                CREATE TABLE IF NOT EXISTS referral_codes (
                    code    TEXT PRIMARY KEY,
                    address TEXT NOT NULL UNIQUE,
                    at      REAL NOT NULL
                );
                -- Who invited whom. The invitee is the key: you can be invited once, by one
                -- person, and no later claim overwrites the first.
                CREATE TABLE IF NOT EXISTS referrals (
                    invitee TEXT PRIMARY KEY,
                    inviter TEXT NOT NULL,
                    at      REAL NOT NULL,
                    -- When the inviter was actually paid, which only happens once the invitee
                    -- has made a first rewarded payment.
                    paid_at REAL
                );
                CREATE INDEX IF NOT EXISTS referrals_inviter ON referrals(inviter, at);
                CREATE TABLE IF NOT EXISTS payouts (
                    id         TEXT PRIMARY KEY,
                    address    TEXT NOT NULL,
                    skr        INTEGER NOT NULL,
                    -- created (not yet accepted by the payer) -> sent -> confirmed | failed
                    state      TEXT NOT NULL,
                    signature  TEXT,
                    error      TEXT,
                    created_at REAL NOT NULL,
                    updated_at REAL NOT NULL
                );
                CREATE INDEX IF NOT EXISTS payouts_address ON payouts(address, created_at);
                CREATE TABLE IF NOT EXISTS auth_nonces (
                    nonce      TEXT PRIMARY KEY,
                    address    TEXT NOT NULL,
                    message    TEXT NOT NULL,
                    issued_at  REAL NOT NULL,
                    expires_at REAL NOT NULL,
                    attempts   INTEGER NOT NULL DEFAULT 0,
                    used       INTEGER NOT NULL DEFAULT 0
                );
                CREATE INDEX IF NOT EXISTS auth_nonces_address ON auth_nonces(address, expires_at);
                CREATE TABLE IF NOT EXISTS sessions (
                    token_hash TEXT PRIMARY KEY,
                    address    TEXT NOT NULL,
                    created_at REAL NOT NULL,
                    expires_at REAL NOT NULL,
                    revoked_at REAL
                );
                CREATE INDEX IF NOT EXISTS sessions_address ON sessions(address, created_at);
                -- One person's wallets. A wallet belongs to at most one account, which is what
                -- lets a wallet that has been through the app before be recognised on a new phone.
                CREATE TABLE IF NOT EXISTS accounts (
                    id         TEXT PRIMARY KEY,
                    created_at REAL NOT NULL
                );
                CREATE TABLE IF NOT EXISTS account_wallets (
                    address    TEXT PRIMARY KEY,
                    account_id TEXT NOT NULL,
                    linked_at  REAL NOT NULL
                );
                CREATE INDEX IF NOT EXISTS account_wallets_account ON account_wallets(account_id);
                -- Offers are told to a person one at a time, a few hours apart — not all at once on
                -- the notification page. `offer_notices` is what has been sent; `notice_state` is
                -- when the next one is due. Keyed by the account (or the wallet, if it has none).
                CREATE TABLE IF NOT EXISTS offer_notices (
                    account_key TEXT NOT NULL,
                    offer_id    TEXT NOT NULL,
                    sent_at     REAL NOT NULL,
                    PRIMARY KEY (account_key, offer_id)
                );
                CREATE TABLE IF NOT EXISTS notice_state (
                    account_key TEXT PRIMARY KEY,
                    next_due    REAL NOT NULL
                );
                CREATE TABLE IF NOT EXISTS handles (
                    name       TEXT PRIMARY KEY,
                    address    TEXT NOT NULL,
                    account    TEXT,
                    created_at REAL NOT NULL
                );
                -- Secrets the server makes for itself and never sends anywhere. Kept here rather
                -- than in the environment so they survive a restart without anybody setting one.
                CREATE TABLE IF NOT EXISTS server_secrets (
                    name  TEXT PRIMARY KEY,
                    value TEXT NOT NULL
                );
                -- Wallet movements already told to a person. `watch_wallets` keeps what it has seen
                -- in memory, which a restart forgets; this is the part that must not be forgotten,
                -- because forgetting it is how one transfer gets announced twice. Keyed by the
                -- account, so a move between two of a person's own wallets is one notice, not two.
                CREATE TABLE IF NOT EXISTS wallet_notices (
                    account_key TEXT NOT NULL,
                    signature   TEXT NOT NULL,
                    sent_at     REAL NOT NULL,
                    PRIMARY KEY (account_key, signature)
                );
                CREATE TABLE IF NOT EXISTS devices (
                    recipient  TEXT NOT NULL,
                    token      TEXT NOT NULL,
                    updated_at REAL NOT NULL,
                    PRIMARY KEY (recipient, token)
                );
                """
            )

        # Databases created before requests could be addressed to a payer.
        with self._lock, self._db:
            cols = {r[1] for r in self._db.execute("PRAGMA table_info(requests)")}
            for col, kind in (("payer", "TEXT"), ("genesis", "TEXT"), ("withdrawn", "INTEGER"),
                              ("rejected", "INTEGER"), ("rejected_by", "TEXT"),
                              # "Later": not a no. The asker is told so it shows as pending
                              # rather than as silence, and it stays open to be paid.
                              ("deferred", "INTEGER"), ("deferred_by", "TEXT"),
                              # What was asked for: "SOL" or an SPL mint. `lamports` then holds
                              # the amount in that token's smallest unit.
                              ("token", "TEXT NOT NULL DEFAULT 'SOL'"), ("decimals", "INTEGER NOT NULL DEFAULT 9"),
                              ("symbol", "TEXT NOT NULL DEFAULT 'SOL'"),
                              # How the payment started: only "tap" earns a reward.
                              ("origin", "TEXT NOT NULL DEFAULT ''"),
                              # When a payer was attached by a *second* registration — the mark
                              # of two phones, since the asking phone cannot know who will pay.
                              ("payer_at", "REAL"),
                              ("registrations", "INTEGER NOT NULL DEFAULT 1")):
                if col not in cols:
                    self._db.execute(f"ALTER TABLE requests ADD COLUMN {col} {kind}")
            self._db.execute("CREATE INDEX IF NOT EXISTS by_payer ON requests(payer, status)")

        # When the person actually looked at a reward. The rewards screen shows each one as a
        # card to scratch, and a card that re-covers itself every time the app is reinstalled
        # would be lying about which ones are new — so this is the server's fact, not the
        # phone's. It has no effect on the money: the reward was decided, credited and counted
        # when the payment settled, and scratching only uncovers what is already there.
        with self._lock, self._db:
            cols = {r[1] for r in self._db.execute("PRAGMA table_info(rewards)")}
            if "revealed_at" not in cols:
                self._db.execute("ALTER TABLE rewards ADD COLUMN revealed_at REAL")

    # -- requests ---------------------------------------------------------------------------

        # Columns added after launch of the ledger. SQLite has no "ADD COLUMN IF NOT EXISTS", so
        # ask first; an existing database keeps every row it had.
        for table, wanted in (
            ("rewards", (("payout_id", "TEXT"), ("paid_at", "REAL"))),
            ("auth_nonces", (("purpose", "TEXT NOT NULL DEFAULT 'signin'"),)),
            ("sessions", (("account_id", "TEXT"),)),
            ("offers", (("kind", "TEXT NOT NULL DEFAULT 'pay'"), ("params", "TEXT NOT NULL DEFAULT '{}'"),
                        ("featured", "INTEGER NOT NULL DEFAULT 0"), ("icon", "TEXT"))),
            ("purchases", (("product_id", "TEXT"), ("category", "TEXT"), ("amount", "INTEGER"),
                           ("created_at", "REAL"), ("delivered_at", "REAL"),
                           # What it cost in dollars, whatever it was paid in. `amount` is only
                           # ever USDC base units, so a purchase paid in SOL had no price at all.
                           ("usd", "REAL"))),
        ):
            have = {r[1] for r in self._db.execute(f"PRAGMA table_info({table})")}
            for col, ddl in wanted:
                if col not in have:
                    with self._db:
                        self._db.execute(f"ALTER TABLE {table} ADD COLUMN {col} {ddl}")

    def register(self, reference: str, recipient: str, amount: str, lamports: int, label: str,
                 payer: str | None = None, genesis: str | None = None,
                 token: str = "SOL", decimals: int = 9, symbol: str = "SOL", origin: str = "") -> dict:
        now = time.time()
        with self._lock, self._db:
            row = self._db.execute("SELECT * FROM requests WHERE reference=?", (reference,)).fetchone()
            if row is None:
                self._db.execute(
                    "INSERT INTO requests(reference,recipient,amount,lamports,label,created_at,next_check,payer,genesis,"
                    "token,decimals,symbol,origin) VALUES (?,?,?,?,?,?,0,?,?,?,?,?,?)",
                    (reference, recipient, amount, lamports, label[:64], now, payer, genesis, token, decimals,
                     symbol[:12], origin[:16]),
                )
                return {"status": "open", "created": True}
            if row["recipient"] != recipient or row["lamports"] != lamports or row["token"] != token:
                raise Conflict("that reference is already registered with different details")
            # Fill in a payer we did not know. A request made by tapping is registered by the
            # phone ASKING for money, which cannot know who will pay; the paying phone registers
            # the same reference moments later and does know. Without this the server has nobody
            # to tell when the request is withdrawn. Never overwrites a payer already recorded.
            self._db.execute("UPDATE requests SET registrations=registrations+1 WHERE reference=?", (reference,))
            if payer and not row["payer"]:
                self._db.execute("UPDATE requests SET payer=?, payer_at=? WHERE reference=?",
                                 (payer, now, reference))
            # Same for the origin: the phone that taps knows it was a tap; the one that served
            # the request may not have said so.
            if origin and not row["origin"]:
                self._db.execute("UPDATE requests SET origin=? WHERE reference=?", (origin[:16], reference))
            # A repeat registration is a nudge: look now rather than on the next sweep.
            if row["status"] == "open":
                self._db.execute("UPDATE requests SET next_check=0 WHERE reference=?", (reference,))
            return {"status": row["status"], "signature": row["signature"], "created": False}

    def reject(self, reference: str, by: str) -> dict | None:
        """The payer declined. Like a withdrawal, it stays OPEN: they may change their mind, and
        money sent anyway must still be recognised."""
        with self._lock, self._db:
            row = self._db.execute("SELECT * FROM requests WHERE reference=?", (reference,)).fetchone()
            if row is None:
                return None
            self._db.execute("UPDATE requests SET rejected=1, rejected_by=? WHERE reference=?", (by, reference))
        return dict(row)

    def defer(self, reference: str, by: str) -> dict | None:
        """The payer said "later". Unlike a rejection this is not an answer, so nothing about
        the request changes except that the asker can stop wondering."""
        with self._lock, self._db:
            row = self._db.execute("SELECT * FROM requests WHERE reference=?", (reference,)).fetchone()
            if row is None:
                return None
            self._db.execute("UPDATE requests SET deferred=1, deferred_by=? WHERE reference=?", (by, reference))
        return dict(row)

    def withdraw(self, reference: str) -> dict | None:
        """Mark a request as taken back. It stays OPEN on purpose: withdrawing means "stop
        asking", not "refuse money", and a payer already inside their wallet may land a payment
        a second later. That payment must still be noticed."""
        with self._lock, self._db:
            row = self._db.execute("SELECT * FROM requests WHERE reference=?", (reference,)).fetchone()
            if row is None:
                return None
            self._db.execute("UPDATE requests SET withdrawn=1 WHERE reference=?", (reference,))
        return dict(row)

    def get(self, reference: str) -> dict | None:
        with self._lock:
            row = self._db.execute("SELECT * FROM requests WHERE reference=?", (reference,)).fetchone()
        return dict(row) if row else None

    def due(self, now: float, limit: int = 50) -> list[dict]:
        with self._lock:
            rows = self._db.execute(
                "SELECT * FROM requests WHERE status='open' AND next_check<=? ORDER BY next_check LIMIT ?",
                (now, limit),
            ).fetchall()
        return [dict(r) for r in rows]

    def schedule(self, reference: str, at: float) -> None:
        with self._lock, self._db:
            self._db.execute("UPDATE requests SET next_check=? WHERE reference=?", (at, reference))

    def mark_paid(self, reference: str, signature: str) -> bool:
        with self._lock, self._db:
            try:
                cur = self._db.execute(
                    "UPDATE requests SET status='paid', signature=?, paid_at=? WHERE reference=? AND status='open'",
                    (signature, time.time(), reference),
                )
            except sqlite3.IntegrityError:
                return False  # signature already settled a different request
            return cur.rowcount == 1

    def open_among(self, keys: list[str]) -> list[dict]:
        if not keys:
            return []
        marks = ",".join("?" * len(keys))
        with self._lock:
            rows = self._db.execute(
                f"SELECT * FROM requests WHERE status='open' AND reference IN ({marks})", keys
            ).fetchall()
        return [dict(r) for r in rows]

    def recent_paid(self, recipient: str, since: float) -> list[dict]:
        with self._lock:
            rows = self._db.execute(
                "SELECT * FROM requests WHERE recipient=? AND status='paid' AND paid_at>=? ORDER BY paid_at",
                (recipient, since),
            ).fetchall()
        return [dict(r) for r in rows]

    def open_for_payer(self, payer: str, since: float) -> list[dict]:
        with self._lock:
            rows = self._db.execute(
                "SELECT * FROM requests WHERE payer=? AND status='open' AND created_at>=? ORDER BY created_at",
                (payer, since),
            ).fetchall()
        return [dict(r) for r in rows]

    def paid_by(self, payer: str, since: float, limit: int = 500) -> list[dict]:
        """Requests this wallet paid that settled after `since`, oldest first. The server's own
        record of a payment, which is the evidence for offers about paying people."""
        with self._lock:
            rows = self._db.execute(
                "SELECT reference, recipient, lamports, token, decimals, origin, signature, paid_at FROM requests"
                " WHERE payer=? AND status='paid' AND paid_at>=? ORDER BY paid_at LIMIT ?",
                (payer, since, limit),
            ).fetchall()
        return [dict(r) for r in rows]

    def secret(self, name: str) -> bytes:
        """A random secret under this name, made the first time it is asked for and the same
        ever after. Two threads asking at once both get the one that was stored."""
        import secrets as _secrets
        with self._lock, self._db:
            self._db.execute("INSERT OR IGNORE INTO server_secrets(name,value) VALUES (?,?)",
                             (name, _secrets.token_hex(32)))
            return bytes.fromhex(self._db.execute(
                "SELECT value FROM server_secrets WHERE name=?", (name,)).fetchone()[0])

    def recipients(self) -> list[str]:
        with self._lock:
            rows = self._db.execute(
                "SELECT recipient FROM requests UNION SELECT recipient FROM devices"
            ).fetchall()
        return sorted(r[0] for r in rows)

    def open_count(self) -> int:
        with self._lock:
            return self._db.execute("SELECT COUNT(*) FROM requests WHERE status='open'").fetchone()[0]

    # -- shop purchases ---------------------------------------------------------------------

    def remember_purchase(self, invoice_id: str, buyer: str | None, product_id: str | None = None,
                          category: str | None = None, amount: int | None = None,
                          usd: float | None = None) -> None:
        """Notes who bought what, at invoice time, so a later webhook knows who to tell — and so a
        shop offer can later prove the purchase happened, what kind it was and what it cost.

        `amount` is USDC base units and only set for a USDC invoice. `usd` is the dollar value
        whatever it was paid in, and is what an offer's floor is compared with."""
        now = time.time()
        with self._lock:
            self._db.execute(
                "INSERT INTO purchases(invoice_id,buyer,status,updated_at,product_id,category,amount,created_at,usd) "
                "VALUES (?,?,?,?,?,?,?,?,?) "
                "ON CONFLICT(invoice_id) DO UPDATE SET buyer=COALESCE(excluded.buyer,buyer)",
                (invoice_id, buyer, "created", now, product_id, category, amount, now, usd),
            )
            self._db.commit()

    #: Bitrefill's words for "the customer has what they paid for".
    DELIVERED = ("all_delivered", "complete", "delivered")

    def delivered_purchases(self, buyer: str, since: float) -> list[dict]:
        """What this wallet bought AND received after `since`. A purchase that was paid for but
        never delivered, or was refunded, is not here, so it can never earn a shop offer."""
        marks = ",".join("?" * len(self.DELIVERED))
        with self._lock:
            return [dict(r) for r in self._db.execute(
                f"SELECT invoice_id, category, amount, usd, created_at, delivered_at FROM purchases "
                f"WHERE buyer=? AND status IN ({marks}) AND delivered_at IS NOT NULL AND created_at>=?",
                (buyer, *self.DELIVERED, since))]

    def settle_purchase(self, invoice_id: str, status: str, code: str | None) -> tuple[str | None, bool]:
        """Records what became of a purchase. Returns (buyer, changed).

        `changed` is false when this is a repeat — Bitrefill retries webhooks and expects the
        handler to be idempotent, and a buyer should not be notified twice for one delivery.

        A code is never overwritten once stored.
        """
        with self._lock:
            row = self._db.execute(
                "SELECT buyer,status,code FROM purchases WHERE invoice_id=?", (invoice_id,)
            ).fetchone()
            buyer = row[0] if row else None
            was_status, was_code = (row[1], row[2]) if row else (None, None)
            changed = status != was_status or (code and not was_code)
            self._db.execute(
                "INSERT INTO purchases(invoice_id,buyer,status,code,updated_at) VALUES (?,?,?,?,?) "
                "ON CONFLICT(invoice_id) DO UPDATE SET status=excluded.status, "
                "code=COALESCE(purchases.code, excluded.code), updated_at=excluded.updated_at, "
                "delivered_at=CASE WHEN excluded.status IN ('all_delivered','complete','delivered') "
                "THEN COALESCE(purchases.delivered_at, excluded.updated_at) ELSE purchases.delivered_at END",
                (invoice_id, buyer, status, code, time.time()),
            )
            self._db.commit()
            return buyer, bool(changed)

    # -- devices ----------------------------------------------------------------------------

    # -- rewards ----------------------------------------------------------------------------

    def reward_config(self) -> dict:
        """Whatever has been changed at runtime. The defaults live in rewards.Config."""
        with self._lock:
            return {r["key"]: r["value"] for r in self._db.execute("SELECT key, value FROM reward_config")}

    def set_reward_config(self, values: dict) -> dict:
        now = time.time()
        with self._lock, self._db:
            for k, v in values.items():
                self._db.execute(
                    "INSERT INTO reward_config(key,value,updated_at) VALUES (?,?,?)"
                    " ON CONFLICT(key) DO UPDATE SET value=excluded.value, updated_at=excluded.updated_at",
                    (str(k), str(v), now),
                )
        return self.reward_config()

    def reward_history(self, payer: str, counterparty: str, now: float, week: int, month_start: float) -> dict:
        """The payer's side of the rules: what they have earned, when, and with whom."""
        with self._lock:
            fam = self._family(payer)
            rows = self._db.execute(
                "SELECT kind, skr, week, created_at, counterparty FROM rewards"
                f" WHERE payer IN ({','.join('?' * len(fam))}) AND state!='reversed'", tuple(fam),
            ).fetchall()
            spent = self._db.execute(
                "SELECT COALESCE(SUM(skr),0) FROM rewards WHERE week=? AND state!='reversed'"
                " AND kind NOT IN ('offer','signup','recharge','voucher')", (week,),
            ).fetchone()[0]
        with_them = [r["created_at"] for r in rows if r["counterparty"] == counterparty]
        # The per-person caps are about the TAP reward budget: they stop one person taking the
        # share meant for twenty. An offer is bounded twice already — by its own budget and by
        # the weekly pool — so counting it here as well would mean claiming offers costs you
        # your tap rewards, which is backwards: offers are the half that pays for itself.
        # `week_spent` below counts TAP rewards only. Offers are earmarked: each has its own
        # budget, so a popular offer cannot empty the pool and stop tap rewards, and the money
        # for each is reserved in the vault separately.
        taps = [r for r in rows if r["kind"] not in ("offer", "signup", "recharge", "voucher")]
        return {
            "rewarded_this_week": sum(1 for r in rows if r["week"] == week and r["kind"] == "per_payment"),
            "earned_in_campaign": sum(r["skr"] for r in taps),
            "earned_this_month": sum(r["skr"] for r in taps if r["created_at"] >= month_start),
            "last_reward_with_counterparty": max(with_them) if with_them else None,
            "had_first_payment": any(r["kind"] == "first_payment" for r in rows),
            "week_spent": spent,
        }

    def record_rewards(self, reference: str, payer: str, counterparty: str, awards: list,
                       week: int, now: float, unlock_at: float) -> int:
        """Writes one row per award. The unique index on (reference, kind) means a payment
        checked twice — two watchers, a webhook and a sweep — still pays once."""
        written = 0
        with self._lock, self._db:
            for kind, skr in awards:
                try:
                    self._db.execute(
                        "INSERT INTO rewards(reference,payer,counterparty,kind,skr,week,created_at,unlock_at)"
                        " VALUES (?,?,?,?,?,?,?,?)",
                        (reference, payer, counterparty, kind, int(skr), week, now, unlock_at),
                    )
                    written += 1
                except sqlite3.IntegrityError:
                    pass  # already paid for this payment
        return written

    def record_attempt(self, reference: str, payer: str, counterparty: str, refused: str | None,
                       skr: int, at: float) -> None:
        with self._lock, self._db:
            self._db.execute(
                "INSERT INTO reward_attempts(reference,payer,counterparty,refused,skr,at) VALUES (?,?,?,?,?,?)"
                " ON CONFLICT(reference) DO UPDATE SET refused=excluded.refused, skr=excluded.skr, at=excluded.at",
                (reference, payer, counterparty, refused, int(skr), at),
            )

    def reward_attempts(self, limit: int = 100) -> list[dict]:
        """Every payment the rules looked at, oldest first — including the ones that earned
        nothing. This is the only place a farming pattern, or a rule refusing too much, is
        visible; R2's admin view reads it."""
        with self._lock:
            rows = self._db.execute(
                "SELECT * FROM reward_attempts ORDER BY at LIMIT ?", (limit,)).fetchall()
        return [dict(r) for r in rows]

    def settled_payments(self, since: float, limit: int = 2000) -> list[dict]:
        """Every payment that landed since `since`, in the shape patrol.py reads. Both
        directions of a pair are in here, which is what makes a round trip visible."""
        with self._lock:
            rows = self._db.execute(
                "SELECT reference, payer, recipient, lamports, token, paid_at FROM requests"
                " WHERE status='paid' AND payer IS NOT NULL AND paid_at >= ? ORDER BY paid_at LIMIT ?",
                (since, limit),
            ).fetchall()
        return [{"reference": r["reference"], "payer": r["payer"], "counterparty": r["recipient"],
                 "units": r["lamports"], "token": r["token"], "at": r["paid_at"]} for r in rows]

    def locked_rewards(self, now: float) -> list[dict]:
        """Rewards that can still be taken back, one row per payment (not per award), with the
        payment's own details joined on so the patterns can be looked for."""
        with self._lock:
            rows = self._db.execute(
                "SELECT w.reference, w.payer, w.counterparty, SUM(w.skr) AS skr,"
                "       MIN(w.created_at) AS created_at, MIN(w.unlock_at) AS unlock_at,"
                "       q.lamports AS units, q.token AS token, q.payer_at AS payer_at,"
                "       q.registrations AS registrations"
                "  FROM rewards w LEFT JOIN requests q ON q.reference = w.reference"
                " WHERE w.state='locked' GROUP BY w.reference",
            ).fetchall()
        out = []
        for r in rows:
            d = dict(r)
            # Two registrations *and* a payer that arrived in the later one: the asking phone
            # cannot know who will pay, so that second call came from the other handset.
            d["corroborated"] = bool(d.get("payer_at")) and (d.get("registrations") or 1) > 1
            d["units"] = d.get("units") or 0
            out.append(d)
        return out

    def matured_by_payer(self, now: float) -> dict[str, int]:
        """Who has rewards about to become final, and how much — read just before they are
        settled, so each person can be told the moment their SKR becomes claimable."""
        with self._lock:
            rows = self._db.execute(
                "SELECT payer, SUM(skr) FROM rewards WHERE state='locked' AND unlock_at <= ? GROUP BY payer", (now,))
            return {r[0]: int(r[1]) for r in rows}

    def reward_owners(self, reference: str) -> dict[str, int]:
        """Who is owed rewards for one payment, and how much (locked ones: the only reversible)."""
        with self._lock:
            rows = self._db.execute(
                "SELECT payer, SUM(skr) FROM rewards WHERE reference=? AND state='locked' GROUP BY payer", (reference,))
            return {r[0]: int(r[1]) for r in rows}

    def settle_matured(self, now: float) -> int:
        """Locked rewards past their unlock time become final. Nothing is paid out here — that
        is R6's decision — this only ends the window in which they can be reversed."""
        with self._lock, self._db:
            cur = self._db.execute(
                "UPDATE rewards SET state='settled' WHERE state='locked' AND unlock_at <= ?", (now,))
        return cur.rowcount

    def reverse_rewards(self, reference: str, reason: str) -> int:
        """Takes back every award for one payment, with the reason kept. Only a locked reward
        can be reversed; once settled it is theirs, whatever we notice afterwards."""
        with self._lock, self._db:
            cur = self._db.execute(
                "UPDATE rewards SET state='reversed', reason=? WHERE reference=? AND state='locked'",
                (reason, reference))
        return cur.rowcount

    def reward_ledger(self, since: float) -> dict:
        """The admin view: what was paid, by rule and by day, and what was refused and why."""
        with self._lock:
            paid = self._db.execute(
                "SELECT kind, state, COUNT(*) AS n, SUM(skr) AS skr FROM rewards"
                " WHERE created_at >= ? GROUP BY kind, state", (since,)).fetchall()
            days = self._db.execute(
                "SELECT date(created_at, 'unixepoch') AS day, COUNT(*) AS n, SUM(skr) AS skr"
                "  FROM rewards WHERE created_at >= ? AND state!='reversed' GROUP BY day ORDER BY day",
                (since,)).fetchall()
            refused = self._db.execute(
                "SELECT refused, COUNT(*) AS n FROM reward_attempts"
                " WHERE at >= ? AND refused IS NOT NULL GROUP BY refused ORDER BY n DESC",
                (since,)).fetchall()
            reversed_rows = self._db.execute(
                "SELECT reference, payer, counterparty, skr, reason FROM rewards"
                " WHERE state='reversed' AND created_at >= ?", (since,)).fetchall()
            people = self._db.execute(
                "SELECT COUNT(DISTINCT payer) AS n FROM rewards WHERE created_at >= ? AND state!='reversed'",
                (since,)).fetchone()[0]
        return {
            "by_rule": [dict(r) for r in paid],
            "by_day": [dict(r) for r in days],
            "refused": [dict(r) for r in refused],
            "reversed": [dict(r) for r in reversed_rows],
            "people": people,
        }

    def refusals_for(self, payer: str, limit: int = 10) -> list[dict]:
        """Recent payments by this payer that earned nothing, newest first. The rewards screen
        shows these: "why didn't I get anything?" is the question a reward layer has to be able
        to answer, or people assume it is broken."""
        with self._lock:
            fam = self._family(payer)
            rows = self._db.execute(
                "SELECT reference, counterparty, refused, at FROM reward_attempts"
                f" WHERE payer IN ({','.join('?' * len(fam))}) AND refused IS NOT NULL ORDER BY at DESC LIMIT ?",
                (*fam, limit),
            ).fetchall()
        return [dict(r) for r in rows]

    def rewards_for(self, payer: str, limit: int = 50) -> list[dict]:
        with self._lock:
            fam = self._family(payer)
            rows = self._db.execute(
                f"SELECT * FROM rewards WHERE payer IN ({','.join('?' * len(fam))}) ORDER BY created_at DESC LIMIT ?",
                (*fam, limit),
            ).fetchall()
        # When each card stops being scratchable, sent with it so the phone can say so on the
        # card. The same figure `reveal_reward` enforces.
        return [dict(r) | {"expires_at": float(r["created_at"]) + SCRATCH_TTL} for r in rows]

    # -- referrals --------------------------------------------------------------------------

    def referral_code(self, address: str, now: float) -> str:
        """This wallet's code, made once and then never changed.

        Derived from the address rather than drawn at random, so the same wallet asking twice
        on two phones gets the same answer without either having to store it. The alphabet
        drops I, L, O and U — the first three because they are unreadable next to 1 and 0 when
        somebody is reading a code aloud, the last because it turns short codes into words
        nobody wants printed on their invite.
        """
        with self._lock, self._db:
            row = self._db.execute(
                "SELECT code FROM referral_codes WHERE address=?", (address,)).fetchone()
            if row is not None:
                return row["code"]
            digest = hashlib.sha256(f"airpay-referral-v1:{address}".encode()).digest()
            for attempt in range(64):
                # Re-hashed rather than incremented on collision: bumping the last character
                # would put two colliding wallets on adjacent codes, so a typo in one lands on
                # the other.
                raw = digest if attempt == 0 else hashlib.sha256(digest + bytes([attempt])).digest()
                n = int.from_bytes(raw[:8], "big")
                code = ""
                for _ in range(CODE_LENGTH):
                    code += CODE_ALPHABET[n % len(CODE_ALPHABET)]
                    n //= len(CODE_ALPHABET)
                taken = self._db.execute(
                    "SELECT address FROM referral_codes WHERE code=?", (code,)).fetchone()
                if taken is None:
                    self._db.execute(
                        "INSERT INTO referral_codes(code,address,at) VALUES (?,?,?)",
                        (code, address, now))
                    return code
            raise RuntimeError("could not allocate a referral code")

    def address_for_code(self, code: str) -> str | None:
        with self._lock:
            row = self._db.execute(
                "SELECT address FROM referral_codes WHERE code=?", (code.strip().upper(),)
            ).fetchone()
        return row["address"] if row else None

    def record_referral(self, invitee: str, inviter: str, now: float) -> bool:
        """Writes down who invited whom. False if this invitee was already claimed by someone."""
        with self._lock, self._db:
            cur = self._db.execute(
                "INSERT OR IGNORE INTO referrals(invitee,inviter,at) VALUES (?,?,?)",
                (invitee, inviter, now))
            return cur.rowcount > 0

    def referral_of(self, invitee: str) -> dict | None:
        """Who invited this person — through ANY of their wallets: one person is invited once."""
        with self._lock:
            fam = self._family(invitee)
            row = self._db.execute(
                f"SELECT * FROM referrals WHERE invitee IN ({','.join('?' * len(fam))})", tuple(fam)).fetchone()
        return dict(row) if row else None

    def mark_referral_paid(self, invitee: str, now: float) -> None:
        with self._lock, self._db:
            self._db.execute(
                "UPDATE referrals SET paid_at=? WHERE invitee=? AND paid_at IS NULL",
                (now, invitee))

    def referrals_by(self, inviter: str) -> list[dict]:
        """Everyone this wallet invited, newest first — including the ones who never paid,
        because "invited 6, 2 have paid" is the honest version of a referral count."""
        with self._lock:
            fam = self._family(inviter)
            rows = self._db.execute(
                f"SELECT * FROM referrals WHERE inviter IN ({','.join('?' * len(fam))}) ORDER BY at DESC",
                tuple(fam)).fetchall()
        return [dict(r) for r in rows]

    def reveal_reward(self, payer: str, reward_id: int, now: float) -> float | None:
        """Marks one reward as seen. Returns when it was first seen, or None if there is no
        such reward *for this payer* — the id comes from a phone, so it is checked against the
        owner rather than trusted.

        Writing only when it is still null makes this idempotent: scratching the same card on
        a second phone shows the first phone's time instead of moving it forward.
        """
        with self._lock, self._db:
            fam = self._family(payer)
            row = self._db.execute(
                f"SELECT revealed_at, created_at FROM rewards WHERE id=? AND payer IN ({','.join('?' * len(fam))})",
                (reward_id, *fam),
            ).fetchone()
            if row is None:
                return None
            if row["revealed_at"] is not None:
                return float(row["revealed_at"])
            # Left too long. A card nobody scratched in time is not paid, which is what gives
            # the date on the card a meaning.
            if now > float(row["created_at"]) + SCRATCH_TTL:
                return EXPIRED
            self._db.execute(
                "UPDATE rewards SET revealed_at=? WHERE id=? AND revealed_at IS NULL",
                (now, reward_id),
            )
        return now

    def reward_balance(self, payer: str, now: float) -> dict:
        with self._lock:
            fam = self._family(payer)
            # Only rewards whose card has been SCRATCHED. An unopened card is a surprise, and a
            # total (or a Claim button) that already counts it gives the surprise away. The amount
            # was fixed when the payment settled; scratching changes what is SHOWN, never what was
            # earned.
            rows = self._db.execute(
                "SELECT state, skr, unlock_at, payout_id FROM rewards"
                f" WHERE payer IN ({','.join('?' * len(fam))}) AND revealed_at IS NOT NULL", tuple(fam)).fetchall()
        locked = sum(r["skr"] for r in rows if r["state"] == "locked" and r["unlock_at"] > now)
        ready = sum(r["skr"] for r in rows if r["state"] == "locked" and r["unlock_at"] <= now)
        claimable = sum(r["skr"] for r in rows if r["state"] == "settled")
        claiming = sum(r["skr"] for r in rows if r["state"] == "claiming")
        paid = sum(r["skr"] for r in rows if r["state"] == "paid")
        return {"locked": locked, "ready": ready, "claimable": claimable, "claiming": claiming,
                "paid": paid,
                # `settled` kept for the app build that reads it: everything final, paid or not.
                "settled": claimable + claiming + paid,
                "total": locked + ready + claimable + claiming + paid}

    # -- payouts ----------------------------------------------------------------------------

    def begin_payout(self, address: str, min_skr: int, now: float, new_id: str) -> dict:
        """Turns an address's settled, unpaid rewards into ONE payout, atomically.

        Returns `{"existing": row}` if one is already in flight (so a second tap on Claim shows the
        same payout instead of starting another), `{"below": total}` under the minimum, or
        `{"payout": row}`. The rewards are marked `claiming` in the same transaction that creates
        the payout row, so the same reward can never be part of two payouts.
        """
        with self._lock, self._db:
            # The whole account, so rewards earned from either wallet are claimed together and a
            # second tap from the other wallet cannot start a second payout. The money still goes
            # to `address` — the wallet that is asking.
            fam = self._family(address)
            q = ",".join("?" * len(fam))
            live = self._db.execute(
                f"SELECT * FROM payouts WHERE address IN ({q}) AND state IN ('created','sent') LIMIT 1",
                tuple(fam)).fetchone()
            if live:
                return {"existing": dict(live)}
            # Scratched cards only, so what Claim pays is exactly what the screen shows.
            rows = self._db.execute(
                f"SELECT id, skr FROM rewards WHERE payer IN ({q}) AND state='settled' AND payout_id IS NULL"
                " AND revealed_at IS NOT NULL", tuple(fam)).fetchall()
            total = sum(r["skr"] for r in rows)
            if total <= 0 or total < min_skr:
                return {"below": total}
            self._db.execute(
                "INSERT INTO payouts(id,address,skr,state,created_at,updated_at) VALUES(?,?,?,?,?,?)",
                (new_id, address, total, "created", now, now))
            self._db.executemany(
                "UPDATE rewards SET state='claiming', payout_id=? WHERE id=? AND state='settled'",
                [(new_id, r["id"]) for r in rows])
            return {"payout": dict(self._db.execute("SELECT * FROM payouts WHERE id=?", (new_id,)).fetchone())}

    def settle_revealed(self, address: str) -> int:
        """Releases the lock on everything this account has scratched and not yet been paid.

        Rewards are paid when scratched, so a card does not wait out a lock window. Only cards
        that have been uncovered are released; an unscratched one stays as it is.
        """
        with self._lock, self._db:
            fam = self._family(address)
            q = ",".join("?" * len(fam))
            return self._db.execute(
                f"UPDATE rewards SET state='settled' WHERE payer IN ({q}) AND state='locked'"
                " AND payout_id IS NULL AND revealed_at IS NOT NULL", tuple(fam)).rowcount

    def revealed_unpaid(self) -> list[str]:
        """One address per account that has scratched cards waiting to be paid."""
        with self._lock:
            return [r[0] for r in self._db.execute(
                "SELECT DISTINCT payer FROM rewards WHERE state IN ('locked','settled')"
                " AND payout_id IS NULL AND revealed_at IS NOT NULL AND skr > 0")]

    def payout(self, pid: str) -> dict | None:
        with self._lock:
            r = self._db.execute("SELECT * FROM payouts WHERE id=?", (pid,)).fetchone()
        return dict(r) if r else None

    def open_payouts(self, limit: int = 50) -> list[dict]:
        with self._lock:
            return [dict(r) for r in self._db.execute(
                "SELECT * FROM payouts WHERE state IN ('created','sent') ORDER BY created_at LIMIT ?", (limit,))]

    def set_payout(self, pid: str, state: str, now: float, signature: str | None = None,
                   error: str | None = None) -> None:
        with self._lock, self._db:
            self._db.execute(
                "UPDATE payouts SET state=?, updated_at=?, signature=COALESCE(?, signature), error=? WHERE id=?",
                (state, now, signature, error, pid))

    def finish_payout(self, pid: str, signature: str | None, now: float) -> int:
        """The money arrived: the payout is confirmed and its rewards are paid, together."""
        with self._lock, self._db:
            self._db.execute(
                "UPDATE payouts SET state='confirmed', updated_at=?, signature=COALESCE(?, signature), error=NULL WHERE id=?",
                (now, signature, pid))
            return self._db.execute(
                "UPDATE rewards SET state='paid', paid_at=? WHERE payout_id=? AND state='claiming'",
                (now, pid)).rowcount

    def release_payout(self, pid: str, error: str, now: float) -> int:
        """The payout provably did not happen: its rewards go back to claimable, untouched."""
        with self._lock, self._db:
            self._db.execute("UPDATE payouts SET state='failed', updated_at=?, error=? WHERE id=?", (now, error, pid))
            return self._db.execute(
                "UPDATE rewards SET state='settled', payout_id=NULL WHERE payout_id=? AND state='claiming'",
                (pid,)).rowcount

    def payouts_for(self, address: str, limit: int = 20) -> list[dict]:
        with self._lock:
            fam = self._family(address)
            return [dict(r) for r in self._db.execute(
                "SELECT id, skr, state, signature, error, created_at, updated_at FROM payouts "
                f"WHERE address IN ({','.join('?' * len(fam))}) ORDER BY created_at DESC LIMIT ?", (*fam, limit))]

    # -- offers -----------------------------------------------------------------------------

    def put_offer(self, o: dict) -> None:
        import json as _json
        cols = ("id", "funder", "app", "what", "address", "token", "minimum", "reward",
                "budget", "starts", "ends", "window_hours", "link", "kind", "params", "featured", "icon")
        row = {**o, "params": _json.dumps(o.get("params") or {}), "kind": o.get("kind") or "pay",
               "featured": 1 if o.get("featured") else 0}
        with self._lock, self._db:
            self._db.execute(
                f"INSERT OR REPLACE INTO offers({','.join(cols)}) VALUES ({','.join('?' * len(cols))})",
                tuple(row.get(c) for c in cols),
            )

    def claims_since(self, offer_id: str, since: float) -> int:
        """How many people opened this offer lately — what makes an offer 'popular'."""
        with self._lock:
            return self._db.execute(
                "SELECT COUNT(*) FROM claims WHERE offer_id=? AND claimed_at>=?", (offer_id, since)).fetchone()[0]

    def offers(self, live_at: float | None = None) -> list[dict]:
        sql = "SELECT * FROM offers"
        args: tuple = ()
        if live_at is not None:
            sql += " WHERE starts <= ? AND ends >= ?"
            args = (live_at, live_at)
        with self._lock:
            rows = [dict(r) for r in self._db.execute(sql + " ORDER BY featured DESC, ends", args).fetchall()]
        import json as _json
        for r in rows:
            try:
                r["params"] = _json.loads(r.get("params") or "{}")
            except ValueError:
                r["params"] = {}
        return rows

    def offer(self, offer_id: str) -> dict | None:
        with self._lock:
            row = self._db.execute("SELECT * FROM offers WHERE id=?", (offer_id,)).fetchone()
        return dict(row) if row else None

    def claim_offer(self, offer_id: str, address: str, at: float) -> bool:
        """Opens a claim. False when this person already has one — re-claiming would restart
        the attribution window and let a payment be counted twice."""
        with self._lock, self._db:
            try:
                self._db.execute(
                    "INSERT INTO claims(offer_id,address,claimed_at) VALUES (?,?,?)",
                    (offer_id, address, at))
                return True
            except sqlite3.IntegrityError:
                return False

    def claim(self, offer_id: str, address: str) -> dict | None:
        with self._lock:
            fam = self._family(address)
            rows = self._db.execute(
                f"SELECT * FROM claims WHERE offer_id=? AND address IN ({','.join('?' * len(fam))})",
                (offer_id, *fam)).fetchall()
        # The one this wallet made if it made one, else the account's: one person claims an offer once.
        mine = [r for r in rows if r["address"] == address]
        pick = (mine or list(rows) or [None])[0]
        return dict(pick) if pick else None

    def claims_for(self, address: str) -> list[dict]:
        with self._lock:
            fam = self._family(address)
            return [dict(r) for r in self._db.execute(
                f"SELECT * FROM claims WHERE address IN ({','.join('?' * len(fam))})", tuple(fam)).fetchall()]

    def open_claims(self, before: float, limit: int = 100) -> list[dict]:
        """Claims still waiting on a payment, least recently looked at first."""
        with self._lock:
            return [dict(r) for r in self._db.execute(
                "SELECT * FROM claims WHERE state='open' AND checked_at <= ? ORDER BY checked_at LIMIT ?",
                (before, limit)).fetchall()]

    def offer_spent(self, offer_id: str) -> int:
        with self._lock:
            return self._db.execute(
                "SELECT COALESCE(SUM(skr),0) FROM claims WHERE offer_id=? AND state='paid'",
                (offer_id,)).fetchone()[0]

    def settle_claim(self, offer_id: str, address: str, state: str, skr: int,
                     signature: str | None, refused: str | None, at: float) -> bool:
        """Only an open claim can be settled, so two checks racing pay once."""
        with self._lock, self._db:
            cur = self._db.execute(
                "UPDATE claims SET state=?, skr=?, signature=?, refused=?, checked_at=?"
                " WHERE offer_id=? AND address=? AND state='open'",
                (state, int(skr), signature, refused, at, offer_id, address))
        return cur.rowcount == 1

    def touch_claim(self, offer_id: str, address: str, at: float, refused: str | None) -> None:
        """Looked at, nothing yet. The reason is kept so the app can say why it hasn't paid."""
        with self._lock, self._db:
            self._db.execute("UPDATE claims SET checked_at=?, refused=? WHERE offer_id=? AND address=?",
                             (at, refused, offer_id, address))

    def stats(self) -> dict:
        """Counts for the admin stats view (stats.py). Reads only."""
        from .stats import collect
        with self._lock:
            return collect(self._db)

    def offer_proof(self, offer_id: str) -> dict:
        """What a dApp is shown: how many verified Seeker owners opened it, how many paid, and
        the signatures. This is the asset the unfunded campaign exists to produce."""
        with self._lock:
            rows = self._db.execute("SELECT state, skr, signature FROM claims WHERE offer_id=?",
                                    (offer_id,)).fetchall()
        paid = [r for r in rows if r["state"] == "paid"]
        return {"offer_id": offer_id, "opened": len(rows), "paid": len(paid),
                "skr_paid": sum(r["skr"] for r in paid),
                "signatures": [r["signature"] for r in paid if r["signature"]]}

    # -- messages ---------------------------------------------------------------------------

    def add_message(self, sender: str, recipient: str, body: str, label: str = "") -> dict:
        now = time.time()
        with self._lock, self._db:
            cur = self._db.execute(
                "INSERT INTO messages(sender,recipient,label,body,sent_at) VALUES (?,?,?,?,?)",
                (sender, recipient, label, body, now),
            )
        return {"id": cur.lastrowid, "from": sender, "to": recipient, "label": label,
                "body": body, "at": now}

    def messages_for(self, recipient: str, since: float = 0.0, limit: int = 200) -> list[dict]:
        """What arrived for this address while it was not listening.

        Kept on the server only long enough to be collected — a phone that has read a message
        holds it, and asks for anything newer than the last one it has."""
        with self._lock:
            rows = self._db.execute(
                "SELECT id,sender,label,body,sent_at FROM messages"
                " WHERE recipient=? AND sent_at>? ORDER BY sent_at LIMIT ?",
                (recipient, since, limit),
            ).fetchall()
        return [{"id": r[0], "from": r[1], "label": r[2], "body": r[3], "at": r[4]} for r in rows]

    def messages_from(self, sender: str, since: float) -> int:
        with self._lock:
            return self._db.execute(
                "SELECT COUNT(*) FROM messages WHERE sender=? AND sent_at>?", (sender, since),
            ).fetchone()[0]

    def prune_messages(self, older_than: float) -> int:
        with self._lock, self._db:
            cur = self._db.execute("DELETE FROM messages WHERE sent_at < ?", (older_than,))
        return cur.rowcount

    def add_device(self, recipient: str, token: str) -> None:
        with self._lock, self._db:
            self._db.execute(
                "INSERT INTO devices(recipient,token,updated_at) VALUES (?,?,?)"
                " ON CONFLICT(recipient,token) DO UPDATE SET updated_at=excluded.updated_at",
                (recipient, token, time.time()),
            )

    def devices(self, recipient: str) -> list[str]:
        with self._lock:
            return [r[0] for r in self._db.execute("SELECT token FROM devices WHERE recipient=?", (recipient,))]

    # -- .air names -------------------------------------------------------------------------

    def claim_handle(self, name: str, address: str, now: float) -> str:
        """Gives `name` to this wallet's account. Returns "ok", "same" or "taken".

        One name per account: claiming a new one lets go of the old. A name held by another
        account is never taken over.
        """
        with self._lock, self._db:
            acct = self._db.execute("SELECT account_id FROM account_wallets WHERE address=?", (address,)).fetchone()
            account = acct[0] if acct else address
            row = self._db.execute("SELECT address, account FROM handles WHERE name=?", (name,)).fetchone()
            if row is not None:
                if (row["account"] or row["address"]) != account:
                    return "taken"
                self._db.execute("UPDATE handles SET address=? WHERE name=?", (address, name))
                return "same"
            self._db.execute("DELETE FROM handles WHERE account=?", (account,))
            self._db.execute("INSERT INTO handles(name,address,account,created_at) VALUES(?,?,?,?)",
                             (name, address, account, now))
            return "ok"

    def handle_address(self, name: str) -> str | None:
        with self._lock:
            r = self._db.execute("SELECT address FROM handles WHERE name=?", (name,)).fetchone()
        return r[0] if r else None

    def handles_like(self, prefix: str, limit: int = 5) -> list[dict]:
        """Names that start with `prefix`. A range scan, so no wildcard in the text can matter."""
        with self._lock:
            rows = self._db.execute(
                "SELECT name, address FROM handles WHERE name >= ? AND name < ? ORDER BY name LIMIT ?",
                (prefix, prefix + "\uffff", limit)).fetchall()
        return [{"name": r[0], "address": r[1]} for r in rows]

    def signature_settled(self, signature: str) -> bool:
        """Whether this transaction already settled an AirPay request, which has its own notice."""
        with self._lock:
            return self._db.execute("SELECT 1 FROM requests WHERE signature=?", (signature,)).fetchone() is not None

    def signature_paid_out(self, signature: str) -> bool:
        """Whether this transaction is one of our own SKR payouts, which has its own notice."""
        with self._lock:
            return self._db.execute("SELECT 1 FROM payouts WHERE signature=?", (signature,)).fetchone() is not None

    def claim_wallet_notice(self, key: str, signature: str, now: float) -> bool:
        """True the first time this transaction is announced to this account, and never again.

        The insert is the decision: two watcher passes, two server processes or a restart all
        ask the same row, and only one of them is told yes.
        """
        with self._lock, self._db:
            return self._db.execute(
                "INSERT OR IGNORE INTO wallet_notices(account_key,signature,sent_at) VALUES(?,?,?)",
                (key, signature, now)).rowcount == 1

    def prune_wallet_notices(self, older_than: float) -> int:
        with self._lock, self._db:
            return self._db.execute("DELETE FROM wallet_notices WHERE sent_at < ?", (older_than,)).rowcount

    def drop_device(self, token: str) -> None:
        with self._lock, self._db:
            self._db.execute("DELETE FROM devices WHERE token=?", (token,))

    # -- auth -------------------------------------------------------------------------------

    def open_nonces(self, address: str, now: float) -> int:
        with self._lock:
            return self._db.execute(
                "SELECT COUNT(*) FROM auth_nonces WHERE address=? AND used=0 AND expires_at>?",
                (address, now)).fetchone()[0]

    def drop_oldest_nonce(self, address: str, now: float) -> None:
        """Retires the oldest unused, unexpired challenge for an address, to make room."""
        with self._lock, self._db:
            self._db.execute(
                "DELETE FROM auth_nonces WHERE nonce = (SELECT nonce FROM auth_nonces"
                " WHERE address=? AND used=0 AND expires_at>? ORDER BY issued_at ASC, rowid ASC LIMIT 1)",
                (address, now))

    def put_nonce(self, nonce: str, address: str, message: str, issued: float, expires: float,
                  purpose: str = "signin") -> None:
        with self._lock, self._db:
            # Expired challenges are swept as new ones arrive, so the table cannot grow forever.
            self._db.execute("DELETE FROM auth_nonces WHERE expires_at < ?", (issued - 3600,))
            self._db.execute(
                "INSERT INTO auth_nonces(nonce,address,message,issued_at,expires_at,purpose) VALUES(?,?,?,?,?,?)",
                (nonce, address, message, issued, expires, purpose))

    def try_nonce(self, nonce: str, now: float, max_attempts: int) -> dict | None:
        """Counts one attempt against a live, unused challenge and returns it — atomically, in a
        single UPDATE, so two simultaneous tries cannot both be treated as the first."""
        with self._lock, self._db:
            cur = self._db.execute(
                "UPDATE auth_nonces SET attempts=attempts+1 "
                "WHERE nonce=? AND used=0 AND expires_at>? AND attempts<?",
                (nonce, now, max_attempts))
            if cur.rowcount != 1:
                return None
            r = self._db.execute("SELECT address,message,purpose FROM auth_nonces WHERE nonce=?", (nonce,)).fetchone()
            return dict(r) if r else None

    def consume_nonce(self, nonce: str) -> bool:
        with self._lock, self._db:
            return self._db.execute(
                "UPDATE auth_nonces SET used=1 WHERE nonce=? AND used=0", (nonce,)).rowcount == 1

    def put_session(self, token_hash: str, address: str, now: float, expires: float, keep: int,
                    account_id: str | None = None) -> None:
        with self._lock, self._db:
            self._db.execute(
                "INSERT INTO sessions(token_hash,address,created_at,expires_at,account_id) VALUES(?,?,?,?,?)",
                (token_hash, address, now, expires, account_id))
            # Only the newest `keep` stay live for one address.
            self._db.execute(
                "UPDATE sessions SET revoked_at=? WHERE address=? AND revoked_at IS NULL AND token_hash NOT IN "
                "(SELECT token_hash FROM sessions WHERE address=? AND revoked_at IS NULL "
                " ORDER BY created_at DESC LIMIT ?)",
                (now, address, address, keep))

    def session_address(self, token_hash: str, now: float) -> str | None:
        with self._lock:
            r = self._db.execute(
                "SELECT address FROM sessions WHERE token_hash=? AND revoked_at IS NULL AND expires_at>?",
                (token_hash, now)).fetchone()
        return r["address"] if r else None

    def session_info(self, token_hash: str, now: float) -> dict | None:
        """The wallet that signed in, and its account (looked up if the session predates accounts)."""
        with self._lock:
            r = self._db.execute(
                "SELECT address, account_id FROM sessions WHERE token_hash=? AND revoked_at IS NULL AND expires_at>?",
                (token_hash, now)).fetchone()
            if not r:
                return None
            acct = r["account_id"]
            if acct is None:
                w = self._db.execute("SELECT account_id FROM account_wallets WHERE address=?", (r["address"],)).fetchone()
                acct = w[0] if w else None
        return {"address": r["address"], "account_id": acct}

    # -- accounts: one person, several wallets -----------------------------------------------

    def _family(self, address: str) -> list[str]:
        """Every wallet in this wallet's account, itself included. The caller holds the lock."""
        r = self._db.execute("SELECT account_id FROM account_wallets WHERE address=?", (address,)).fetchone()
        if not r:
            return [address]
        out = [x[0] for x in self._db.execute(
            "SELECT address FROM account_wallets WHERE account_id=? ORDER BY linked_at, address", (r[0],))]
        return out if address in out else [address] + out

    def family(self, address: str) -> list[str]:
        with self._lock:
            return self._family(address)

    def account_of(self, address: str) -> str | None:
        with self._lock:
            r = self._db.execute("SELECT account_id FROM account_wallets WHERE address=?", (address,)).fetchone()
        return r[0] if r else None

    def ensure_account_ex(self, address: str, now: float) -> tuple[str, bool]:
        """The account this wallet belongs to, made (with this wallet in it) if it has none, and
        whether it was made just now — which is what a welcome card hangs on."""
        import uuid
        with self._lock, self._db:
            r = self._db.execute("SELECT account_id FROM account_wallets WHERE address=?", (address,)).fetchone()
            if r:
                return r[0], False
            acct = uuid.uuid4().hex
            self._db.execute("INSERT INTO accounts(id,created_at) VALUES(?,?)", (acct, now))
            self._db.execute("INSERT INTO account_wallets(address,account_id,linked_at) VALUES(?,?,?)", (address, acct, now))
            return acct, True

    def ensure_account(self, address: str, now: float) -> str:
        """The account this wallet belongs to, made (with this wallet in it) if it has none."""
        return self.ensure_account_ex(address, now)[0]

    # -- offer notices ----------------------------------------------------------------------

    def account_key(self, address: str) -> str:
        """Who a notice is for: the account, or the wallet itself when it has none yet."""
        a = self.account_of(address)
        return a or address

    def account_created_at(self, address: str) -> float | None:
        with self._lock:
            r = self._db.execute(
                "SELECT a.created_at FROM accounts a JOIN account_wallets w ON w.account_id=a.id WHERE w.address=?",
                (address,)).fetchone()
        return r[0] if r else None

    def device_recipients(self) -> list[str]:
        with self._lock:
            return [r[0] for r in self._db.execute("SELECT DISTINCT recipient FROM devices")]

    def notice_due(self, key: str) -> float | None:
        with self._lock:
            r = self._db.execute("SELECT next_due FROM notice_state WHERE account_key=?", (key,)).fetchone()
        return r[0] if r else None

    def set_notice_due(self, key: str, due: float) -> None:
        with self._lock, self._db:
            self._db.execute(
                "INSERT INTO notice_state(account_key,next_due) VALUES(?,?)"
                " ON CONFLICT(account_key) DO UPDATE SET next_due=excluded.next_due", (key, due))

    def notified_offers(self, key: str) -> dict[str, float]:
        with self._lock:
            return {r[0]: r[1] for r in self._db.execute(
                "SELECT offer_id, sent_at FROM offer_notices WHERE account_key=?", (key,))}

    def record_notice(self, key: str, offer_id: str, now: float) -> bool:
        with self._lock, self._db:
            return self._db.execute(
                "INSERT OR IGNORE INTO offer_notices(account_key,offer_id,sent_at) VALUES(?,?,?)",
                (key, offer_id, now)).rowcount == 1

    def signup_spent(self, week: int) -> int:
        """SKR handed out as welcome cards this week."""
        with self._lock:
            return self._db.execute(
                "SELECT COALESCE(SUM(skr),0) FROM rewards WHERE kind='signup' AND week=?", (week,)).fetchone()[0]

    def recharge_spent(self, week: int) -> int:
        """SKR handed out as recharge cards this week."""
        with self._lock:
            return self._db.execute(
                "SELECT COALESCE(SUM(skr),0) FROM rewards WHERE kind='recharge' AND week=?"
                " AND state!='reversed'", (week,)).fetchone()[0]

    def recharge_cards_since(self, payer: str, since: float) -> int:
        """Recharge cards this wallet's whole account has been given after `since`."""
        with self._lock:
            fam = self._family(payer)
            return self._db.execute(
                "SELECT COUNT(*) FROM rewards WHERE kind='recharge' AND state!='reversed'"
                f" AND created_at>=? AND payer IN ({','.join('?' * len(fam))})",
                (since, *fam)).fetchone()[0]

    def purchase(self, invoice_id: str) -> dict | None:
        """One purchase as it was remembered: who bought it, what kind, and what became of it."""
        with self._lock:
            r = self._db.execute(
                "SELECT invoice_id, buyer, status, category, product_id, delivered_at FROM purchases WHERE invoice_id=?",
                (invoice_id,)).fetchone()
            return dict(r) if r else None

    def link_wallet(self, account_id: str, address: str, now: float) -> str:
        """Puts a wallet in an account: "ok", or "other_account" if it already belongs to a
        different one. A wallet is never moved: that is what makes one that has been through the
        app before recognisable on a new phone."""
        with self._lock, self._db:
            r = self._db.execute("SELECT account_id FROM account_wallets WHERE address=?", (address,)).fetchone()
            if r:
                return "ok" if r[0] == account_id else "other_account"
            self._db.execute("INSERT INTO account_wallets(address,account_id,linked_at) VALUES(?,?,?)",
                             (address, account_id, now))
            return "ok"

    def revoke_session(self, token_hash: str, now: float) -> bool:
        with self._lock, self._db:
            return self._db.execute(
                "UPDATE sessions SET revoked_at=? WHERE token_hash=? AND revoked_at IS NULL",
                (now, token_hash)).rowcount == 1

    def revoke_all_sessions(self, address: str, now: float) -> int:
        with self._lock, self._db:
            return self._db.execute(
                "UPDATE sessions SET revoked_at=? WHERE address=? AND revoked_at IS NULL",
                (now, address)).rowcount
