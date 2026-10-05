"""
The server's actual job, independent of HTTP: know which requests are open, find out when they
are paid, and ring the payee. Everything network-facing is injected so tests run offline.
"""
from __future__ import annotations

import hashlib
import hmac
import json
import logging
import calendar
import os
import secrets
import re
import threading
import time
from collections import OrderedDict
from concurrent.futures import ThreadPoolExecutor

from .auth import Auth
from .payer_client import Payer, PayerError
from .prices import Prices
from . import stakes, swaps
from . import activity, chain, offers, patrol, rewards, verify
from .helius import AddressSync, signatures_in
from .notify import Fcm, Hub
from .store import Store

log = logging.getLogger("duffle.service")

#: Tokens worth a dollar a unit, for valuing a payment between people: the mainnet stablecoins a
#: swap already treats that way, and devnet USDC so the same rule holds where payments are tested.
DOLLAR_TOKENS = swaps.STABLES | {"4zMMC9srt5Ri5X14GAgXhaHii3GnPAEERYPJgZJDncDU"}


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
                 helius: AddressSync | None = None, workers: int = 4, payer: Payer | None = None, prices=None):
        self.store = store
        #: The separate service that holds the SKR key. None of its internals are reachable from here.
        self.payer = payer if payer is not None else Payer(
            os.environ.get("PAYER_URL"), os.environ.get("PAYER_HMAC_SECRET_FILE"))
        #: Proves who is calling. See auth.py.
        self.auth = Auth(store, domain=os.environ.get("AUTH_DOMAIN", "airpay"))
        self.rpc = rpc
        #: SOL/USD for valuing swaps. Injectable so tests never touch the network.
        self.prices = prices or Prices()
        self.hub = hub or Hub()
        self.fcm = fcm or Fcm(None, None)
        self.helius = helius
        #: Reads Genesis and wallet age from the chain. None means rewards never pay out.
        self.facts = facts
        #: How suspicious a pattern has to be before a locked reward is taken back.
        self.watch = patrol.Watch()
        self._patrolled = 0.0
        self._noticed = 0.0
        self._looked = 0.0
        self._paid_at = 0.0
        self._activity_seen: dict[str, dict[str, None]] = {}
        self._activity_since: dict[str, float] = {}
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

        # Paying another of your OWN wallets is paying yourself, however many you have linked.
        if recipient in self.store.family(payer):
            self.store.record_attempt(reference, payer, recipient, rewards.SELF_PAYMENT, 0, now)
            return rewards.Decision(refused=rewards.SELF_PAYMENT)

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
        # Whoever invited this payer, if this was the payment that proved they turned up.
        if any(a.kind == rewards.FIRST_PAYMENT for a in decision.awards):
            self._pay_referrer(payer, reference, now, config)
        return decision

    def send_offer_notices(self, now: float | None = None) -> int:
        """Tells each person about ONE offer at a time, a few hours apart, by push.

        The notification page used to list every live offer the moment somebody signed up — three
        at once, before they had seen the app. Now an offer reaches the page only after a push has
        gone out for it, the first a few hours after sign-up and the rest spaced out. Only people
        with a phone registered for push are scheduled (that is who a push can reach), and only
        Seekers (offers are for Seekers). Returns how many were sent.
        """
        now = now if now is not None else time.time()
        sent, handled = 0, set()
        for addr in self.store.device_recipients():
            key = self.store.account_key(addr)
            if key in handled:
                continue
            handled.add(key)
            due = self.store.notice_due(key)
            if due is None:
                created = self.store.account_created_at(addr) or now
                due = created + self.OFFER_FIRST_DELAY
                self.store.set_notice_due(key, due)
            if now < due:
                continue
            family = self.store.family(addr)
            told = self.store.notified_offers(key)
            claimed = {c["offer_id"] for c in self.store.claims_for(addr)}
            nxt = next((o for o in self.store.offers(live_at=now)
                        if o["id"] not in told and o["id"] not in claimed), None)
            if nxt is None:
                self.store.set_notice_due(key, now + self.OFFER_RECHECK)
                continue
            answers = [self.facts.is_seeker(w) for w in family] if self.facts else [False]
            if any(a is True for a in answers):
                pass
            elif any(a is None for a in answers):
                self.store.set_notice_due(key, now + self.OFFER_CHAIN_RETRY)
                continue
            else:
                self.store.set_notice_due(key, now + self.OFFER_NOT_SEEKER_RETRY)
                continue
            if self.store.record_notice(key, nxt["id"], now):
                self._push_offer(family, nxt)
                sent += 1
            self.store.set_notice_due(key, now + self.OFFER_GAP)
        return sent

    def _push_offer(self, family: list[str], offer: dict) -> None:
        """One push per phone — a phone registered under two of a person's wallets hears it once."""
        event = {"type": "offer", "state": "new", "offer": offer["id"], "app": offer["app"],
                 "skr": offer["reward"], "detail": offer["what"]}
        for w in family:
            self.hub.publish(w, event)
        if self.fcm.enabled:
            tokens = {t for w in family for t in self.store.devices(w)}
            for token in tokens:
                if self.fcm.send(token, event) == "gone":
                    self.store.drop_device(token)
        log.info("offer %s told to %s…", offer["id"], family[0][:8])

    def sign_in(self, nonce: str, signature_b64: str) -> dict:
        """Signs in, and gives a brand-new account its welcome card.

        Only the FIRST sign-in of an account earns it: a wallet that has been through the app
        before — on any phone, or as a second wallet of an account that has one — comes back to
        its account and gets nothing more.
        """
        out = self.auth.verify(nonce, signature_b64)
        if out.get("new_account"):
            try:
                self._grant_signup(out["address"], out["wallets"][0] if out.get("wallets") else out["address"])
            except Exception:  # a welcome card must never stop somebody signing in
                log.exception("welcome card failed")
        return out

    def _grant_signup(self, address: str, account_key: str) -> bool:
        config = self.reward_config()
        if config.signup <= 0:
            return False
        now = time.time()
        week = rewards.week_of(now)
        # One weekly budget for everyone. Past it, nobody is given a card until the week turns:
        # a wallet is free to make, so this is the line that bounds what making many costs.
        if self.store.signup_spent(week) + config.signup > config.signup_pool:
            log.info("welcome card for %s… not given: this week's budget is spent", address[:8])
            return False
        reference = f"signup-{account_key}"
        unlock = now + config.lock_days * rewards.DAY
        written = self.store.record_rewards(reference, address, "", [(rewards.SIGNUP, config.signup)],
                                            week, now, unlock)
        if written:
            log.info("welcome card: %s SKR to %s…", config.signup, address[:8])
            self._deliver(address, {"type": "reward", "reference": reference, "skr": config.signup,
                                    "kinds": rewards.SIGNUP, "unlock_at": unlock})
        return bool(written)

    def _pay_referrer(self, invitee: str, reference: str, now: float, config: rewards.Config) -> None:
        """Pays the inviter, once, on the invitee's first rewarded payment.

        Hung off the first payment rather than off the claim, because a claim costs nothing: a
        wallet is free, so "somebody joined with my code" is worth exactly nothing until they
        have made a real payment the rules already agreed to reward. That single condition is
        what stops a referral from being the cheapest reward in the system to manufacture.
        """
        link = self.store.referral_of(invitee)
        if link is None or link.get("paid_at") is not None:
            return
        inviter = link["inviter"]
        invitee = link["invitee"]  # the wallet that claimed the code, whichever of their wallets paid
        past = self.store.reward_history(inviter, "", now, rewards.week_of(now), month_start(now))
        decision = rewards.referral_award(
            rewards.History(
                payer_is_seeker=True,
                earned_in_campaign=past["earned_in_campaign"],
                earned_this_month=past["earned_this_month"],
                pool_left=max(0, config.weekly_pool - past["week_spent"]),
            ),
            now, config,
        )
        if not decision.earned:
            # Not marked paid: the inviter is owed this, and a later first payment by somebody
            # else they invited will find the pool refilled.
            log.info("referral for %s… not paid: %s", inviter[:8], decision.refused)
            return
        written = self.store.record_rewards(
            reference, inviter, invitee, [(a.kind, a.skr) for a in decision.awards],
            rewards.week_of(now), now, decision.unlock_at or now,
        )
        if written:
            self.store.mark_referral_paid(invitee, now)
            log.info("referral %s SKR to %s… for inviting %s…",
                     decision.total, inviter[:8], invitee[:8])
            self._deliver(inviter, {"type": "referral", "state": "paid", "invitee": invitee,
                                    "skr": decision.total})
            self._deliver(inviter, {"type": "reward", "reference": reference,
                                    "skr": decision.total, "kinds": rewards.REFERRAL,
                                    "unlock_at": decision.unlock_at or now})

    # -- referrals --------------------------------------------------------------------------

    def referral_summary(self, address: str) -> dict:
        """This wallet's own code, and how the people it invited are getting on."""
        config = self.reward_config()
        invited = self.store.referrals_by(address)
        now = time.time()
        past = self.store.reward_history(address, "", now, rewards.week_of(now), month_start(now))
        return {
            # Whether this wallet may still enter somebody else's code: the same two conditions
            # `claim_referral` refuses on, said in advance so the app can take the door away
            # instead of letting a person walk into a refusal.
            "can_claim": self.store.referral_of(address) is None and not past["had_first_payment"],
            "code": self.store.referral_code(address, time.time()),
            "reward": config.referral,
            # What the person being invited gets, which is the half worth putting in the
            # message: it is their reason to accept, not ours.
            "their_reward": config.first_payment,
            "invited": len(invited),
            "paid": sum(1 for r in invited if r.get("paid_at")),
            "earned": sum(1 for r in invited if r.get("paid_at")) * config.referral,
            # Who was invited, so the screen can show progress rather than a bare count.
            "people": [{"invitee": r["invitee"], "at": r["at"], "paid": bool(r.get("paid_at"))}
                       for r in invited[:20]],
        }

    def claim_referral(self, code: str, address: str) -> dict:
        """Records that `address` was invited by whoever owns `code`.

        Every refusal here is a rule somebody would otherwise farm: your own code, a second
        code after the first, or a code produced after you had already earned — which would
        turn an invitation into a receipt for people who were already here.
        """
        inviter = self.store.address_for_code(code or "")
        if inviter is None:
            return {"ok": False, "refused": rewards.UNKNOWN_CODE}
        if inviter == address or inviter in self.store.family(address):
            return {"ok": False, "refused": rewards.SELF_REFERRAL}
        if self.store.referral_of(address) is not None:
            return {"ok": False, "refused": rewards.ALREADY_REFERRED}
        now = time.time()
        past = self.store.reward_history(address, "", now, rewards.week_of(now), month_start(now))
        if past["had_first_payment"]:
            return {"ok": False, "refused": rewards.REFERRAL_TOO_LATE}
        if not self.store.record_referral(address, inviter, now):
            return {"ok": False, "refused": rewards.ALREADY_REFERRED}
        # Both sides see it at once: the inviter's referral sheet gains a row, the invitee's says
        # who invited them. Neither has to reopen anything.
        self._deliver(inviter, {"type": "referral", "state": "joined", "invitee": address})
        self._deliver(address, {"type": "referral", "state": "claimed", "inviter": inviter})
        return {"ok": True, "inviter": inviter}

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
        told = self.store.notified_offers(self.store.account_key(address)) if address else {}
        out = []
        min_age = self.reward_config().counterparty_min_age_days
        for o in self.store.offers(live_at=now):
            c = mine.get(o["id"])
            offer = _offer(o)
            # The page's text is sent once, as the fields below, not a second time inside params.
            params = {k: v for k, v in offer.params.items() if k not in offers.COPY_KEYS}
            out.append({**o, "params": params, "spent": self.store.offer_spent(o["id"]),
                        # Everything the offer's page says, written here from the same numbers the
                        # watcher checks, so the app holds no text that could disagree with it.
                        "steps": offers.steps_for(offer),
                        "rules": offers.rules_for(offer, min_age),
                        "cta": offers.cta_for(offer),
                        "about": offers.about_for(offer),
                        "where": offers.where_for(offer),
                        "guide": offers.guide_for(offer),
                        "payout": offers.payout_for(offer),
                        "needs": offers.needs_for(offer),
                        "order": offers.order_of(offer),
                        "reward_text": offers.reward_text(offer),
                        "reward_min": offer.reward_min if offer.variable else None,
                        "progress": self._progress(offer, c),
                        # When this person was TOLD about it (a push), or null: the notification page
                        # shows an offer only once they have been.
                        "notified_at": told.get(o["id"]),
                        "state": (c or {}).get("state", "available"),
                        "skr_earned": (c or {}).get("skr", 0),
                        "signature": (c or {}).get("signature"),
                        "refused": (c or {}).get("refused"),
                        "claims_7d": self.store.claims_since(o["id"], now - 7 * rewards.DAY),
                        "claimed_at": (c or {}).get("claimed_at")})
        # Lowest `order` first. The sort is stable, so offers that name no order keep the order
        # the store gave them (featured, then ending soonest) and come after those that do.
        out.sort(key=lambda r: r["order"])
        return out

    def _progress(self, offer: offers.Offer, c: dict | None) -> dict | None:
        """How far this person's own claim has got, for an offer that counts several things.
        None when there is nothing to count: not claimed, expired, or a single action."""
        if not c or offer.need <= 1 or c.get("state") not in (offers.OPEN, offers.PAID):
            return None
        if c["state"] == offers.PAID:
            return {"done": offer.need, "need": offer.need}
        try:
            seen = self._seen_for(c["address"], offer, c["claimed_at"])
        except (chain.Offline, chain.RpcError):
            return None
        t = offers.tally(offer, offers.Claim(offer.id, c["address"], c["claimed_at"], c["state"]), seen)
        return {"done": t.done, "need": t.need}

    def put_offer(self, body: dict) -> dict:
        """Creates or replaces an offer. Validated here rather than trusted: an offer with an
        unpayable address would sit in the app collecting claims that can never be satisfied."""
        now = time.time()
        offer_id = str(body.get("id") or "").strip()
        if not re.fullmatch(r"[A-Za-z0-9_-]{1,64}", offer_id):
            raise ValueError("id must be 1-64 characters of a-z, 0-9, - or _")
        kind = str(body.get("kind") or "pay")
        if kind not in offers.KINDS:
            raise ValueError("kind must be one of " + ", ".join(offers.KINDS))
        address = str(body.get("address") or "")
        # Only a "pay" offer names an address that has to be paid.
        if kind == "pay" and not verify.is_address(address):
            raise ValueError("invalid address")
        # `reward_min`, `cta` and `steps` may be given beside the offer's other fields; they are
        # kept with the params, which is where everything about how an offer behaves already lives.
        raw = dict(body.get("params")) if isinstance(body.get("params"), dict) else {}
        for key in ("reward_min", "steps", "order", *offers.COPY_KEYS):
            if body.get(key) is not None:
                raw[key] = body[key]
        cta = body.get("cta")
        if isinstance(cta, dict):
            raw["cta"] = cta.get("target")
            if cta.get("label"):
                raw["cta_label"] = cta["label"]
        elif cta is not None:
            raw["cta"] = cta
        params = self._clean_params(raw, kind)
        reward = max(0, int(body.get("reward") or 0))
        # The payer refuses a single payout above its cap. An offer promising more would collect
        # claims it can never pay.
        if reward > offers.MAX_REWARD:
            raise ValueError(f"reward must be {offers.MAX_REWARD} SKR or less")
        if "reward_min" in params and params["reward_min"] > reward:
            raise ValueError("reward_min cannot be more than reward")
        if params.get("cta") == "link" and not str(body.get("link") or "").startswith("https://"):
            raise ValueError("a link button needs an https link")
        token = str(body.get("token") or verify.SOL)
        if token != verify.SOL and not verify.is_address(token):
            raise ValueError("invalid token")
        days = float(body.get("days") or 30)
        row = {
            "id": offer_id, "funder": str(body.get("funder") or "duffle")[:32],
            "app": str(body.get("app") or "")[:48], "what": str(body.get("what") or "")[:120],
            "address": address, "token": token,
            "minimum": max(0, int(body.get("minimum") or 0)),
            "reward": reward,
            "budget": max(0, int(body.get("budget") or 0)),
            "starts": float(body.get("starts") or now),
            "ends": float(body.get("ends") or (now + days * rewards.DAY)),
            "window_hours": float(body.get("window_hours") or 48),
            "link": str(body.get("link") or "")[:200],
            "kind": kind, "params": params, "featured": bool(body.get("featured")),
            "icon": self._clean_icon(body.get("icon")),
        }
        if not row["app"] or not row["what"]:
            raise ValueError("an offer needs an app and a line saying what to do")
        self.store.put_offer(row)
        log.info("offer %s: %s — %s SKR, budget %s", offer_id, row["what"], row["reward"], row["budget"])
        return {"ok": True, "offer": row}

    @staticmethod
    def _clean_icon(raw) -> str | None:
        """The app's logo, as an https address, or nothing. The phone fetches whatever is stored
        here, so only a plain https URL of sensible length is kept — never a scheme that reads a
        local file or runs script, and never something enormous."""
        v = str(raw or "").strip()
        if not v or len(v) > 300 or not v.startswith("https://") or any(c in v for c in " \n\r\t\"'<>"):
            return None
        return v

    @staticmethod
    def _clean_params(raw, kind: str) -> dict:
        """Only the settings each kind understands, each checked. An unknown key is dropped rather
        than stored: an offer's rules should be exactly what was reviewed."""
        raw = raw if isinstance(raw, dict) else {}
        out: dict = {}
        if "min_usd" in raw:
            v = float(raw["min_usd"])
            if not 0 <= v <= 10_000:
                raise ValueError("min_usd out of range")
            out["min_usd"] = v
        if kind == "swap":
            # One program, or a short list when the same result can be reached in more than one
            # place. A single program is stored as plain text, which is what older rows hold.
            given = raw.get("program")
            if given is None or given == "":
                given = swaps.JUPITER_V6
            progs = list(given) if isinstance(given, (list, tuple)) else [given]
            if not 1 <= len(progs) <= 4 or not all(verify.is_address(x) for x in progs):
                raise ValueError("invalid program")
            out["program"] = progs[0] if len(progs) == 1 else progs
            # Which token has to come OUT of the swap, for an offer about swapping into it:
            # "USDC", or the mint itself for anything else.
            if raw.get("receive") is not None:
                if raw["receive"] != "USDC" and not verify.is_address(raw["receive"]):
                    raise ValueError("receive must be USDC or a mint address")
                out["receive"] = raw["receive"]
            # A floor in SOL rather than dollars, for an offer that is about SOL going in. It
            # needs no price feed, so a claim never waits on one.
            if raw.get("min_sol") is not None:
                v = raw["min_sol"]
                if isinstance(v, bool) or not isinstance(v, (int, float)) or not 0 < v <= 1000:
                    raise ValueError("min_sol out of range")
                out["min_sol"] = float(v)
        if kind == "shop":
            scope = str(raw.get("scope") or "any")
            if scope not in ("any", "recharge", "gift"):
                raise ValueError("scope must be any, recharge or gift")
            out["scope"] = scope
        # How many purchases, or how many different people.
        if kind in ("shop", "people") and raw.get("count") is not None:
            n = raw["count"]
            if isinstance(n, bool) or not isinstance(n, int) or not 1 <= n <= 20:
                raise ValueError("count must be a whole number from 1 to 20")
            out["count"] = n
        if kind == "stake":
            # A stake is measured in SKR, not dollars, so a dollar floor would mean nothing here.
            out.pop("min_usd", None)
            n = raw.get("min_skr", 100)
            if isinstance(n, bool) or not isinstance(n, int) or not 1 <= n <= 1_000_000:
                raise ValueError("min_skr must be a whole number of SKR")
            out["min_skr"] = n
        # A scratch: the least a card can pay. Never zero, because a card that pays nothing after
        # somebody did the thing is a refusal dressed up as a prize.
        if raw.get("reward_min") is not None:
            n = raw["reward_min"]
            if isinstance(n, bool) or not isinstance(n, int) or not 1 <= n <= offers.MAX_REWARD:
                raise ValueError("reward_min must be a whole number of SKR, at least 1")
            out["reward_min"] = n
        # The offer's own wording and button. Text that lands on somebody's screen: short,
        # printable, and never an em dash.
        if raw.get("steps") is not None:
            steps = raw["steps"]
            if not isinstance(steps, list) or not 2 <= len(steps) <= 3 or not all(
                    isinstance(x, str) and 0 < len(x.strip()) <= 100 and x.isprintable() for x in steps):
                raise ValueError("steps must be 2 or 3 short lines")
            out["steps"] = [x.strip() for x in steps]
        # The descriptive page: what the offer is, where it is done, how, and what it takes.
        # Every line of it is optional, because offers.py writes a default for whatever is left out.
        for key, most in (("about", 280), ("where", 60), ("payout", 200)):
            if raw.get(key) is not None:
                out[key] = _line(raw[key], most, key)
        if raw.get("guide") is not None:
            guide = raw["guide"]
            if not isinstance(guide, list) or not 3 <= len(guide) <= 5 or not all(
                    isinstance(g, dict) for g in guide):
                raise ValueError("guide must be 3 to 5 steps")
            out["guide"] = [{"title": _line(g.get("title"), 40, "guide title"),
                             "detail": _line(g.get("detail"), 260, "guide detail")} for g in guide]
        if raw.get("needs") is not None:
            needs = raw["needs"]
            if not isinstance(needs, list) or not 1 <= len(needs) <= 4:
                raise ValueError("needs must be 1 to 4 short lines")
            out["needs"] = [_line(x, 60, "needs") for x in needs]
        # Where it sits on the page, lowest first, so one company's offers need not sit together.
        if raw.get("order") is not None:
            n = raw["order"]
            if isinstance(n, bool) or not isinstance(n, int) or not 0 <= n <= 9999:
                raise ValueError("order must be a whole number from 0 to 9999")
            out["order"] = n
        if raw.get("cta") is not None:
            if raw["cta"] not in offers.CTA_TARGETS:
                raise ValueError("cta must be one of " + ", ".join(offers.CTA_TARGETS))
            out["cta"] = raw["cta"]
        if raw.get("cta_label") is not None:
            label = str(raw["cta_label"]).strip()
            if not 0 < len(label) <= 24 or not label.isprintable():
                raise ValueError("cta label must be 1 to 24 characters")
            out["cta_label"] = label
        # What the app shows and checks on the phone; never used to decide a payout.
        pkg = raw.get("package")
        if pkg is not None:
            if not re.fullmatch(r"[A-Za-z0-9_.]{3,100}", str(pkg)):
                raise ValueError("invalid package")
            out["package"] = str(pkg)
        url = raw.get("store_url")
        if url is not None:
            if not str(url).startswith("https://") or len(str(url)) > 300:
                raise ValueError("store_url must be https")
            out["store_url"] = str(url)
        return out

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
            # What this claim pays if it is satisfied: the fixed reward, or the card drawn for it.
            reward = self._reward_of(offer, claim)
            try:
                if offer.kind == "pay":
                    payments = self._payments_from(c["address"], offer, claim.claimed_at)
                    d = offers.settle(offer, claim, payments, now, self.store.offer_spent(offer.id),
                                      reward=reward)
                else:
                    seen = self._seen_for(c["address"], offer, claim.claimed_at)
                    d = offers.settle_seen(offer, claim, seen, now, self.store.offer_spent(offer.id),
                                           reward=reward)
            except (chain.Offline, chain.RpcError) as e:
                log.info("offer check for %s… deferred: %s", c["address"][:8], e)
                continue
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
                self._deliver(c["address"], {"type": "offer", "state": "expired", "offer": offer.id,
                                             "app": offer.app})
            else:
                self.store.touch_claim(offer.id, c["address"], now, d.refused)
        return settled

    def _reward_of(self, offer: offers.Offer, claim: offers.Claim) -> int:
        """What this claim pays. For a scratch, the card is drawn from the claim itself and a
        secret only this server holds, so it is the same every time it is worked out: checking a
        claim twice, or restarting, can never deal a second card, and nobody outside can work out
        which claim gets the big one."""
        if not offer.variable:
            return offer.reward
        claim_id = f"{offer.id}|{claim.address}|{claim.claimed_at!r}".encode()
        digest = hmac.new(self.store.secret("scratch"), claim_id, hashlib.sha256).digest()
        return offers.draw(offer.reward_min, offer.reward, digest)

    def _seen_for(self, address: str, offer: offers.Offer, since: float) -> list[offers.Seen]:
        """What the claimer did that this kind of offer cares about, since they opened it."""
        if offer.kind == "swap":
            return self._swaps_seen(address, offer, since)
        if offer.kind == "shop":
            return self._purchases_seen(address, offer, since)
        if offer.kind == "people":
            return self._paid_people_seen(address, since, tap_only=False)
        if offer.kind == "tap":
            return self._paid_people_seen(address, since, tap_only=True)
        if offer.kind == "stake":
            return self._stakes_seen(address, offer, since)
        return []

    def _paid_people_seen(self, address: str, since: float, tap_only: bool) -> list[offers.Seen]:
        """Payments this wallet made to other people since it opened the offer, from the server's
        own record of settled requests.

        The same counterparty rules as the tap rewards, for the same reason: a wallet costs
        nothing to make. Paying another of your own wallets is paying yourself, and a wallet made
        last week earns the payer nothing unless it is a Seeker. Two wallets of one person share a
        `key`, so they count as one person however many they have linked.
        """
        now = time.time()
        own = set(self.store.family(address))
        min_age = self.reward_config().counterparty_min_age_days * rewards.DAY
        sol = self.prices.sol_usd()
        out: list[offers.Seen] = []
        for r in self.store.paid_by(address, since):
            to = r["recipient"]
            refused = None
            if tap_only and (r.get("origin") or "") != "tap":
                refused = rewards.NOT_A_TAP
            elif to in own:
                refused = rewards.SELF_PAYMENT
            elif not (self.facts and self.facts.is_seeker(to)):
                first = self.facts.first_seen(to) if self.facts else None
                if first is None or now - first < min_age:
                    refused = rewards.NEW_COUNTERPARTY
            token = r.get("token") or verify.SOL
            if token == verify.SOL:
                usd = r["lamports"] / 1e9 * sol if sol else None
            elif token in DOLLAR_TOKENS:
                usd = r["lamports"] / 10 ** int(r.get("decimals") or 6)
            else:
                usd = None          # a token nobody prices: counts only where there is no floor
            out.append(offers.Seen(r.get("signature") or r["reference"], float(r["paid_at"]), usd, refused,
                                   key=self.store.account_key(to)))
        return out

    def _stakes_seen(self, address: str, offer: offers.Offer, since: float) -> list[offers.Seen]:
        """SKR stakes this wallet made since it opened the offer, read from its own transactions."""
        need = int(offer.params.get("min_skr") or 100) * stakes.SKR_UNITS
        out: list[offers.Seen] = []
        for entry in self.rpc.signatures_with_time(address):
            at = entry.get("blockTime")
            if at is not None and at < since:
                continue
            tx = self.rpc.transaction(entry["signature"])
            units = stakes.staked(tx, address)
            if units is None:
                continue
            # Valued in SKR, not dollars: the floor is applied here and the dollar value is nil.
            out.append(offers.Seen(entry["signature"], float(at or (tx or {}).get("blockTime") or since), 0.0,
                                   None if units >= need else offers.BELOW_MINIMUM))
        return out

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

    def _swaps_seen(self, address: str, offer: offers.Offer, since: float) -> list[offers.Seen]:
        """Swaps this wallet made since it opened the offer, read from its own transactions."""
        program = swaps.programs_of(offer.params.get("program"))
        want = swaps.receive_mint(offer.params.get("receive"))
        min_lamports = int(round(float(offer.params.get("min_sol") or 0) * 1e9))
        sol = self.prices.sol_usd()
        out: list[offers.Seen] = []
        for entry in self.rpc.signatures_with_time(address):
            at = entry.get("blockTime")
            if at is not None and at < since:
                continue
            tx = self.rpc.transaction(entry["signature"])
            sw = swaps.swap_of(tx, address, program)
            if sw is None:
                continue
            refused = swaps.refusal(sw)
            # An offer about swapping INTO something: the right swap of the wrong token is not it.
            if refused is None and want and sw.bought != want:
                refused = offers.WRONG_TOKEN
            # A floor in SOL is measured on the SOL that left, so the swap has to be paid in SOL.
            if refused is None and min_lamports:
                if sw.sold != swaps.WSOL:
                    refused = offers.WRONG_TOKEN
                elif sw.sold_units < min_lamports:
                    refused = offers.BELOW_MINIMUM
            out.append(offers.Seen(entry["signature"], float(at or (tx or {}).get("blockTime") or since),
                                   swaps.usd_value(sw, sol), refused))
        return out

    def _purchases_seen(self, address: str, offer: offers.Offer, since: float) -> list[offers.Seen]:
        """Purchases in the AirPay shop this wallet made AND received since it opened the offer."""
        scope = str(offer.params.get("scope") or "any")
        out = []
        for p in self.store.delivered_purchases(address, since):
            if scope != "any" and p.get("category") != scope:
                continue
            # The dollar value recorded at invoice time, whatever it was paid in. A row from before
            # that was recorded only knows its USDC amount.
            usd = p["usd"] if p.get("usd") is not None else ((p["amount"] / 1e6) if p.get("amount") else None)
            out.append(offers.Seen(p["invoice_id"], float(p["delivered_at"] or p["created_at"]), usd))
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
            owners = self.store.reward_owners(rev.reference)
            n = self.store.reverse_rewards(rev.reference, f"{rev.reason}: {rev.detail}")
            if n:
                for who, skr in owners.items():
                    # Told on the same screen where the reward appeared, with the reason: someone
                    # who saw SKR and later sees none is owed an explanation, not a silent change.
                    self._deliver(who, {"type": "reward", "state": "reversed", "skr": skr,
                                        "reference": rev.reference, "reason": rev.reason})
                reversed_count += n
                log.warning("reversed %d reward(s) for %s…: %s", n, rev.reference[:8], rev.detail)
        # Settling last, so a reward found suspicious in this same pass is reversed before its
        # window closes rather than a second later.
        maturing = self.store.matured_by_payer(now)
        settled = self.store.settle_matured(now)
        if settled:
            log.info("%d reward(s) settled", settled)
            for who, skr in maturing.items():
                self._deliver(who, {"type": "reward", "state": "settled", "skr": skr})
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
            "payouts": self.store.payouts_for(address, 10),
            "min_payout": config.min_payout,
            "payouts_enabled": self.payer.enabled,
            # Sent alongside the earnings on purpose: a screen that only shows what was paid
            # makes the rules look arbitrary the first time one refuses.
            "refused": self.store.refusals_for(address, 10),
            "rates": {"first_payment": config.first_payment, "per_payment": config.per_payment,
                      "streak": config.streak, "streak_needed": config.streak_needed,
                      "lock_days": config.lock_days},
        }

    # -- paying out -------------------------------------------------------------------------

    #: SKR has 6 decimals. The ledger counts whole SKR; the chain counts millionths.
    SKR_UNITS = 10 ** 6
    #: The payer's deliberate refusals. Anything else it says is not a verdict on the claim.
    PAYER_SAID_NO = {"over_payout_cap", "over_daily_cap", "over_recipient_cap", "paused", "bad_destination",
                     "bad_amount", "self_payment", "bad_id"}

    def claim_rewards(self, address: str) -> dict:
        """Pay out everything this wallet has settled and not yet been paid.

        Only SETTLED rewards are claimable — past the 7-day window in which the patrol can still
        reverse them — so SKR never leaves for a reward that is later taken back. The rewards are
        reserved against one payout row in a single transaction, and the payout id is the payer's
        idempotency key, so pressing Claim twice, or the server crashing halfway, can never pay a
        reward twice.
        """
        if not verify.is_address(address):
            raise ValueError("invalid address")
        if not self.payer.enabled:
            return {"ok": False, "refused": "payouts_unavailable"}
        now = time.time()
        config = self.reward_config()
        got = self.store.begin_payout(address, config.min_payout, now, "pay-" + secrets.token_hex(12))
        if "existing" in got:
            self._drive_payout(got["existing"]["id"])
            return {"ok": True, **self._payout_view(got["existing"]["id"])}
        if "below" in got:
            return {"ok": False, "refused": "below_minimum", "have": got["below"], "need": config.min_payout}
        pid = got["payout"]["id"]
        self._drive_payout(pid)
        return {"ok": True, **self._payout_view(pid)}

    def _payout_view(self, pid: str) -> dict:
        p = self.store.payout(pid) or {}
        return {"payout": {"id": pid, "skr": p.get("skr"), "state": p.get("state"),
                           "signature": p.get("signature"), "error": p.get("error")}}

    def _drive_payout(self, pid: str) -> None:
        """Move one payout along. Safe to call any number of times, from any thread."""
        p = self.store.payout(pid)
        if p is None or p["state"] not in ("created", "sent"):
            return
        now = time.time()
        try:
            out = self.payer.pay(pid, p["address"], p["skr"] * self.SKR_UNITS)
        except PayerError as e:
            if e.retryable:
                self.store.set_payout(pid, p["state"], now, error=f"retry: {e.code}")
                return                       # try again next pass; nothing is lost
            if e.code not in self.PAYER_SAID_NO:
                # `denied` means our secret is wrong; `id_reused` means the payer remembers this id
                # as a DIFFERENT payout. Neither is a "no" about this claim, so the rewards stay
                # reserved (never released, never paid twice) and a person has to look.
                log.error("payout %s stuck: payer answered %s — needs attention", pid, e.code)
                self.store.set_payout(pid, p["state"], now, error=f"attention: {e.code}")
                return
            # The payer said NO on purpose (a cap, a pause, a bad id): it will not happen, so the
            # rewards go back to claimable and the person can try again later.
            n = self.store.release_payout(pid, e.code, now)
            log.warning("payout %s refused by payer (%s); %d rewards released", pid, e.code, n)
            return
        state = out.get("state")
        if state == "confirmed":
            n = self.store.finish_payout(pid, out.get("signature"), now)
            log.info("payout %s paid %s SKR to %s… (%d rewards)", pid, p["skr"], p["address"][:8], n)
            self._deliver(p["address"], {"type": "payout", "skr": p["skr"], "state": "confirmed",
                                         "signature": out.get("signature") or ""})
        elif state == "failed":
            # Failed in the payer means no money moved (no balance, rejected, or an on-chain error),
            # so releasing is safe: the same rewards can be claimed again under a new payout.
            n = self.store.release_payout(pid, out.get("error") or "failed", now)
            log.warning("payout %s failed (%s); %d rewards released", pid, out.get("error"), n)
        else:
            first = p["state"] != "sent"
            self.store.set_payout(pid, "sent", now, signature=out.get("signature"))
            if first:
                self._deliver(p["address"], {"type": "payout", "skr": p["skr"], "state": "sent",
                                             "signature": out.get("signature") or ""})

    def settle_payouts(self) -> int:
        """Watcher pass: push every unfinished payout forward. This is what turns a claim made while
        the payer was down, or a transaction still confirming, into a finished payout."""
        done = 0
        for p in self.store.open_payouts():
            self._drive_payout(p["id"])
            done += 1
        return done

    def reveal_reward(self, address: str, reward_id: int) -> dict:
        """Uncovers one reward card.

        Deliberately **not** where the amount is decided. The reward was evaluated, credited
        and counted against the weekly pool when the payment settled; this records that
        somebody has now looked at it. Deciding it here instead would make the payout depend
        on opening a screen, which is a lottery, and the whole defence this layer has against
        farming is that every reward is a fixed consequence of a rule.
        """
        at = self.store.reveal_reward(address, reward_id, time.time())
        if at is None:
            return {"ok": False, "refused": "unknown_reward"}
        if at < 0:
            return {"ok": False, "refused": "expired"}
        # Paid the moment it is scratched. Off this request's thread: the payer waits on the
        # chain, and the screen should not.
        self._pool.submit(self._safe, self.pay_revealed, address)
        return {"ok": True, "revealed_at": at}

    def pay_revealed(self, address: str | None = None) -> int:
        """Pay every scratched card that is still owed, with no lock and no minimum.

        The payer's own caps (per payout, per day, per recipient) are the limit now, and a payout
        keeps its id as an idempotency key, so a card is never paid twice. Returns how many
        accounts were paid out.
        """
        if not self.payer.enabled:
            return 0
        done = 0
        for addr in ([address] if address else self.store.revealed_unpaid()):
            self.store.settle_revealed(addr)
            out = self.claim_rewards(addr)
            if out.get("ok"):
                done += 1
        return done

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

    #: How long after somebody signs up the FIRST offer is told to them, so they have time to
    #: look around the app before anything is pitched. Then one more every [OFFER_GAP].
    OFFER_FIRST_DELAY = 3 * 3600.0
    OFFER_GAP = 6 * 3600.0
    #: With nothing new to tell, how long before looking again (an offer may have been added).
    OFFER_RECHECK = 3600.0
    #: Not a Seeker (offers are for Seekers), or the chain could not be asked: try again later.
    OFFER_NOT_SEEKER_RETRY = 24 * 3600.0
    OFFER_CHAIN_RETRY = 15 * 60.0
    NOTICE_EVERY = 60.0

    def settle_shop(self, shop, invoice_id: str) -> None:
        """What to do when Bitrefill says a purchase finished.

        Called from the webhook, which is unsigned — so nothing here reads the POST body. The
        invoice is fetched with our own key and that answer is the only one trusted.

        Idempotent, because Bitrefill retries: a repeat settles to the same row, reports no
        change, and nobody is told twice about one delivery.
        """
        try:
            out = shop.order(invoice_id)
        except Exception as e:                                   # noqa: BLE001
            log.info("shop webhook %s: %s", invoice_id, e)
            return
        status = out.get("status") or ""
        code = next((o.get("redemption") for o in out.get("orders") or []
                     if o.get("redemption")), None)
        if isinstance(code, (dict, list)):
            code = json.dumps(code)
        buyer, changed = self.store.settle_purchase(invoice_id, status, code)
        if not changed or not buyer:
            return
        # Same road as every other notification: the live stream when the app is open, push
        # when it is not — which is the usual case here, since delivery lands after the buyer
        # has put the phone away.
        self._deliver(buyer, {
            "type": "shop",
            "invoice": invoice_id,
            "status": status,
            "code": code,
            "at": time.time(),
        })

    #: How often each registered wallet is looked at for movement.
    ACTIVITY_EVERY = 6.0
    #: Signatures remembered per address. Far more than one look returns, so a lagging node's
    #: older list is always inside it.
    ACTIVITY_REMEMBER = 500
    #: Allowance for this machine's clock and the chain's disagreeing, in seconds.
    ACTIVITY_SKEW = 30.0

    def watch_wallets(self) -> int:
        """Tell each registered wallet about money that moved in it. Returns how many were told.

        Every signature ever seen for an address is remembered, and only one never seen before is
        reported. The first look at an address records what is there and reports nothing, so a
        wallet is never greeted with its history.

        It used to keep a single "newest signature" per address and report whatever sat above it.
        The RPC provider answers from several nodes, and one that is a little behind returns a
        list WITHOUT the newest transaction. The cursor then pointed at something not in the
        list, the code fell back to announcing the top three, the cursor moved backwards, and
        the next answer from a caught-up node announced the newest one again: the same payments,
        every few seconds, for ever. A set of seen signatures cannot move backwards.

        A transfer that settled an AirPay request already has its own "paid" notice and is
        skipped here, so the recipient is told once.
        """
        told = 0
        for addr in self.store.device_recipients():
            try:
                sigs = self.rpc.call("getSignaturesForAddress", [addr, {"limit": 10, "commitment": "confirmed"}]) or []
            except Exception:                                    # noqa: BLE001
                continue
            rows = [x for x in sigs if isinstance(x, dict) and x.get("err") is None and x.get("signature")]
            ok = [x["signature"] for x in rows]
            seen = self._activity_seen.get(addr)
            if seen is None:
                self._activity_seen[addr] = dict.fromkeys(ok)
                self._activity_since[addr] = time.time()
                continue
            # Unseen is not enough on its own. A node that is behind returns an OLDER window of
            # ten, whose last entry is a transaction from before the first look that was never in
            # any earlier answer. So a signature must also have happened after watching began.
            since = self._activity_since.get(addr, time.time()) - self.ACTIVITY_SKEW
            new = []
            for x in rows:
                sig = x["signature"]
                if sig in seen:
                    continue
                when = x.get("blockTime")
                if isinstance(when, (int, float)) and when >= since:
                    new.append(sig)
                else:
                    seen[sig] = None          # old, or undated: remembered, never announced
            for sig in reversed(new):            # oldest first, as they happened
                seen[sig] = None
                if self.store.signature_settled(sig):
                    continue
                try:
                    tx = self.rpc.transaction(sig)
                except Exception:                                # noqa: BLE001
                    continue
                for change in activity.describe(tx, addr) if tx else []:
                    self._deliver(addr, {"type": "wallet", "signature": sig, **change})
                    told += 1
            while len(seen) > self.ACTIVITY_REMEMBER:
                seen.pop(next(iter(seen)))
        return told


    # -- .air names: an AirPay handle, looked up here rather than on a chain -------------------

    HANDLE_RE = re.compile(r"^[a-z0-9_]{3,20}$")
    RESERVED = {"airpay", "admin", "support", "help", "root", "team", "official", "skr", "solana", "seeker"}

    @classmethod
    def clean_handle(cls, raw) -> str:
        name = str(raw or "").strip().lower().removesuffix(".air")
        if not cls.HANDLE_RE.match(name) or name in cls.RESERVED:
            raise ValueError("invalid name")
        return name

    def claim_handle(self, address: str, raw) -> dict:
        if not verify.is_address(address):
            raise ValueError("invalid address")
        name = self.clean_handle(raw)
        got = self.store.claim_handle(name, address, time.time())
        return {"ok": got != "taken", "name": name + ".air", "refused": "taken" if got == "taken" else None}

    def resolve_handles(self, text: str) -> list[dict]:
        """Names starting with `text`, so a person can be found while their name is still being typed."""
        t = str(text or "").strip().lower().removesuffix(".air")
        if not re.match(r"^[a-z0-9_]{1,20}$", t):
            return []
        return [{"name": h["name"] + ".air", "address": h["address"]} for h in self.store.handles_like(t, 5)]

    def run_watcher(self, stop: threading.Event, every: float = 1.0) -> None:
        while not stop.is_set():
            self._safe(self.sweep)
            self._safe(self.check_claims)
            self._safe(self.settle_payouts)
            if time.time() - self._patrolled > self.PATROL_EVERY:
                self._patrolled = time.time()
                self._safe(self.patrol)
            if time.time() - self._paid_at > 5.0:
                self._paid_at = time.time()
                self._safe(self.pay_revealed)
            if time.time() - self._looked > self.ACTIVITY_EVERY:
                self._looked = time.time()
                self._safe(self.watch_wallets)
            if time.time() - self._noticed > self.NOTICE_EVERY:
                self._noticed = time.time()
                self._safe(self.send_offer_notices)
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


def _line(value, most: int, name: str) -> str:
    """One piece of text that lands on somebody's screen: present, short, on one line, and with
    no em dash, which the app's wording never uses."""
    text = value.strip() if isinstance(value, str) else ""
    if not 0 < len(text) <= most or not text.isprintable() or "\u2014" in text:
        raise ValueError(f"{name} must be 1 to {most} plain characters")
    return text


def _offer(row: dict) -> offers.Offer:
    """A stored row as the rules want it."""
    return offers.Offer(
        id=row["id"], funder=row["funder"], app=row["app"], what=row["what"],
        address=row["address"], token=row["token"], minimum=row["minimum"],
        reward=row["reward"], budget=row["budget"], starts=row["starts"], ends=row["ends"],
        window_hours=row["window_hours"], link=row["link"],
        kind=row.get("kind") or "pay", params=_params(row.get("params")),
    )


def _params(raw) -> dict:
    if isinstance(raw, dict):
        return raw
    try:
        v = json.loads(raw or "{}")
    except (TypeError, ValueError):
        return {}
    return v if isinstance(v, dict) else {}
