"""
Who earns what, and why not.

One pure function decides every reward: no database, no network, no clock of its own. What it
needs to know about a payment and the payer's history is passed in, so every rule — including
the ones that exist only to stop farming — can be tested on its own.

The amounts live in [Config] and are stated in SKR, never in money. A small reward priced in
cents measurably does worse than no reward at all (Heyman & Ariely 2004; Gneezy & Rustichini
2000), and SKR moved between $0.008 and $0.029 in a single month, so a dollar promise would
break itself. See REWARDS_PLAN.md for where each number came from.
"""
from __future__ import annotations

import math
from dataclasses import dataclass, field, replace

DAY = 86_400.0

# Why a payment earned nothing. Stored with the attempt and shown to us, not invented per call
# site, so the admin view and the app can speak about the same set of reasons.
NOT_A_SEEKER = "not_a_seeker"
NOT_A_TAP = "not_a_tap"
SELF_PAYMENT = "self_payment"
BELOW_MINIMUM = "below_minimum"
UNKNOWN_TOKEN = "unknown_token"
NEW_COUNTERPARTY = "new_counterparty"
COUNTERPARTY_COOLDOWN = "counterparty_cooldown"
WEEKLY_CAP = "weekly_cap"
MONTHLY_CAP = "monthly_cap"
CAMPAIGN_CAP = "campaign_cap"
POOL_EMPTY = "pool_empty"
#: The chain could not be asked. Not a refusal — something to try again, in R2.
CHAIN_UNREACHABLE = "chain_unreachable"
#: We never learned who paid, so there is nobody to pay.
NO_PAYER = "no_payer"

# Why a referral could not be claimed. Same discipline as the refusals above: named once, so
# the app and the admin view talk about the same set.
UNKNOWN_CODE = "unknown_code"
SELF_REFERRAL = "self_referral"
ALREADY_REFERRED = "already_referred"
#: Claimed after the invitee had already earned their first payment. A code is an invitation,
#: not a receipt — letting it be claimed afterwards would pay for people who were already here.
REFERRAL_TOO_LATE = "referral_too_late"

# What a reward is for.
FIRST_PAYMENT = "first_payment"
PER_PAYMENT = "per_payment"
STREAK = "streak"
#: Paid to the *inviter*, when someone they invited makes their first rewarded tap. Never paid
#: on the invitee's say-so alone — a wallet is free, so "I joined" has to cost a real payment
#: before it is worth anything.
REFERRAL = "referral"


