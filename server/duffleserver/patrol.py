"""
Looking back at rewards that were already paid.

[rewards.evaluate] decides at the moment of payment, knowing only what came before it. Some
farming is invisible at that moment and obvious an hour later: a payment sent straight back,
or a burst of them in a few minutes. That is why a reward stays **locked** for a week — this
module is what uses that week.

Everything here is pure. It takes lists of plain dicts and returns reversals; reading and
writing belong to the caller, so every pattern can be tested with a handful of rows.

A reversal is an accusation, so the bar is deliberately high and each one carries a reason a
person could be told. Rules that fire on honest behaviour are worse than farming: someone
splitting a restaurant bill four ways in five minutes is not a farmer, and a reward layer that
claws back their SKR has done more damage than the SKR was worth.
"""
from __future__ import annotations

from dataclasses import dataclass

MINUTE = 60.0
HOUR = 3600.0

# Why a reward was taken back.
ROUND_TRIP = "round_trip"
BURST = "burst"
UNCORROBORATED_TAP = "uncorroborated_tap"


@dataclass(frozen=True)
class Watch:
    """How suspicious a pattern has to be before anything is reversed."""

    #: A payment sent back the other way inside this window looks like a wash.
    round_trip_hours: float = 6.0
    #: …if the amount back is within this fraction of the amount out. A repayment of a shared
    #: bill is rarely the same number to the cent; a wash usually is.
    round_trip_tolerance: float = 0.1
    #: More than this many rewarded payments inside `burst_minutes` is not a queue of friends.
    burst_count: int = 3
    burst_minutes: float = 10.0


@dataclass(frozen=True)
class Reversal:
    reference: str
    reason: str
    detail: str


def round_trips(rewarded: list[dict], payments: list[dict], now: float,
                watch: Watch | None = None) -> list[Reversal]:
    """Rewards whose payment came straight back.

    `rewarded` are locked rewards, one row per payment (reference, payer, counterparty,
    created_at, units, token). `payments` is every settled payment we know of, in both
    directions. A payment out and a payment back of about the same size, between the same two
    wallets, within a few hours, moved no money — it only moved the reward.
    """
    w = watch or Watch()
    out: list[Reversal] = []
    for r in rewarded:
        back = [
            p for p in payments
            if p["payer"] == r["counterparty"] and p["counterparty"] == r["payer"]
            and p.get("token") == r.get("token")
            and 0 <= p["at"] - r["created_at"] <= w.round_trip_hours * HOUR
            and _close(p["units"], r["units"], w.round_trip_tolerance)
        ]
        if back:
            hours = (back[0]["at"] - r["created_at"]) / HOUR
            out.append(Reversal(r["reference"], ROUND_TRIP,
                                f"the same amount came back {hours:.1f}h later"))
    return out


def bursts(rewarded: list[dict], watch: Watch | None = None) -> list[Reversal]:
    """Rewards earned in a flurry.

    Four rewarded payments in ten minutes is one person working through a list of wallets, not
    four people paying each other. The **earliest** of each flurry is left alone: the first one
    looked like an ordinary payment at the time, and probably was.
    """
    w = watch or Watch()
    by_payer: dict[str, list[dict]] = {}
    for r in rewarded:
        by_payer.setdefault(r["payer"], []).append(r)
    out: list[Reversal] = []
    for rows in by_payer.values():
        rows = sorted(rows, key=lambda r: r["created_at"])
        for i, row in enumerate(rows):
            window = [o for o in rows[:i] if row["created_at"] - o["created_at"] <= w.burst_minutes * MINUTE]
            if len(window) >= w.burst_count:
                out.append(Reversal(row["reference"], BURST,
                                    f"{len(window) + 1} rewarded payments within {w.burst_minutes:.0f} minutes"))
    return out


def uncorroborated(rewarded: list[dict]) -> list[Reversal]:
    """Rewards for a payment only one phone ever mentioned.

    A real tap leaves two registrations of the same reference: the phone that served the
    request, and the phone that received it over the radio and knows who is paying. One
    registration means the claim of a tap rests entirely on a flag the app sent about itself.

    This is **evidence, not proof** — a farmer with two phones produces two registrations too,
    and an honest payer whose phone was offline at the wrong moment produces one. So it is
    reported and, for now, not reversed on its own: see `reversals`.
    """
    return [Reversal(r["reference"], UNCORROBORATED_TAP, "only one phone registered this payment")
            for r in rewarded if not r.get("corroborated")]


def reversals(rewarded: list[dict], payments: list[dict], now: float,
              watch: Watch | None = None) -> list[Reversal]:
    """Everything worth reversing, one per reference, worst reason first.

    An uncorroborated tap on its own is not enough to take money back — too many honest ways to
    produce one. It counts only when a pattern already fired, where it is corroborating
    evidence rather than the accusation.
    """
    found: dict[str, Reversal] = {}
    for rev in round_trips(rewarded, payments, now, watch) + bursts(rewarded, watch):
        found.setdefault(rev.reference, rev)
    weak = {r.reference for r in uncorroborated(rewarded)}
    return [r if r.reference not in weak else Reversal(r.reference, r.reason, r.detail + ", and only one phone registered it")
            for r in found.values()]


def _close(a: int, b: int, tolerance: float) -> bool:
    if a <= 0 or b <= 0:
        return False
    return abs(a - b) <= tolerance * max(a, b)
