"""
Two doorbells: a live SSE stream for a payee with the app open, and FCM for one with it
closed. Neither is the judge — the app re-checks the chain before showing ✓ Paid — so a
duplicate or spurious ring costs a lookup, never money.
"""
from __future__ import annotations

import json
import logging
import queue
import threading
import urllib.error
import urllib.request

log = logging.getLogger("tap.notify")


class Hub:
    """Fan-out of paid events to SSE subscribers, keyed by recipient address."""

    MAX_PER_RECIPIENT = 8

    def __init__(self):
        self._subs: dict[str, list[queue.Queue]] = {}
        self._lock = threading.Lock()

    def subscribe(self, recipient: str) -> queue.Queue | None:
        q: queue.Queue = queue.Queue(maxsize=100)
        with self._lock:
            subs = self._subs.setdefault(recipient, [])
            if len(subs) >= self.MAX_PER_RECIPIENT:
                return None
            subs.append(q)
        return q

    def unsubscribe(self, recipient: str, q: queue.Queue) -> None:
        with self._lock:
            subs = self._subs.get(recipient, [])
            if q in subs:
                subs.remove(q)
            if not subs:
                self._subs.pop(recipient, None)

    def publish(self, recipient: str, event: dict) -> int:
        with self._lock:
            subs = list(self._subs.get(recipient, []))
        for q in subs:
            try:
                q.put_nowait(event)
            except queue.Full:
                pass  # a stalled client loses a doorbell, not a payment
        return len(subs)

    def count(self) -> int:
        with self._lock:
            return sum(len(v) for v in self._subs.values())


class Fcm:
    """FCM HTTP v1. Disabled unless a project id and service-account credentials are set and
    google-auth is installed; the server runs fine without it."""

    SCOPE = "https://www.googleapis.com/auth/firebase.messaging"

    def __init__(self, project_id: str | None, credentials_path: str | None):
        self.project_id = project_id
        self._creds = None
        self._lock = threading.Lock()
        if not (project_id and credentials_path):
            return
        try:
            from google.oauth2 import service_account  # type: ignore

            self._creds = service_account.Credentials.from_service_account_file(credentials_path, scopes=[self.SCOPE])
        except Exception as e:  # missing package or unreadable file: push is off, not broken
            log.warning("push disabled: %s", e)
            self._creds = None

    @property
    def enabled(self) -> bool:
        return self._creds is not None

    def _bearer(self) -> str:
        from google.auth.transport.requests import Request  # type: ignore

        with self._lock:
            if not self._creds.valid:
                self._creds.refresh(Request())
            return self._creds.token

    def send(self, token: str, data: dict) -> str:
        """Returns 'ok', 'gone' (token should be dropped) or 'error'."""
        body = json.dumps(
            {"message": {"token": token, "data": {k: str(v) for k, v in data.items()}, "android": {"priority": "high"}}}
        ).encode()
        req = urllib.request.Request(
            f"https://fcm.googleapis.com/v1/projects/{self.project_id}/messages:send",
            body,
            {"Content-Type": "application/json", "Authorization": f"Bearer {self._bearer()}"},
        )
        try:
            urllib.request.urlopen(req, timeout=10).read()
            return "ok"
        except urllib.error.HTTPError as e:
            # 404 UNREGISTERED / 400 INVALID_ARGUMENT on a token means the app was uninstalled
            # or the token rotated; keeping it just repeats the failure on every payment.
            return "gone" if e.code in (400, 404) else "error"
        except Exception:
            return "error"