@dataclass(frozen=True)
class Config:
    """
    Every number the rules use, in whole SKR. Stored in the database and editable while the
    server runs (`Store.reward_config`, `PUT /v1/rewards/config`) — these are only the defaults
    for a database that has never been told otherwise.

    The defaults are sized for a $200-a-month budget: about 10,400 SKR a month. The monthly
    cap has to be larger than a good week, or the cap silently eats the streak and the
    first-payment bonus — 500 SKR covers a full streak week (400) with room, and spreads the
    budget across roughly 20 users at the cap, more in practice.
    """

    first_payment: int = 250
    per_payment: int = 50
    streak: int = 200
    #: To the inviter, once the person they invited has made a first rewarded payment. Larger
    #: than per_payment because it costs the inviter something real to bring somebody, and
    #: smaller than first_payment because the invitee is the one who had to turn up.
    referral: int = 300
    #: Rewarded payments in one week that complete a streak.
    streak_needed: int = 4
    #: Rewarded payments allowed per week (the streak is the last of them).
    weekly_payments: int = 4
    #: Everything one Seeker can earn in a month — spreads the budget across people.
    monthly_cap: int = 500
    #: Everything one Seeker can earn in a campaign.
    user_cap: int = 2_500
    #: What may be paid out in one week, across everyone. Empty pool, no rewards — the app
    #: says so rather than promising what isn't funded.
    weekly_pool: int = 2_400
    #: The same counterparty only earns you a reward this often.
    cooldown_days: int = 30
    #: Their wallet must have been active at least this long — unless they are a Seeker too.
    counterparty_min_age_days: int = 7
    #: A reward can be reversed while it is locked.
    lock_days: int = 7
    #: Smallest payment that earns, in each token's own units. Roughly $5.
    minimums: dict[str, int] = field(
        default_factory=lambda: {
            "SOL": 50_000_000,  # 0.05 SOL
            "4zMMC9srt5Ri5X14GAgXhaHii3GnPAEERYPJgZJDncDU": 5_000_000,  # devnet USDC, 6dp
            "HzwqbKZw8HxMN6bF2yFZNrht3c2iXXzpKcFu7uBEDKtr": 5_000_000,  # devnet EURC, 6dp
        }
    )

    def minimum_for(self, token: str) -> int | None:
        return self.minimums.get(token)

    #: Names that may be changed at runtime. Anything else is refused, so a typo can't
    #: silently invent a setting nobody reads.
    TUNABLE = ("first_payment", "per_payment", "streak", "streak_needed", "weekly_payments",
               "monthly_cap", "user_cap", "weekly_pool", "cooldown_days",
               "counterparty_min_age_days", "lock_days", "referral")

    def with_overrides(self, values: dict) -> "Config":
        """A copy with whatever the database says on top. Unknown or unusable values are
        ignored, never guessed at — a bad row must not stop rewards working."""
        clean = {}
        for k, v in (values or {}).items():
            if k == "minimums" and isinstance(v, dict):
                clean[k] = {str(t): int(m) for t, m in v.items()}
            elif k in self.TUNABLE:
                try:
                    n = int(v)
                except (TypeError, ValueError):
                    continue
                if n >= 0:
                    clean[k] = n
        return replace(self, **clean) if clean else self


@dataclass(frozen=True)
class Payment:
    """A payment that has settled on-chain."""

    reference: str
    payer: str
    counterparty: str
    #: "SOL" or the mint, as the wire carries it.
    token: str
    #: The amount in the token's smallest unit.
    units: int
    #: How the payment started. Only "tap" earns.
    origin: str = "tap"


@dataclass(frozen=True)
class History:
    """What we know about the payer, the counterparty and what has been paid out already."""

    payer_is_seeker: bool
    #: None when the chain could not be asked — treated as "not a Seeker", never as one.
    counterparty_is_seeker: bool = False
    #: When the counterparty's wallet was first seen on-chain; None if never, or unknown.
    counterparty_first_seen: float | None = None
    #: Rewarded payments by this payer in the current week.
    rewarded_this_week: int = 0
    #: SKR this payer has earned in the campaign.
    earned_in_campaign: int = 0
    #: SKR this payer has earned this month.
    earned_this_month: int = 0
    #: SKR left in this week's pool, across everyone. None means "no pool set" (unlimited).
    pool_left: int | None = None
    #: When this pair last earned, if ever.
    last_reward_with_counterparty: float | None = None
    #: True once the first-payment bonus has been paid.
    had_first_payment: bool = False


@dataclass(frozen=True)
class Award:
    kind: str
    skr: int


@dataclass(frozen=True)
class Decision:
    """What this payment earns. `awards` empty and `refused` set means it earned nothing."""

    awards: tuple[Award, ...] = ()
    refused: str | None = None
    #: When the awards stop being reversible.
    unlock_at: float | None = None

    @property
    def total(self) -> int:
        return sum(a.skr for a in self.awards)

    @property
    def earned(self) -> bool:
        return bool(self.awards)


