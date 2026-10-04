"""What a mobile plan actually gives you, from the network's own published catalogue.

The shop that sells a recharge describes each plan in one line ("2GB/Day 5G Plan – 84 Days").
The network's own site publishes far more for the same plan: validity, data, voice, SMS, the
subscriptions bundled with it and which shelf it sits on ("Popular", "Data Packs"). This reads
that catalogue so the app can show a plan properly and say what is being bought before it is.

It is used ONLY to describe. What can be bought, and what it costs, still comes from the shop:
a plan here that the shop does not sell is never offered, and a plan the shop sells that is not
found here is simply shown the plain way.

Jio only, for now: it is the one network whose plans the shop lists in full. The address is the
one Jio's own plans page reads. It is not a documented API, so everything here assumes it can
change or disappear: the last good answer is kept, and with none the app gets an empty list.
"""
from __future__ import annotations

import json
import logging
import re
import threading
import time
import urllib.request

log = logging.getLogger("duffle.plans")

JIO_URL = ("https://www.jio.com/api/jio-mdmdata-service/mdmdata/recharge/plans"
           "?productType=MOBILITY&billingType=1")
HEADERS = {
    "User-Agent": "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 Chrome/120 Mobile Safari/537.36",
    "Accept": "application/json",
    "Referer": "https://www.jio.com/selfcare/plans/mobility/prepaid-plans-list/",
}
#: How long one answer is used. Plans change a few times a year, not a few times a day.
TTL = 6 * 3600.0

_TAGS = re.compile(r"<[^>]+>")
#: Where the network serves the small pictures of the apps bundled with a plan.
ICON_BASE = "https://myjiostatic.cdn.jio.com/jiocom/static/images/"
_ICON_NAME = re.compile(r"^[A-Za-z0-9_.-]{1,60}\.svg$")


def _icon(name) -> str | None:
    """A full address for a bundled app's picture, or None for anything that is not a bare file
    name. The name comes off the wire, so it is checked rather than pasted into a URL."""
    n = str(name or "").strip()
    return ICON_BASE + n if _ICON_NAME.match(n) else None


def _text(value, limit: int = 240) -> str:
    """A string off the wire, as plain text: tags gone, whitespace collapsed, length capped.

    A list item becomes a bullet before the tags go, so the app can still tell the items apart
    instead of receiving them run together as one sentence.
    """
    s = re.sub(r"<\s*li[^>]*>", " \u2022 ", str(value or ""), flags=re.I)
    s = _TAGS.sub(" ", s).replace("\u00a0", " ")
    return re.sub(r"\s+", " ", s).strip()[:limit]


def _join(*parts) -> str:
    return _text(" ".join(str(p).strip() for p in parts if p and str(p).strip() not in ("`",)), 60)


def parse_jio(doc: dict) -> list[dict]:
    """The catalogue as flat rows, one per (plan, shelf). Pure, so it is tested on a recorded answer.

    A plan sold on two shelves appears twice, once per shelf, with the same `id`: the app files it
    under both. Anything missing an amount is dropped rather than guessed.
    """
    out: list[dict] = []
    for cat in doc.get("planCategories") or []:
        for sub in cat.get("subCategories") or []:
            for p in sub.get("plans") or []:
                try:
                    amount = int(str(p.get("amount")).strip())
                except (TypeError, ValueError):
                    continue
                prime = p.get("primeData") or {}
                misc = p.get("misc") or {}
                tag = _text(str(prime.get("plan") or "").split("|")[0], 48)
                out.append({
                    "id": str(p.get("id") or ""),
                    "amount": amount,
                    "category": _text(cat.get("type"), 40),
                    "shelf": _text(sub.get("type"), 60),
                    "data": _join(prime.get("offerBenefits1"), prime.get("offerBenefits2")),
                    "validity": _join(prime.get("offerBenefits3"), prime.get("offerBenefits4")),
                    "tag": tag,
                    "description": _text(p.get("description"), 300),
                    "subscriptions": [{"title": _text(s.get("title"), 30), "icon": _icon(s.get("image"))}
                                      for s in misc.get("subscriptions") or []
                                      if isinstance(s, dict) and s.get("title")][:12],
                    "details": [{"label": _text(d.get("header"), 40), "value": _text(d.get("value"), 60)}
                                for d in misc.get("details") or []
                                if isinstance(d, dict) and d.get("header") and d.get("value")][:8],
                    "notes": [n for n in (_text(x, 400) for x in misc.get("notes") or []) if n][:4],
                })
    return out


class PlanCatalogue:
    """The network catalogues, fetched lazily and kept for [TTL]."""

    def __init__(self, fetch=None):
        self._fetch = fetch or self._http
        self._lock = threading.Lock()
        self._rows: dict[str, list[dict]] = {}
        self._at: dict[str, float] = {}

    @staticmethod
    def _http(url: str) -> dict:
        req = urllib.request.Request(url, headers=HEADERS)
        with urllib.request.urlopen(req, timeout=20) as r:
            return json.loads(r.read())

    def plans(self, operator: str, now: float | None = None) -> list[dict]:
        """Plans for `operator` ("jio"). Empty for a network we do not read, or when nothing was
        ever fetched. A failed refresh returns the last good answer rather than nothing."""
        op = str(operator or "").strip().lower()
        if op != "jio":
            return []
        now = now if now is not None else time.time()
        with self._lock:
            # `_at` is when to ask again: TTL after a good answer, ten minutes after a failed one,
            # so a source that is down is not asked on every request.
            if now < self._at.get(op, 0):
                return self._rows.get(op, [])
        try:
            rows = parse_jio(self._fetch(JIO_URL))
        except Exception as e:                                   # noqa: BLE001
            log.info("plan catalogue for %s not refreshed: %s", op, str(e)[:120])
            rows = []
        with self._lock:
            if rows:
                self._rows[op] = rows
            self._at[op] = now + (TTL if rows else 600.0)
            return self._rows.get(op, [])
