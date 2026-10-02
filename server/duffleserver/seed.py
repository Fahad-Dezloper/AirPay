"""python -m duffleserver.seed — put the launch offers in the database. Safe to run again: an offer
with the same id is replaced, never duplicated, and nothing else is touched.

`funder` stays the lowercase data value "duffle": the app compares against that exact string to
print "from AirPay". It is a row, not our name.
"""
from __future__ import annotations

import os
import time

from . import swaps
from .service import Service
from .store import Store

DAY = 86_400.0


def launch_offers(now: float, days: float = 30.0) -> list[dict]:
    end = now + days * DAY
    return [
        {"id": "jupiter-first-swap", "app": "Jupiter", "kind": "swap", "featured": True,
         "what": "Swap $5 or more on Jupiter", "reward": 300, "budget": 6000, "window_hours": 48,
         "params": {"min_usd": 5, "program": swaps.JUPITER_V6, "package": "ag.jup.jupiter.android"},
         "link": "https://jup.ag", "icon": "https://static.jup.ag/jup/icon.png",
         "starts": now, "ends": end},
        {"id": "airpay-gift-card", "app": "AirPay", "kind": "shop",
         "what": "Buy a gift card or Hotstar code in AirPay", "reward": 100, "budget": 3000, "window_hours": 72,
         "params": {"scope": "gift", "min_usd": 0.5}, "starts": now, "ends": end},
        {"id": "airpay-top-up", "app": "AirPay", "kind": "shop",
         "what": "Top up a phone in AirPay", "reward": 100, "budget": 3000, "window_hours": 72,
         "params": {"scope": "recharge", "min_usd": 0.1}, "starts": now, "ends": end},
    ]


def main() -> None:
    svc = Service(Store(os.environ.get("DUFFLE_DB", "duffle.db")), rpc=None)
    for o in launch_offers(time.time()):
        svc.put_offer(o)
        print("offer", o["id"], "·", o["reward"], "SKR · budget", o["budget"])


if __name__ == "__main__":
    main()
