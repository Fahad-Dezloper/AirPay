"""Help with one recharge, sent from the app to the support inbox.

The buyer writes only their question and the address to answer. Everything else in the email,
the order, its state, the supplier's own order id, the amount, the times, is read here from our
own records of an order this wallet placed, never taken from the app. So a message can always be
trusted to describe a real order, and a stranger cannot file one about someone else's.

Mail goes out through an SMTP account (for Gmail, an app password), to SUPPORT_TO, with the
buyer's address as Reply-To: pressing Reply in the inbox answers them directly.
"""

from __future__ import annotations

import logging
import re
import smtplib
import ssl
import time
import uuid
from email.message import EmailMessage
from email.utils import formataddr, make_msgid

from .topups import EMAIL, TopupRefused

log = logging.getLogger("duffle.support")

SCHEMA = """
CREATE TABLE IF NOT EXISTS support_tickets (
    id          TEXT PRIMARY KEY,
    order_id    TEXT NOT NULL,
    buyer       TEXT NOT NULL,
    email       TEXT NOT NULL,
    query       TEXT NOT NULL,
    created_at  REAL NOT NULL,
    mailed      INTEGER NOT NULL DEFAULT 0
);
CREATE INDEX IF NOT EXISTS support_buyer ON support_tickets(buyer, created_at);
"""

#: A wallet may write this many times an hour, and this many times a day about one order.
PER_HOUR = 5
PER_ORDER_PER_DAY = 3
MIN_QUERY, MAX_QUERY = 5, 2000

_STATE_WORDS = {
    "waiting": "Awaiting payment", "paying": "Processing (payment confirming)",
    "partial": "Part paid", "delivering": "Processing (sending to network)",
    "delivered": "Successful", "expired": "Cancelled (expired unpaid)",
    "cancelled": "Cancelled", "failed": "Failed", "refunded": "Refunded",
}


def _one_line(text: str, limit: int) -> str:
    # Header injection: nothing the buyer sends may start a new header line.
    return re.sub(r"[\r\n\t]+", " ", str(text or "")).strip()[:limit]


def _when(ts) -> str:
    try:
        return time.strftime("%Y-%m-%d %H:%M:%S UTC", time.gmtime(float(ts))) if ts else "-"
    except (TypeError, ValueError):
        return "-"


_SYMBOLS = {"INR": "₹", "USD": "$", "EUR": "€", "GBP": "£", "NGN": "₦", "PHP": "₱", "JPY": "¥", "BRL": "R$"}
_SHORT_STATE = {"waiting": "Awaiting payment", "paying": "Processing", "partial": "Part paid",
                "delivering": "Processing", "delivered": "Successful", "expired": "Cancelled",
                "cancelled": "Cancelled", "failed": "Failed", "refunded": "Refunded"}


def readable_money(face) -> str:
    """'49 INR' as a person reads it: ₹49 (the app shows it the same way)."""
    m = re.match(r"^\s*([\d.,]+)\s*([A-Za-z]{3})\s*$", str(face or ""))
    if not m:
        return str(face or "")
    sym = _SYMBOLS.get(m.group(2).upper())
    return f"{sym}{m.group(1)}" if sym else f"{m.group(1)} {m.group(2).upper()}"


def short_id(row: dict) -> str:
    return str(row["id"]).removeprefix("cu_")[:10].upper()


def subject_for(row: dict) -> str:
    what = readable_money(row.get("face")) or row.get("label") or ""
    state = _SHORT_STATE.get(row.get("state"), row.get("state") or "")
    return _one_line(f"CellUp help · Order {short_id(row)} · {row.get('operator', '')} {what} · {state}", 180)


def details_for(row: dict, app: str | None = None) -> list[tuple[str, str]]:
    return [
        ("Order", str(row["id"])),
        ("Supplier", str(row.get("supplier") or "cryptorefills")),
        ("Supplier order", str(row.get("cr_id") or "-")),
        ("Status", f"{_STATE_WORDS.get(row.get('state'), row.get('state'))} ({row.get('raw_state') or '-'})"),
        ("Number", str(row.get("phone") or "-")),
        ("Country", str(row.get("country") or "-")),
        ("Network", str(row.get("operator") or "-")),
        ("Plan", readable_money(row.get("face")) or str(row.get("label") or "-")),
        ("Amount", f"{row.get('usdc') or '-'} USDC"),
        ("Paid to", str(row.get("address") or "-")),
        ("Wallet", str(row.get("buyer") or "-")),
        ("Placed", _when(row.get("created_at"))),
        ("Last update", _when(row.get("updated_at"))),
        ("App", _one_line(app or "-", 40)),
    ]


