"""SOL/USD, from Pyth's public feed, cached. Never a guess: None when it cannot be read."""
from __future__ import annotations

import json
import threading
import time
import urllib.request

SOL_USD_FEED = "0xef0d8b6fda2ceba41da15d4095d1da392a0d2f8ed0c6c7bc0f4cfac8c280b56d"
URL = "https://hermes.pyth.network/v2/updates/price/latest?ids[]=" + SOL_USD_FEED + "&parsed=true"


class Prices:
    def __init__(self, ttl: float = 60.0, fetch=None):
        self.ttl = ttl
        self._fetch = fetch or self._hermes
        self._at, self._val = 0.0, None
        self._lock = threading.Lock()

    @staticmethod
    def _hermes() -> float | None:
        with urllib.request.urlopen(URL, timeout=8) as r:
            p = json.loads(r.read())["parsed"][0]["price"]
        return float(p["price"]) * 10 ** int(p["expo"])

    def sol_usd(self) -> float | None:
        with self._lock:
            if time.time() - self._at < self.ttl:
                return self._val
        try:
            v = self._fetch()
            ok = v is not None and 1.0 < v < 100_000.0     # a feed gone wrong is not a price
        except Exception:                                  # noqa: BLE001
            v, ok = None, False
        with self._lock:
            self._at = time.time() if ok else time.time() - self.ttl + 10   # retry soon on failure
            self._val = v if ok else self._val
            return self._val