def evaluate(payment: Payment, history: History, now: float, config: Config | None = None) -> Decision:
    """The whole rule set, in the order the reasons should be reported."""
    c = config or Config()

    # Who is asking, and how.
    if not history.payer_is_seeker:
        return Decision(refused=NOT_A_SEEKER)
    if payment.origin != "tap":
        return Decision(refused=NOT_A_TAP)
    if payment.payer == payment.counterparty:
        return Decision(refused=SELF_PAYMENT)

    # How much.
    minimum = c.minimum_for(payment.token)
    if minimum is None:
        # An unlisted token has no floor we can trust, so it earns nothing rather than
        # everything: 1,000 units of a token nobody prices is not $5.
        return Decision(refused=UNKNOWN_TOKEN)
    if payment.units < minimum:
        return Decision(refused=BELOW_MINIMUM)

    # Who was paid. A wallet costs nothing to make, so a brand-new one earns the payer nothing —
    # otherwise one Seeker with a spare phone taps itself with a fresh wallet every time.
    if not history.counterparty_is_seeker:
        first_seen = history.counterparty_first_seen
        if first_seen is None or now - first_seen < c.counterparty_min_age_days * DAY:
            return Decision(refused=NEW_COUNTERPARTY)

    # The same person, again. Stops two friends paying each other in circles.
    last = history.last_reward_with_counterparty
    if last is not None and now - last < c.cooldown_days * DAY:
        return Decision(refused=COUNTERPARTY_COOLDOWN)

    if history.rewarded_this_week >= c.weekly_payments:
        return Decision(refused=WEEKLY_CAP)

    remaining = c.user_cap - history.earned_in_campaign
    if remaining <= 0:
        return Decision(refused=CAMPAIGN_CAP)
    monthly_left = c.monthly_cap - history.earned_this_month
    if monthly_left <= 0:
        return Decision(refused=MONTHLY_CAP)
    remaining = min(remaining, monthly_left)

    # Only what is actually funded this week can be paid.
    if history.pool_left is not None:
        if history.pool_left <= 0:
            return Decision(refused=POOL_EMPTY)
        remaining = min(remaining, history.pool_left)

    # What it earns. The first payment is the big one; the streak lands on the payment that
    # completes the week.
    wanted: list[Award] = []
    if not history.had_first_payment:
        wanted.append(Award(FIRST_PAYMENT, c.first_payment))
    wanted.append(Award(PER_PAYMENT, c.per_payment))
    if history.rewarded_this_week + 1 >= c.streak_needed:
        wanted.append(Award(STREAK, c.streak))

    # The caps and the pool trim the last awards rather than refusing the payment outright.
    awards: list[Award] = []
    left = remaining
    for a in wanted:
        if left <= 0:
            break
        awards.append(a if a.skr <= left else replace(a, skr=left))
        left -= awards[-1].skr

    return Decision(tuple(awards), None, now + c.lock_days * DAY)


def referral_award(history: History, now: float, config: Config | None = None) -> Decision:
    """What the **inviter** earns when someone they invited makes a first rewarded payment.

    Separate from [evaluate] rather than folded into it, because every award [evaluate] returns
    belongs to the payer, and this one belongs to somebody who was not there. Forcing it into
    that return type is how a reward ends up credited to the wrong wallet.

    It still answers to the same caps. A referral is the easiest reward to manufacture — invite
    a wallet you also control, pay it once — so it is the last one that should be allowed to
    ignore the pool, the campaign cap or the month.
    """
    c = config or Config()
    remaining = c.user_cap - history.earned_in_campaign
    if remaining <= 0:
        return Decision(refused=CAMPAIGN_CAP)
    monthly_left = c.monthly_cap - history.earned_this_month
    if monthly_left <= 0:
        return Decision(refused=MONTHLY_CAP)
    remaining = min(remaining, monthly_left)
    if history.pool_left is not None:
        if history.pool_left <= 0:
            return Decision(refused=POOL_EMPTY)
        remaining = min(remaining, history.pool_left)
    if remaining <= 0:
        return Decision(refused=POOL_EMPTY)
    skr = min(c.referral, remaining)
    return Decision((Award(REFERRAL, skr),), None, now + c.lock_days * DAY)


def week_of(at: float) -> int:
    """The week a payment falls in, counted from the Unix epoch (a Thursday). Used for the
    weekly cap and the streak, so both agree on where a week ends."""
    return math.floor(at / (7 * DAY))
