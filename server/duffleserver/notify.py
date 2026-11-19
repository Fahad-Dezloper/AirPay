"""
Push (FCM) for a recharge that finishes while the app is closed. The app re-reads the order
from the server when opened, so a duplicate or lost push costs nothing.
"""
from __future__ import annotations

import json
import logging
import threading
import urllib.error
import urllib.request

log = logging.getLogger("duffle.notify")


#: A recharge finishing is the one thing AirPay tells you, and it is news: wake the phone.
URGENT = frozenset({"topup"})

#: How long FCM keeps trying a switched-off phone, per kind, in seconds. Unset: FCM's default.
TTL: dict[str, int] = {}


def android_config(kind: str) -> dict:
    """The `android` block for one message: how urgently, and for how long, FCM delivers it."""
    out = {"priority": "high" if kind in URGENT else "normal"}
    if kind in TTL:
        out["ttl"] = f"{TTL[kind]}s"
    return out


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
            {"message": {"token": token,
                         # A missing value is left out, not sent as the word "None".
                         "data": {k: str(v) for k, v in data.items() if v is not None},
                         "android": android_config(str(data.get("type") or ""))}}
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
