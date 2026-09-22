"""
The server's actual job, independent of HTTP: know which requests are open, find out when they
are paid, and ring the payee. Everything network-facing is injected so tests run offline.
"""
from __future__ import annotations

import logging
import calendar
import re
import threading
import time
from collections import OrderedDict
from concurrent.futures import ThreadPoolExecutor

from . import chain, offers, patrol, rewards, verify
from .helius import AddressSync, signatures_in
from .notify import Fcm, Hub
from .store import Store

log = logging.getLogger("duffle.service")


def mint_of(req: dict) -> str | None:
    """The SPL mint a stored request asks for, or None for SOL."""
    token = req.get("token") or verify.SOL
    return None if token == verify.SOL else token


def next_check_after(age: float) -> float:
    """How long until an open request is looked at again. Young requests have a payer in
    their wallet right now; old ones may be paid tomorrow. Nothing ever expires."""
    if age < 60:
        return 1.0
    if age < 10 * 60:
        return 8.0
    if age < 24 * 3600:
        return 60.0
    return 3600.0


class Service:
    def __init__(self, store: Store, rpc, hub: Hub | None = None, fcm: Fcm | None = None,
                 facts=None,
                 helius: AddressSync | None = None, workers: int = 4):
        self.store = store
        self.rpc = rpc
        self.hub = hub or Hub()
        self.fcm = fcm or Fcm(None, None)
        self.helius = helius
        #: Reads Genesis and wallet age from the chain. None means rewards never pay out.
        self.facts = facts
        #: How suspicious a pattern has to be before a locked reward is taken back.
        self.watch = patrol.Watch()
        self._patrolled = 0.0
        self._pool = ThreadPoolExecutor(max_workers=workers, thread_name_prefix="check")
        self._inflight: set[str] = set()
        self._inflight_lock = threading.Lock()
        self._seen_sigs: OrderedDict[str, None] = OrderedDict()
        self._seen_lock = threading.Lock()

    # -- inputs -----------------------------------------------------------------------------

    def register(self, reference: str, recipient: str, amount: str, label: str = "",
                 payer: str | None = None, genesis: str | None = None,
                 token: str = "SOL", decimals: int = 9, symbol: str = "SOL", origin: str = "",
                 deliver: bool = True) -> dict:
        if not verify.is_address(reference):
            raise ValueError("invalid reference")
        if not verify.is_address(recipient):
            raise ValueError("invalid recipient")
        if payer is not None and (not verify.is_address(payer) or payer == recipient):
            raise ValueError("invalid payer")
        if genesis is not None and not verify.is_address(genesis):
            raise ValueError("invalid genesis")
        token = token or verify.SOL
        if token != verify.SOL and not verify.is_address(token):
            raise ValueError("invalid token")
        if isinstance(decimals, bool) or not isinstance(decimals, int):
            raise ValueError("invalid decimals")
        if token == verify.SOL:
            decimals, symbol = 9, "SOL"
        else:
            # The phone's word on decimals decides what "0.5" means, so check it against the
            # mint when the chain can be reached. An unreachable chain isn't a reason to refuse:
            # the payer's transferChecked is refused on-chain if the decimals are wrong anyway.
            try:
                real = self.rpc.mint_decimals(token)
            except (chain.Offline, chain.RpcError):
                real = decimals
            if real is None:
                raise ValueError("that token is not a mint")
            if real != decimals:
                raise ValueError("those decimals don't match the mint")
        symbol = str(symbol or "")[:12] or token[:4]
        want = verify.units(amount, decimals)
        if want is None:
            raise ValueError("invalid amount")
        label = str(label or "")[:64]
        result = self.store.register(reference, recipient, str(amount).strip(), want, label, payer, genesis,
                                     token, decimals, symbol, str(origin or "")[:16])
        if result.get("created"):
            if self.helius:
                self.helius.touch()
            # A request addressed to someone: the phones have parted, so the server carries it.
            #
            # `deliver` is how a phone says "I am the payer, I already have this" — the paying
            # phone registers the request itself before paying, so that the server is watching
            # and knows who to tell if it is withdrawn. Delivering it back to them rang their
            # own phone with their own request: "Seeker requests 1 USDC" on the Seeker, while
            # it was paying the OnePlus. The server cannot work this out for itself, because
            # nothing here says who is calling.
            if payer and deliver:
                self._deliver(payer, {"type": "request", "reference": reference, "recipient": recipient,
                                      "amount": str(amount).strip(), "label": label, "genesis": genesis or "",
                                      "token": token, "decimals": decimals, "symbol": symbol})
        return result

    def withdraw(self, reference: str) -> int:
        """The requester took their request back; tell the phone that was asked."""
        if not verify.is_address(reference):
            raise ValueError("invalid reference")
        row = self.store.withdraw(reference)
        if row is None:
            raise KeyError("unknown reference")
        if not row["payer"] or row["status"] != "open":
            return 0
        return self._deliver(row["payer"], {"type": "withdrawn", "reference": reference,
                                            "label": row["label"] or ""})

    def reject(self, reference: str, label: str = "") -> int:
        """The payer declined; tell the phone that asked."""
        if not verify.is_address(reference):
            raise ValueError("invalid reference")
        row = self.store.reject(reference, str(label or "")[:64])
        if row is None:
            raise KeyError("unknown reference")
        if row["status"] != "open":
            return 0
        return self._deliver(row["recipient"], {"type": "rejected", "reference": reference,
                                                "label": str(label or "")[:64]})

    def defer(self, reference: str, label: str = "") -> int:
        """The payer said "later" — tell the phone that asked.

        Deliberately not a rejection. The request stays open and payable; all that changes is
        that the asker sees "pending" instead of nothing, and hears about it if the app is
        closed. Someone who taps "Later" has said something, and the other person is standing
        there waiting to find out what.
        """
        if not verify.is_address(reference):
            raise ValueError("invalid reference")
        row = self.store.defer(reference, str(label or "")[:64])
        if row is None:
            raise KeyError("unknown reference")
        if row["status"] != "open":
            return 0
        return self._deliver(row["recipient"], {"type": "deferred", "reference": reference,
                                                "label": str(label or "")[:64],
                                                "amount": row["amount"], "symbol": row["symbol"] or "SOL"})

    def relay_tap(self, to: str, address: str, label: str = "", genesis: str | None = None,
                  brand: str | None = None) -> int:
        """An introduction the radio could not deliver because the phones parted before HELLO."""
        if not verify.is_address(to) or not verify.is_address(address) or to == address:
            raise ValueError("invalid address")
        if genesis is not None and not verify.is_address(genesis):
            raise ValueError("invalid genesis")
        # The phone maker, for the logo on the other screen. A short lowercase key or nothing —
        # never free text, since it ends up on someone else's screen.
        brand = brand if isinstance(brand, str) and re.fullmatch(r"[a-z]{2,12}", brand) else ""
        return self._deliver(to, {"type": "tap", "address": address, "label": str(label or "")[:64],
                                  "genesis": genesis or "", "brand": brand})

    #: What one phone may send another in an hour. A message costs nothing to send and lands on
    #: somebody else's lock screen, so the ceiling exists before anyone goes looking for it.
    MESSAGE_LIMIT = 30

    def send_message(self, frm: str, to: str, body: str, label: str = "") -> dict:
        """A line of text between two phones that have tapped.

        Payments carry no words, and the first thing anyone does before handing over money in
        person is check they have the right person. This is that check.

        **The body ends up on someone else's screen**, so it is treated the way `relay_tap`
        treats a brand: length-capped and stripped of anything that is not printable. It is
        never HTML, and nothing downstream renders it as such.

        Open question, recorded rather than hidden: anyone who knows an address may write to it.
        The rate limit is what stands in for a contact list until there is one.
        """
        if not verify.is_address(frm) or not verify.is_address(to) or frm == to:
            raise ValueError("invalid address")
        body = "".join(c for c in str(body or "") if c.isprintable() or c == "\n").strip()[:280]
        if not body:
            raise ValueError("empty message")
        recent = self.store.messages_from(frm, time.time() - 3600)
        if recent >= self.MESSAGE_LIMIT:
            raise ValueError("too many messages")
        row = self.store.add_message(frm, to, body, str(label or "")[:64])
        row["live"] = self._deliver(to, {"type": "message", "from": frm, "label": row["label"],
                                         "body": body, "at": row["at"], "id": row["id"]})
        return row

    def messages_for(self, recipient: str, since: float = 0.0) -> list[dict]:
        if not verify.is_address(recipient):
            raise ValueError("invalid recipient")
        return self.store.messages_for(recipient, since)

    def _deliver(self, to: str, event: dict) -> int:
        """Live stream first, push for a closed app. Returns how many listeners heard it."""
        live = self.hub.publish(to, event)
        if self.fcm.enabled:
            for token in self.store.devices(to):
                if self.fcm.send(token, event) == "gone":
                    self.store.drop_device(token)
        log.info("%s → %s… (%d live)", event["type"], to[:8], live)
        return live

    def add_device(self, recipient: str, token: str) -> None:
        if not verify.is_address(recipient):
            raise ValueError("invalid recipient")
        if not isinstance(token, str) or not (20 <= len(token) <= 4096):
            raise ValueError("invalid push token")
        self.store.add_device(recipient, token)
        if self.helius:
            self.helius.touch()

    def on_webhook(self, payload) -> int:
        """Queue every new signature for verification. Returns how many were queued."""
        fresh = []
        with self._seen_lock:
            for sig in signatures_in(payload):
                if sig in self._seen_sigs:
                    continue  # Helius may deliver the same event more than once
                self._seen_sigs[sig] = None
                fresh.append(sig)
            while len(self._seen_sigs) > 5000:
                self._seen_sigs.popitem(last=False)
        for sig in fresh:
            self._pool.submit(self._safe, self.check_signature, sig)
        return len(fresh)

    # -- rewards ----------------------------------------------------------------------------

    def reward_config(self) -> rewards.Config:
        """The defaults with whatever has been changed at runtime on top. Read per call, so a
        change takes effect on the next payment without a restart."""
        return rewards.Config().with_overrides(self.store.reward_config())

    def set_reward_config(self, values: dict) -> dict:
        """Change the numbers while the server runs. Only known settings, only sane values —
        `with_overrides` drops the rest, and this reports what it dropped."""
        allowed = {k: v for k, v in (values or {}).items() if k in rewards.Config.TUNABLE}
        ignored = sorted(set((values or {}).keys()) - set(allowed))
        before = self.reward_config()
        self.store.set_reward_config(allowed)
        after = self.reward_config()
        changed = {k: getattr(after, k) for k in allowed if getattr(before, k) != getattr(after, k)}
        if changed:
            log.info("reward config changed: %s", changed)
        return {"config": config_as_dict(after), "changed": changed, "ignored": ignored}

    def reward_for_payment(self, req: dict) -> rewards.Decision:
        """Run the rules over a payment that just settled, and write down what happened —
        including when nothing was earned. The refusals are how farming becomes visible, and
        how the app can answer "why didn't I get anything?"."""
        now = time.time()
        reference, payer, recipient = req["reference"], req.get("payer"), req["recipient"]
        if not payer:
            # A request registered by the asking phone alone: nobody to pay.
            self.store.record_attempt(reference, "", recipient, rewards.NO_PAYER, 0, now)
            return rewards.Decision(refused=rewards.NO_PAYER)

        # Asked of the chain by us. The phone's own claim to be a Seeker is not evidence when
        # money depends on it.
        payer_is_seeker = self.facts.is_seeker(payer) if self.facts else False
        if payer_is_seeker is None:
            self.store.record_attempt(reference, payer, recipient, rewards.CHAIN_UNREACHABLE, 0, now)
            return rewards.Decision(refused=rewards.CHAIN_UNREACHABLE)

        config = self.reward_config()
        week = rewards.week_of(now)
        history = self._reward_history(payer, recipient, payer_is_seeker, now, config)
        payment = rewards.Payment(reference, payer, recipient, req.get("token") or "SOL",
                                  int(req["lamports"]), req.get("origin") or "")
        decision = rewards.evaluate(payment, history, now, config)

        if decision.earned:
            written = self.store.record_rewards(
                reference, payer, recipient, [(a.kind, a.skr) for a in decision.awards],
                week, now, decision.unlock_at or now,
            )
            if written:
                log.info("reward %s SKR to %s… for %s…", decision.total, payer[:8], reference[:8])
                self._deliver(payer, {"type": "reward", "reference": reference, "skr": decision.total,
                                      "kinds": ",".join(a.kind for a in decision.awards),
                                      "unlock_at": decision.unlock_at or now})
        self.store.record_attempt(reference, payer, recipient, decision.refused, decision.total, now)
        return decision

    def _reward_history(self, payer: str, counterparty: str, payer_is_seeker: bool,
                        now: float, config: rewards.Config) -> rewards.History:
        """Everything the rules need to know about this payer, gathered once. Shared by the
        real decision and the preview — two copies of this would drift, and the app would end
        up promising a reward the server then refuses."""
        past = self.store.reward_history(payer, counterparty, now, rewards.week_of(now), month_start(now))
        return rewards.History(
            payer_is_seeker=payer_is_seeker,
            counterparty_is_seeker=bool(self.facts and self.facts.is_seeker(counterparty)),
            counterparty_first_seen=self.facts.first_seen(counterparty) if self.facts else None,
            rewarded_this_week=past["rewarded_this_week"],
            earned_in_campaign=past["earned_in_campaign"],
            earned_this_month=past["earned_this_month"],
            pool_left=max(0, config.weekly_pool - past["week_spent"]),
            last_reward_with_counterparty=past["last_reward_with_counterparty"],
            had_first_payment=past["had_first_payment"],
        )

    def reward_preview(self, payer: str, counterparty: str, token: str, units: int) -> dict:
        """What this payment *would* earn, asked before it is made.

        The point is the refusal. Someone about to pay a wallet made this morning should read
        "this won't earn" on the review screen, not work it out from a reward that never
        arrives. Nothing is written down, and a preview is never a promise: the real decision
        runs again on the settled payment, when the week's pool may have emptied.
        """
        now = time.time()
        config = self.reward_config()
        seeker = self.facts.is_seeker(payer) if self.facts else False
        if seeker is None:
            return {"skr": 0, "refused": rewards.CHAIN_UNREACHABLE}
        history = self._reward_history(payer, counterparty, seeker, now, config)
        payment = rewards.Payment("preview", payer, counterparty, token or "SOL", int(units), "tap")
        d = rewards.evaluate(payment, history, now, config)
        return {"skr": d.total, "refused": d.refused,
                "kinds": [a.kind for a in d.awards],
                "min_age_days": config.counterparty_min_age_days}

    # -- offers ----------------------------------------------------------------------------

    def offers_for(self, address: str) -> list[dict]:
        """Every live offer, with this person's own state on each. One call, because the app
        shows them in one list and a second round trip per offer would be absurd."""
        now = time.time()
        mine = {c["offer_id"]: c for c in self.store.claims_for(address)} if address else {}
        out = []
        for o in self.store.offers(live_at=now):
            c = mine.get(o["id"])
            out.append({**o, "spent": self.store.offer_spent(o["id"]),
                        "state": (c or {}).get("state", "available"),
                        "skr_earned": (c or {}).get("skr", 0),
                        "signature": (c or {}).get("signature"),
                        "refused": (c or {}).get("refused"),
                        "claimed_at": (c or {}).get("claimed_at")})
        return out

    def put_offer(self, body: dict) -> dict:
        """Creates or replaces an offer. Validated here rather than trusted: an offer with an
        unpayable address would sit in the app collecting claims that can never be satisfied."""
        now = time.time()
        offer_id = str(body.get("id") or "").strip()
        if not re.fullmatch(r"[A-Za-z0-9_-]{1,64}", offer_id):
            raise ValueError("id must be 1-64 characters of a-z, 0-9, - or _")
        address = str(body.get("address") or "")
        if not verify.is_address(address):
            raise ValueError("invalid address")
        token = str(body.get("token") or verify.SOL)
        if token != verify.SOL and not verify.is_address(token):
            raise ValueError("invalid token")
        days = float(body.get("days") or 30)
        row = {
            "id": offer_id, "funder": str(body.get("funder") or "duffle")[:32],
            "app": str(body.get("app") or "")[:48], "what": str(body.get("what") or "")[:120],
            "address": address, "token": token,
            "minimum": max(0, int(body.get("minimum") or 0)),
            "reward": max(0, int(body.get("reward") or 0)),
            "budget": max(0, int(body.get("budget") or 0)),
            "starts": float(body.get("starts") or now),
            "ends": float(body.get("ends") or (now + days * rewards.DAY)),
            "window_hours": float(body.get("window_hours") or 48),
            "link": str(body.get("link") or "")[:200],
        }
        if not row["app"] or not row["what"]:
            raise ValueError("an offer needs an app and a line saying what to do")
        self.store.put_offer(row)
        log.info("offer %s: %s — %s SKR, budget %s", offer_id, row["what"], row["reward"], row["budget"])
        return {"ok": True, "offer": row}

    def claim_offer(self, offer_id: str, address: str) -> dict:
        """Opens an offer for one person. The clock on the attribution window starts here, so
        this is also the moment that makes any later payment provably caused by the offer."""
        if not verify.is_address(address):
            raise ValueError("invalid address")
        row = self.store.offer(offer_id)
        if row is None:
            raise KeyError(offer_id)
        offer = _offer(row)
        now = time.time()
        seeker = self.facts.is_seeker(address) if self.facts else False
        if seeker is None:
            return {"ok": False, "refused": rewards.CHAIN_UNREACHABLE}
        existing = self.store.claim(offer_id, address)
        why = offers.can_claim(offer, now, bool(seeker),
                               offers.Claim(offer_id, address, existing["claimed_at"], existing["state"])
                               if existing else None,
                               self.store.offer_spent(offer_id))
        if why:
            return {"ok": False, "refused": why}
        if not self.store.claim_offer(offer_id, address, now):
            return {"ok": False, "refused": offers.ALREADY_CLAIMED}
        log.info("offer %s opened by %s…", offer_id, address[:8])
        return {"ok": True, "claimed_at": now, "window_hours": offer.window_hours}

    def check_claims(self, now: float | None = None, limit: int = 20) -> int:
        """Looks for the payment each open claim is waiting for.

        It watches the **claimer's** wallet rather than the dApp's: a real app's address has
        far too much traffic to read, while the set of people who opened an offer is small and
        known. Settled claims stop being looked at.
        """
        now = now or time.time()
        settled = 0
        for c in self.store.open_claims(now - 60.0, limit):
            row = self.store.offer(c["offer_id"])
            if row is None:
                continue
            offer = _offer(row)
            claim = offers.Claim(c["offer_id"], c["address"], c["claimed_at"], c["state"])
            try:
                payments = self._payments_from(c["address"], offer, claim.claimed_at)
            except (chain.Offline, chain.RpcError) as e:
                log.info("offer check for %s… deferred: %s", c["address"][:8], e)
                continue
            d = offers.settle(offer, claim, payments, now, self.store.offer_spent(offer.id),
                              self._pool_left(now))
            if d.earned:
                if self.store.settle_claim(offer.id, c["address"], offers.PAID, d.skr,
                                           d.signature, None, now):
                    settled += 1
                    # Written into the SAME ledger as tap rewards, not a second one beside it.
                    # Two ledgers meant the app showed a balance that left offers out, and —
                    # worse — the weekly pool never saw the spend, so the promise that an
                    # unfunded campaign costs nothing beyond the budget was not actually kept.
                    # kind="offer" keeps it out of the weekly payment count and the streak,
                    # which are about paying people, while the caps and the pool now include it.
                    config = self.reward_config()
                    self.store.record_rewards(
                        d.signature or f"offer:{offer.id}:{c['address']}", c["address"], offer.address,
                        [("offer", d.skr)], rewards.week_of(now), now, now + config.lock_days * rewards.DAY)
                    log.info("offer %s paid %s SKR to %s… (%s…)", offer.id, d.skr,
                             c["address"][:8], (d.signature or "")[:8])
                    self._deliver(c["address"], {"type": "offer", "offer": offer.id, "app": offer.app,
                                                 "skr": d.skr, "signature": d.signature or ""})
            elif d.refused == offers.OUTSIDE_WINDOW:
                self.store.settle_claim(offer.id, c["address"], offers.EXPIRED, 0, None, d.refused, now)
            else:
                self.store.touch_claim(offer.id, c["address"], now, d.refused)
        return settled

    def _payments_from(self, address: str, offer: offers.Offer, since: float) -> list[offers.Payment]:
        """What this wallet paid the offer's address since it was opened."""
        mint = None if offer.token == verify.SOL else offer.token
        out: list[offers.Payment] = []
        for entry in (self.rpc.signatures_with_time(address) if hasattr(self.rpc, "signatures_with_time")
                      else [{"signature": s, "blockTime": None} for s in self.rpc.signatures(address)]):
            at = entry.get("blockTime")
            if at is not None and at < since:
                continue  # older than the claim; cannot have been caused by it
            tx = self.rpc.transaction(entry["signature"])
            got = verify.received(tx, offer.address, mint)
            if got > 0:
                out.append(offers.Payment(entry["signature"], offer.address, offer.token, got,
                                          float(at or (tx or {}).get("blockTime") or since)))
        return out

    def _pool_left(self, now: float) -> int:
        """Offers spend the same weekly pool as tap rewards — that is what makes an unfunded
        campaign cost nothing beyond what is already budgeted."""
        config = self.reward_config()
        spent = self.store.reward_history("", "", now, rewards.week_of(now), month_start(now))["week_spent"]
        return max(0, config.weekly_pool - spent)

    # -- looking back --------------------------------------------------------------------

    def patrol(self, now: float | None = None) -> dict:
        """Settle what has matured, reverse what shouldn't have been paid.

        Run on a slow timer rather than at payment time: the patterns it looks for only exist
        after the fact. Reversals are logged individually, because taking money back is the one
        thing here that needs to be explainable to the person it happened to.
        """
        now = now or time.time()
        found = patrol.reversals(
            self.store.locked_rewards(now),
            self.store.settled_payments(now - 7 * rewards.DAY),
            now,
            self.watch,
        )
        reversed_count = 0
        for rev in found:
            n = self.store.reverse_rewards(rev.reference, f"{rev.reason}: {rev.detail}")
            if n:
                reversed_count += n
                log.warning("reversed %d reward(s) for %s…: %s", n, rev.reference[:8], rev.detail)
        # Settling last, so a reward found suspicious in this same pass is reversed before its
        # window closes rather than a second later.
        settled = self.store.settle_matured(now)
        if settled:
            log.info("%d reward(s) settled", settled)
        return {"reversed": reversed_count, "settled": settled}

    def reward_ledger(self, days: float = 30.0) -> dict:
        out = self.store.reward_ledger(time.time() - days * rewards.DAY)
        out["config"] = config_as_dict(self.reward_config())
        return out

    def reward_summary(self, address: str) -> dict:
        """What one person has earned, for their rewards screen."""
        now = time.time()
        config = self.reward_config()
        week = rewards.week_of(now)
        past = self.store.reward_history(address, "", now, week, month_start(now))
        balance = self.store.reward_balance(address, now)
        return {
            "balance": balance,
            "this_week": {"rewarded_payments": past["rewarded_this_week"], "of": config.weekly_payments},
            "this_month": {"earned": past["earned_this_month"], "cap": config.monthly_cap},
            "pool_left": max(0, config.weekly_pool - past["week_spent"]),
            "recent": self.store.rewards_for(address, 20),
            # Sent alongside the earnings on purpose: a screen that only shows what was paid
            # makes the rules look arbitrary the first time one refuses.
            "refused": self.store.refusals_for(address, 10),
            "rates": {"first_payment": config.first_payment, "per_payment": config.per_payment,
                      "streak": config.streak, "streak_needed": config.streak_needed,
                      "lock_days": config.lock_days},
        }

    # -- checks -----------------------------------------------------------------------------

    def check_signature(self, signature: str) -> list[str]:
        """A webhook hint: fetch the transaction ourselves and settle any open request it pays."""
        tx = self.rpc.transaction(signature)
        if tx is None:
            return []
        settled = []
        for req in self.store.open_among(verify.account_keys(tx)):
            v = verify.verify_transfer(tx, req["reference"], req["recipient"], req["lamports"], mint_of(req))
            if v.kind == "paid" and self._settle(req, signature):
                settled.append(req["reference"])
            elif v.kind == "rejected":
                log.info("ref %s… seen in %s… but %s", req["reference"][:8], signature[:8], v.why)
        return settled

    def check_reference(self, reference: str) -> str:
        req = self.store.get(reference)
        if not req or req["status"] != "open":
            return req["status"] if req else "unknown"
        try:
            kind, sig, why = chain.find_payment(self.rpc, reference, req["recipient"], req["lamports"], mint_of(req))
        except (chain.Offline, chain.RpcError) as e:
            # Unknown is not unpaid: look again soon, say nothing to anyone.
            self.store.schedule(reference, time.time() + 5)
            log.info("check %s… deferred: %s", reference[:8], e)
            return "unknown"
        if kind == "paid" and self._settle(req, sig):
            return "paid"
        if kind == "short":
            log.info("ref %s… used by %s… but %s", reference[:8], sig[:8], why)
        age = time.time() - req["created_at"]
        self.store.schedule(reference, time.time() + next_check_after(age))
        return kind

    def sweep(self, now: float | None = None) -> int:
        """Queue every request that is due. Called about once a second by the watcher."""
        due = self.store.due(now if now is not None else time.time())
        queued = 0
        for req in due:
            ref = req["reference"]
            with self._inflight_lock:
                if ref in self._inflight:
                    continue
                self._inflight.add(ref)
            # Push the next check out now so a slow RPC doesn't get this queued twice.
            self.store.schedule(ref, time.time() + 30)
            self._pool.submit(self._run_check, ref)
            queued += 1
        return queued

    def _run_check(self, ref: str) -> None:
        try:
            self._safe(self.check_reference, ref)
        finally:
            with self._inflight_lock:
                self._inflight.discard(ref)

    def _settle(self, req: dict, signature: str) -> bool:
        if not self.store.mark_paid(req["reference"], signature):
            return False
        self._safe(self.reward_for_payment, req)
        event = {"type": "paid", "reference": req["reference"], "signature": signature, "amount": req["amount"],
                 "symbol": req.get("symbol") or "SOL"}
        log.info("PAID %s… by %s…", req["reference"][:8], signature[:8])
        self._deliver(req["recipient"], event)
        return True

    @staticmethod
    def _safe(fn, *args):
        try:
            return fn(*args)
        except Exception:
            log.exception("check failed")
            return None

    #: How often the look-back runs. Nothing it finds is urgent — the reward it might reverse
    #: is locked for a week — so this stays well away from the payment sweep.
    PATROL_EVERY = 10 * 60.0

    def run_watcher(self, stop: threading.Event, every: float = 1.0) -> None:
        while not stop.is_set():
            self._safe(self.sweep)
            self._safe(self.check_claims)
            if time.time() - self._patrolled > self.PATROL_EVERY:
                self._patrolled = time.time()
                self._safe(self.patrol)
            stop.wait(every)


def config_as_dict(c: rewards.Config) -> dict:
    """The numbers, for the admin endpoint and for the app's rewards screen."""
    out = {k: getattr(c, k) for k in rewards.Config.TUNABLE}
    out["minimums"] = dict(c.minimums)
    return out


def month_start(at: float) -> float:
    """Midnight UTC on the first of the month `at` falls in — the monthly cap's window."""
    t = time.gmtime(at)
    return calendar.timegm((t.tm_year, t.tm_mon, 1, 0, 0, 0, 0, 0, 0))


def _offer(row: dict) -> offers.Offer:
    """A stored row as the rules want it."""
    return offers.Offer(
        id=row["id"], funder=row["funder"], app=row["app"], what=row["what"],
        address=row["address"], token=row["token"], minimum=row["minimum"],
        reward=row["reward"], budget=row["budget"], starts=row["starts"], ends=row["ends"],
        window_hours=row["window_hours"], link=row["link"],
    )
