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

log = logging.getLogger("tap.service")


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
                 payer: str | None = None, genesis: str | None = None) -> dict:
        if not verify.is_address(reference):
            raise ValueError("invalid reference")
        if not verify.is_address(recipient):
            raise ValueError("invalid recipient")
        if payer is not None and (not verify.is_address(payer) or payer == recipient):
            raise ValueError("invalid payer")
        if genesis is not None and not verify.is_address(genesis):
            raise ValueError("invalid genesis")
        want = verify.lamports(amount)
        if want is None:
            raise ValueError("invalid amount")
        label = str(label or "")[:64]
        result = self.store.register(reference, recipient, str(amount).strip(), want, label, payer, genesis)
        if result.get("created"):
            if self.helius:
                self.helius.touch()
            # A request addressed to someone: the phones have parted, so the server carries it.
            if payer:
                self._deliver(payer, {"type": "request", "reference": reference, "recipient": recipient,
                                      "amount": str(amount).strip(), "label": label, "genesis": genesis or ""})
        return result

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
            v = verify.verify_transfer(tx, req["reference"], req["recipient"], req["lamports"])
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
            kind, sig, why = chain.find_payment(self.rpc, reference, req["recipient"], req["lamports"])
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
        event = {"type": "paid", "reference": req["reference"], "signature": signature, "amount": req["amount"]}
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
