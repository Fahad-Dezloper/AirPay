"""
The server's actual job, independent of HTTP: know which requests are open, find out when they
are paid, and ring the payee. Everything network-facing is injected so tests run offline.
"""
from __future__ import annotations

import logging
import threading
import time
from collections import OrderedDict
from concurrent.futures import ThreadPoolExecutor

from . import chain, verify
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
                 helius: AddressSync | None = None, workers: int = 4):
        self.store = store
        self.rpc = rpc
        self.hub = hub or Hub()
        self.fcm = fcm or Fcm(None, None)
        self.helius = helius
        self._pool = ThreadPoolExecutor(max_workers=workers, thread_name_prefix="check")
        self._inflight: set[str] = set()
        self._inflight_lock = threading.Lock()
        self._seen_sigs: OrderedDict[str, None] = OrderedDict()
        self._seen_lock = threading.Lock()

    # -- inputs -----------------------------------------------------------------------------

    def register(self, reference: str, recipient: str, amount: str, label: str = "",
                 payer: str | None = None, genesis: str | None = None,
                 token: str = "SOL", decimals: int = 9, symbol: str = "SOL") -> dict:
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
                                     token, decimals, symbol)
        if result.get("created"):
            if self.helius:
                self.helius.touch()
            # A request addressed to someone: the phones have parted, so the server carries it.
            if payer:
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

    def relay_tap(self, to: str, address: str, label: str = "", genesis: str | None = None) -> int:
        """An introduction the radio could not deliver because the phones parted before HELLO."""
        if not verify.is_address(to) or not verify.is_address(address) or to == address:
            raise ValueError("invalid address")
        if genesis is not None and not verify.is_address(genesis):
            raise ValueError("invalid genesis")
        return self._deliver(to, {"type": "tap", "address": address, "label": str(label or "")[:64],
                                  "genesis": genesis or ""})

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

    def run_watcher(self, stop: threading.Event, every: float = 1.0) -> None:
        while not stop.is_set():
            self._safe(self.sweep)
            stop.wait(every)
