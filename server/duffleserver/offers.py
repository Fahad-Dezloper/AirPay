"""
Offers: "pay this app, earn SKR."

Tap rewards pay people for paying each other. An offer pays them for paying someone in
particular — a dApp — and is the only part of this that can eventually be funded by somebody
other than us. Until one is, Duffle funds them from the same weekly pool as tap rewards, so
the campaign cannot cost more than is already budgeted.

The thing being built here is not really the payout. It is the **proof**: a verified Seeker
owner opened an offer and then paid that dApp, with a signature to show for it. That is what a
dApp would pay for later, and it cannot be collected retroactively — hence building the rail
now, with nobody funding it.

Pure, like [rewards]. What is known about the offer, the claim and the payment goes in; a
decision comes out.
"""
from __future__ import annotations

from dataclasses import dataclass

DAY = 86_400.0

# Why a claim earned nothing.
NOT_LIVE = "not_live"
ALREADY_CLAIMED = "already_claimed"
OFFER_BUDGET = "offer_budget"
NOT_A_SEEKER = "not_a_seeker"
WRONG_RECIPIENT = "wrong_recipient"
WRONG_TOKEN = "wrong_token"
BELOW_MINIMUM = "below_minimum"
OUTSIDE_WINDOW = "outside_window"
#: Paying the dApp before opening the offer. Honest, but unprovable as attribution — and
#: attribution is the entire product here.
BEFORE_CLAIM = "before_claim"

#: A claim that has been opened but not yet satisfied.
OPEN = "open"
PAID = "paid"
EXPIRED = "expired"


@dataclass(frozen=True)
class Offer:
    id: str
    #: Who pays for it. "duffle" until a dApp does — the only difference between the two.
    funder: str
    #: The app, as a person would name it.
    app: str
    #: One line: what to do.
    what: str
    #: The address that has to be paid for the offer to count.
    address: str
    #: "SOL" or the mint.
    token: str
    #: The smallest payment that counts, in the token's own units.
    minimum: int
    reward: int
    #: Total SKR this offer may pay out, across everyone.
    budget: int
    starts: float
    ends: float
    #: How long after opening it someone has to pay. Keeps attribution honest and stops a
    #: claim sitting open forever against the budget.
    window_hours: float = 48.0
    link: str = ""

    def live(self, now: float) -> bool:
        return self.starts <= now <= self.ends


@dataclass(frozen=True)
class Claim:
    offer_id: str
    address: str
    claimed_at: float
    state: str = OPEN


@dataclass(frozen=True)
class Payment:
    """A payment seen on-chain from the claimer's wallet."""

    signature: str
    to: str
    token: str
    units: int
    at: float


@dataclass(frozen=True)
class Decision:
    skr: int = 0
    refused: str | None = None
    signature: str | None = None

    @property
    def earned(self) -> bool:
        return self.skr > 0


def can_claim(offer: Offer, now: float, is_seeker: bool, already: Claim | None,
              spent: int) -> str | None:
    """Why this person cannot open this offer, or None if they can."""
    if not offer.live(now):
        return NOT_LIVE
    if not is_seeker:
        return NOT_A_SEEKER
    if already is not None and already.state != EXPIRED:
        return ALREADY_CLAIMED
    if offer.budget and spent + offer.reward > offer.budget:
        return OFFER_BUDGET
    return None


def settle(offer: Offer, claim: Claim, payments: list[Payment], now: float,
           spent: int, pool_left: int | None = None) -> Decision:
    """Does anything this wallet paid satisfy the claim?

    Only payments **after** the claim count. Paying the dApp yesterday and opening the offer
    today is not attribution, and attribution is the product — if the offer did not cause the
    payment, there is nothing to sell a dApp.
    """
    deadline = claim.claimed_at + offer.window_hours * 3600
    if now > deadline and not payments:
        return Decision(refused=OUTSIDE_WINDOW)

    best: str | None = None
    for p in payments:
        if p.to != offer.address:
            best = best or WRONG_RECIPIENT
            continue
        if p.token != offer.token:
            best = WRONG_TOKEN
            continue
        if p.at < claim.claimed_at:
            best = BEFORE_CLAIM
            continue
        if p.at > deadline:
            best = OUTSIDE_WINDOW
            continue
        if p.units < offer.minimum:
            best = BELOW_MINIMUM
            continue
        # It counts. The budget and the pool can still trim it, but never below zero.
        payable = offer.reward
        if offer.budget:
            payable = min(payable, max(0, offer.budget - spent))
        if pool_left is not None:
            payable = min(payable, max(0, pool_left))
        if payable <= 0:
            return Decision(refused=OFFER_BUDGET)
        return Decision(payable, None, p.signature)
    return Decision(refused=best)