class Mailer:
    """SMTP over TLS. Unset, it is off and the app offers the phone's mail app instead."""

    def __init__(self, user: str | None, password: str | None, to: str | None = None,
                 host: str = "smtp.gmail.com", port: int = 465, send=None):
        self.user, self.password = (user or "").strip(), (password or "").strip()
        self.to = (to or self.user or "").strip()
        self.host, self.port = host, port
        self._send = send or self._smtp

    @property
    def enabled(self) -> bool:
        return bool(self.user and self.password and self.to)

    def send(self, msg: EmailMessage) -> None:
        self._send(msg)

    def _smtp(self, msg: EmailMessage) -> None:
        with smtplib.SMTP_SSL(self.host, self.port, timeout=20, context=ssl.create_default_context()) as s:
            s.login(self.user, self.password)
            s.send_message(msg)


class SupportDesk:
    def __init__(self, store, topups, mailer: Mailer | None, now=time.time):
        self.store, self.topups, self.mailer, self._now = store, topups, mailer, now
        with store._lock, store._db:
            store._db.executescript(SCHEMA)

    @property
    def enabled(self) -> bool:
        return bool(self.mailer and self.mailer.enabled)

    def preview(self, buyer: str, order_id: str) -> dict:
        """What the email will say about the order, for the app to show before sending."""
        row = self.topups._mine(buyer, order_id)
        return {"to": "CellUp support", "subject": subject_for(row),
                "details": [{"label": k, "value": v} for k, v in details_for(row)[:12]],
                "mail": self.enabled}

    def send(self, buyer: str, order_id: str, *, email: str, query: str, app: str | None = None) -> dict:
        row = self.topups._mine(buyer, order_id)
        email = _one_line(email, 254)
        query = str(query or "").replace("\r\n", "\n").strip()
        if not EMAIL.match(email):
            raise TopupRefused("bad_email", "Enter the email we should reply to.")
        if len(query) < MIN_QUERY:
            raise TopupRefused("short_query", "Tell us what went wrong, in a sentence or two.")
        if len(query) > MAX_QUERY:
            raise TopupRefused("long_query", f"Keep it under {MAX_QUERY} characters.")
        now = self._now()
        with self.store._lock:
            db = self.store._db
            hour = db.execute("SELECT COUNT(*) FROM support_tickets WHERE buyer=? AND created_at>?",
                              (buyer, now - 3600)).fetchone()[0]
            day = db.execute("SELECT COUNT(*) FROM support_tickets WHERE order_id=? AND created_at>?",
                             (row["id"], now - 86400)).fetchone()[0]
        if hour >= PER_HOUR or day >= PER_ORDER_PER_DAY:
            raise TopupRefused("too_many", "We have your message already. We'll reply by email.", 429)

        ticket = "st_" + uuid.uuid4().hex
        if not self.enabled:
            # No mail account here: keep the ticket so it is listed, and let the app hand the
            # same message to the phone's mail app.
            self._keep(ticket, row["id"], buyer, email, query, now, mailed=False)
            return {"ok": True, "ticket": ticket, "mailed": False}
        msg = EmailMessage()
        msg["Subject"] = subject_for(row)
        msg["From"] = formataddr(("CellUp support form", self.mailer.user))
        msg["To"] = self.mailer.to
        msg["Reply-To"] = email
        msg["Message-ID"] = make_msgid(domain="cellup.app")
        rule = "━" * 20
        lines = ["Hi CellUp support,", "", query, "", "Thanks,", email, "",
                 rule, "ORDER DETAILS (from our records)", rule]
        lines += [f"{k}: {v}" for k, v in details_for(row, app)]
        lines += ["", rule, f"Customer email: {email}", f"Ticket: {ticket}",
                  "Reply to this email to answer the customer directly."]
        msg.set_content("\n".join(lines))
        try:
            self.mailer.send(msg)
        except (OSError, smtplib.SMTPException) as e:
            log.error("support mail for %s failed: %s", row["id"], type(e).__name__)
            raise TopupRefused("mail_failed", "Could not send right now. Try again in a minute.", 502) from None
        self._keep(ticket, row["id"], buyer, email, query, now, mailed=True)
        log.info("support ticket %s for %s mailed", ticket, row["id"])
        return {"ok": True, "ticket": ticket, "mailed": True}

    def _keep(self, ticket, order_id, buyer, email, query, now, *, mailed: bool) -> None:
        with self.store._lock, self.store._db:
            self.store._db.execute(
                "INSERT INTO support_tickets (id, order_id, buyer, email, query, created_at, mailed)"
                " VALUES (?,?,?,?,?,?,?)", (ticket, order_id, buyer, email, query, now, int(mailed)))

    def tickets(self, buyer: str, limit: int = 50) -> list[dict]:
        """This wallet's help requests, newest first, each with its order as it stands now."""
        with self.store._lock:
            rows = self.store._db.execute(
                "SELECT * FROM support_tickets WHERE buyer=? ORDER BY created_at DESC LIMIT ?",
                (buyer, limit)).fetchall()
        out = []
        for r in rows:
            r = dict(r)
            order = self.topups._row(r["order_id"])
            out.append({"id": r["id"], "created_at": r["created_at"], "email": r["email"],
                        "query": r["query"], "mailed": bool(r["mailed"]),
                        "order": self.topups.view(order) if order else None})
        return out
