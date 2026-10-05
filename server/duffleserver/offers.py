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

from dataclasses import dataclass, field
from urllib.parse import urlsplit

from . import swaps

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

#: Seen the action, but could not put a dollar value on it. Not a refusal: asked again.
UNPRICED = "unpriced"
STABLE_SWAP = "stable_swap"

KINDS = ("pay", "swap", "shop", "people", "tap", "stake")
#: Where the offer's button takes somebody in the app. "link" opens the offer's own URL.
CTA_TARGETS = ("recharge", "shop", "tap", "pay", "link")
#: The descriptive text of an offer's page. Each may be written by the offer itself; whatever is
#: left out is written here from the offer's own numbers.
COPY_KEYS = ("about", "where", "guide", "payout", "needs")
#: Where an offer that names no place on the page goes: after every offer that does.
LAST = 10_000
#: The payer refuses any single payout above this, so no offer may promise more.
MAX_REWARD = 1000

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
    #: What action earns it. "pay" = paid `address`; "swap" = a Jupiter swap; "shop" = a purchase in
    #: the AirPay shop; "people" = paid several different people; "tap" = paid somebody by tapping
    #: phones; "stake" = staked SKR. Everything the verifier needs beyond that lives in `params`.
    kind: str = "pay"
    params: dict = field(default_factory=dict)

    @property
    def reward_min(self) -> int:
        """The least a scratch can pay, or 0 for a fixed reward. `reward` is then the most."""
        try:
            return max(0, int(self.params.get("reward_min") or 0))
        except (TypeError, ValueError):
            return 0

    @property
    def variable(self) -> bool:
        return 0 < self.reward_min < self.reward

    @property
    def need(self) -> int:
        """How many qualifying things the claim has to collect before it pays."""
        default = 3 if self.kind == "people" else 1
        try:
            return max(1, int(self.params.get("count") or default))
        except (TypeError, ValueError):
            return default

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


def draw(low: int, high: int, digest: bytes) -> int:
    """What a scratch card pays, between `low` and `high`, from 8 bytes of a keyed hash.

    Cubing a number between 0 and 1 pushes it toward 0, so half of all cards land in the bottom
    eighth of the range and about one in ten in the top quarter. The average is a quarter of the
    way up, which is what an offer's budget should be sized against.

    Not `random`: the caller derives `digest` from the claim and a secret only the server holds,
    so the same claim always draws the same card and nobody can predict or re-roll one.
    """
    low, high = max(1, int(low)), max(1, int(high))
    if high <= low:
        return high
    u = int.from_bytes(digest[:8], "big") / 2 ** 64
    return min(high, low + int((high - low + 1) * u ** 3))


def settle(offer: Offer, claim: Claim, payments: list[Payment], now: float,
           spent: int, pool_left: int | None = None, reward: int | None = None) -> Decision:
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
        payable = offer.reward if reward is None else reward
        if offer.budget:
            payable = min(payable, max(0, offer.budget - spent))
        if pool_left is not None:
            payable = min(payable, max(0, pool_left))
        if payable <= 0:
            return Decision(refused=OFFER_BUDGET)
        return Decision(payable, None, p.signature)
    return Decision(refused=best)


@dataclass(frozen=True)
class Seen:
    """Something the claimer did, as read from the chain or from our own records."""

    ref: str                 # a transaction signature or an invoice id: the proof
    at: float
    #: Its value in dollars; None = could not be priced. That only holds a claim up when the
    #: offer has a dollar floor to compare it with.
    usd: float | None
    #: Why it cannot count even though it happened (e.g. a stable-to-stable swap).
    refused: str | None = None
    #: What makes two of these "the same one" when an offer counts several: the person paid,
    #: for an offer about paying different people. Empty means each is its own.
    key: str = ""


@dataclass(frozen=True)
class Tally:
    """How far a claim has got: how many qualifying things, of how many needed."""

    done: int
    need: int
    #: The proof of the one that completed it, or of the latest so far.
    ref: str | None = None
    #: Why the most telling thing that did NOT count was turned away.
    refused: str | None = None
    #: Something inside the window is still waiting on a price, so "expired" would be premature.
    waiting: bool = False


def tally(offer: Offer, claim: Claim, seen: list[Seen]) -> Tally:
    """Counts what the claimer did that qualifies. Shared by the payout decision and by the
    progress the app shows, so "2 of 3" on the screen and the watcher can never disagree.

    Same discipline as [settle]: only actions AFTER the claim count (attribution is the product)
    and only inside the window.
    """
    deadline = claim.claimed_at + offer.window_hours * 3600
    minimum = float(offer.params.get("min_usd", 0) or 0)
    need = offer.need
    best: str | None = None
    waiting = False
    counted: dict[str, str] = {}
    for s in sorted(seen, key=lambda x: x.at):
        if s.at < claim.claimed_at:
            best = best or BEFORE_CLAIM
            continue
        if s.at > deadline:
            best = OUTSIDE_WINDOW
            continue
        if s.refused:
            best = s.refused
            continue
        if s.usd is None and minimum > 0:
            best, waiting = UNPRICED, True
            continue
        if s.usd is not None and s.usd < minimum:
            best = BELOW_MINIMUM
            continue
        if len(counted) < need:
            counted.setdefault(s.key or s.ref, s.ref)
    ref = list(counted.values())[-1] if counted else None
    return Tally(len(counted), need, ref, best, waiting)


def settle_seen(offer: Offer, claim: Claim, seen: list[Seen], now: float,
                spent: int, reward: int | None = None) -> Decision:
    """Does what the claimer did satisfy the offer? Used by every kind except "pay".

    `reward` is what to pay when it does, for a scratch whose amount the caller has drawn; left
    out, it is the offer's fixed reward. Either way the offer's own budget is a hard ceiling.
    """
    deadline = claim.claimed_at + offer.window_hours * 3600
    if now > deadline and not seen:
        return Decision(refused=OUTSIDE_WINDOW)
    t = tally(offer, claim, seen)
    if t.done < t.need:
        # Short of the count when the window shut: nothing later can count, so it is over. One
        # thing is exempt, an action in time that is only waiting to be priced.
        if now > deadline and t.done > 0 and not t.waiting:
            return Decision(refused=OUTSIDE_WINDOW)
        return Decision(refused=t.refused)
    payable = offer.reward if reward is None else reward
    if offer.budget:
        payable = min(payable, max(0, offer.budget - spent))
    if payable <= 0:
        return Decision(refused=OFFER_BUDGET)
    return Decision(payable, None, t.ref)


def _money(v: float) -> str:
    return f"${int(v)}" if float(v).is_integer() else f"${v:.2f}"


def _window(hours: float) -> str:
    if hours >= 48 and hours % 24 == 0:
        return f"{int(hours // 24)} days"
    return f"{int(hours)} hours" if float(hours).is_integer() else f"{hours:g} hours"


def _receive_name(offer: Offer) -> str:
    """The token a swap offer says must arrive, as a person would name it, or "" for any."""
    mint = swaps.receive_mint(offer.params.get("receive"))
    return swaps.TOKEN_NAMES.get(mint, "the token this offer names") if mint else ""


def _swap_floor(offer: Offer) -> str:
    """The least a swap offer accepts, in whichever unit it is measured in, or "" for none."""
    if offer.params.get("min_sol"):
        return f"{float(offer.params['min_sol']):g} SOL"
    usd = float(offer.params.get("min_usd", 0) or 0)
    return _money(usd) if usd > 0 else ""


def reward_text(offer: Offer) -> str:
    return f"Up to {offer.reward} SKR" if offer.variable else f"Flat {offer.reward} SKR"


def _action(offer: Offer) -> str:
    """The one thing to do, as a sentence. Written from the offer's own numbers so the app
    carries no text that could drift from what the watcher actually checks."""
    p, n = offer.params, offer.need
    usd = float(p.get("min_usd", 0) or 0)
    floor = f", {_money(usd)} or more" if usd > 0 else ""
    if offer.kind == "shop":
        scope = p.get("scope") or "any"
        each = " each" if floor and n > 1 else ""
        if scope == "recharge":
            return (f"Recharge any phone in AirPay{floor}." if n == 1
                    else f"Recharge a phone in AirPay {n} times{floor}{each}.")
        thing = "a gift card" if scope == "gift" else "anything"
        where = "in AirPay" if scope == "gift" else "in the AirPay shop"
        return (f"Buy {thing} {where}{floor}." if n == 1
                else f"Make {n} purchases {where}{floor}{each}.")
    if offer.kind == "swap":
        amount = f"Swap {_swap_floor(offer)} or more" if _swap_floor(offer) else "Make a swap"
        into = _receive_name(offer)
        return f"{amount}{f' into {into}' if into else ''} on {offer.app}."
    if offer.kind == "people":
        return f"Pay {n} different people in AirPay{floor}{' each' if floor else ''}."
    if offer.kind == "tap":
        return f"Tap a friend's phone and pay them{f' {_money(usd)} or more' if usd > 0 else ''}."
    if offer.kind == "stake":
        return f"Stake {int(p.get('min_skr') or 100)} SKR or more."
    return f"Pay {offer.app} from AirPay."


def steps_for(offer: Offer) -> list[str]:
    """What to do, in order. An offer may bring its own."""
    own = offer.params.get("steps")
    if isinstance(own, list) and own:
        return [str(x) for x in own]
    return ["Claim this offer.", _action(offer), "Scratch your card."]


def rules_for(offer: Offer, min_age_days: int = 0) -> list[str]:
    p = offer.params
    usd = float(p.get("min_usd", 0) or 0)
    out: list[str] = []
    if offer.kind == "stake":
        out.append(f"At least {int(p.get('min_skr') or 100)} SKR")
    elif offer.kind == "swap" and p.get("min_sol"):
        out.append(f"At least {float(p['min_sol']):g} SOL, paid in SOL")
    elif usd > 0:
        out.append(f"At least {_money(usd)}" + (" each time" if offer.need > 1 else ""))
    if offer.kind == "people":
        out.append(f"{offer.need} different people")
    if offer.kind == "tap":
        out.append("Paid by tapping phones")
    if offer.kind in ("people", "tap"):
        out.append("Your own wallets do not count")
        if min_age_days > 0:
            out.append(f"Wallets newer than {min_age_days} days count only if they are a Seeker")
    if offer.kind == "swap":
        # Only worth saying where it could happen: a swap paid in SOL is never stable to stable.
        if not p.get("min_sol"):
            out.append("Swaps between two stablecoins do not count")
        if _receive_name(offer):
            out.append(f"You must receive {_receive_name(offer)}")
    if offer.variable:
        out.append(f"Every card pays {offer.reward_min} to {offer.reward} SKR")
    out.append(f"Finish within {_window(offer.window_hours)} of claiming")
    out.append("One per person")
    out.append("Counts from when you claim")
    return out


def cta_for(offer: Offer) -> dict:
    """The button: what it says and where in the app it goes."""
    p = offer.params
    target = p.get("cta")
    if target not in CTA_TARGETS:
        if offer.kind == "shop":
            target = "recharge" if p.get("scope") == "recharge" else "shop"
        elif offer.kind == "tap":
            target = "tap"
        elif offer.kind == "people":
            target = "pay"
        else:
            target = "link" if offer.link else "pay"
    label = p.get("cta_label") or {
        "recharge": "Recharge a phone", "shop": "Open the shop", "tap": "Tap and pay",
        "pay": "Pay someone", "link": f"Open {offer.app}",
    }[target]
    return {"label": str(label), "target": target}


# -- the descriptive page ------------------------------------------------------------------
#
# Written for somebody who has never swapped or staked: what the offer is, where it is done, how
# to do it step by step, what they need first and when the reward arrives. An offer can bring its
# own wording for any of these. Whatever it leaves out is written below from the same numbers the
# watcher checks, so an offer added later still gets a full page that cannot promise something
# the watcher will not pay for.

#: Kinds whose proof is a transaction read from the chain, which takes a few minutes to show up.
#: The others are read from AirPay's own records and are quicker.
ON_CHAIN = ("pay", "swap", "stake")


def _host(link: str) -> str:
    """The site a link goes to, as somebody would say it: "jup.ag", not the whole address."""
    try:
        host = urlsplit(link).hostname or ""
    except ValueError:
        return ""
    return host[4:] if host.startswith("www.") else host


def _own(offer: Offer, key: str):
    """The offer's own wording for one part of its page, or None to fall back on the default."""
    value = offer.params.get(key)
    return value if value else None


def _prize(offer: Offer) -> str:
    return (f"between {offer.reward_min} and {offer.reward} SKR" if offer.variable
            else f"{offer.reward} SKR")


def order_of(offer: Offer) -> int:
    try:
        return int(offer.params["order"])
    except (KeyError, TypeError, ValueError):
        return LAST


def about_for(offer: Offer) -> str:
    """One or two sentences: what this offer is and why it exists."""
    own = _own(offer, "about")
    if own:
        return str(own)
    p, n = offer.params, offer.need
    if offer.kind == "shop":
        scope = p.get("scope") or "any"
        if scope == "recharge":
            return ("Recharge any prepaid phone from AirPay and get SKR back. "
                    "It works for your own number or anyone else's.")
        if scope == "gift":
            return "Buy a gift card in AirPay, paid from your wallet, and get SKR back."
        return "Buy something in the AirPay shop, paid from your wallet, and get SKR back."
    if offer.kind == "swap":
        into = _receive_name(offer)
        return (f"Swap one token for {into or 'another'} on {offer.app} and get SKR back. "
                "A swap is a single trade made from your own wallet.")
    if offer.kind == "people":
        return (f"Pay {n} different people from AirPay and get SKR back. "
                "It is a reward for using AirPay the way it is meant to be used.")
    if offer.kind == "tap":
        return ("Pay a friend by holding your two phones together and get SKR back. "
                "No address to type and no code to scan.")
    if offer.kind == "stake":
        return ("Stake SKR and get SKR back. Staking means locking your SKR for a while, and it "
                "is your own choice, so only stake what you are happy to lock.")
    return f"Pay {offer.app} from AirPay and get SKR back."


def where_for(offer: Offer) -> str:
    """A short label for the place it is done."""
    own = _own(offer, "where")
    if own:
        return str(own)
    if offer.kind in ("shop", "people", "tap", "pay"):
        return "In AirPay"
    host = _host(offer.link)
    if offer.kind == "swap" and offer.params.get("package"):
        return f"In the {offer.app} app" + (f" or on {host}" if host else "")
    return f"On {host}" if host else f"On {offer.app}"


def _do_steps(offer: Offer) -> list[tuple[str, str]]:
    """The middle of the guide: how the thing itself is done, for each kind."""
    p, n = offer.params, offer.need
    usd = float(p.get("min_usd", 0) or 0)
    floor = f" of {_money(usd)} or more" if usd > 0 else ""
    approve = ("Approve in your wallet",
               "Your wallet opens and shows what you are about to pay. Approve it there.")
    if offer.kind == "shop":
        scope = p.get("scope") or "any"
        times = f" Do this {n} times." if n > 1 else ""
        if scope == "recharge":
            return [("Open Phone in AirPay",
                     "On Home, find the Recharge and bills section and tap the Phone tile."),
                    ("Pick a number and a plan",
                     f"Enter the phone number and choose a plan{floor}. Tap Proceed with payment "
                     f"and approve it in your wallet.{times}")]
        what = "a gift card" if scope == "gift" else "something to buy"
        return [("Open the shop in AirPay",
                 "On Home, look in the Recharge and bills section. More shows everything on sale."),
                (f"Buy {what}",
                 f"Choose {what}{floor}, confirm, and approve the payment in your wallet.{times}")]
    if offer.kind == "swap":
        host = _host(offer.link)
        into = _receive_name(offer)
        least = _swap_floor(offer)
        return [(f"Open {offer.app}"[:40],
                 f"Open {offer.app}{f' at {host}' if host else ''} and connect the same wallet "
                 "you use in AirPay."),
                ("Set up the swap",
                 f"Choose what you pay with{', which must be SOL' if p.get('min_sol') else ''}"
                 f"{f' and choose {into} as what you receive' if into else ''}."
                 f"{f' Enter {least} or more.' if least else ''}"),
                approve]
    if offer.kind == "people":
        each = f" {_money(usd)} or more each time." if usd > 0 else ""
        return [("Open Pay anyone",
                 "On Home, tap Pay anyone and enter a name ending in .skr, .sol or .air, or a "
                 "wallet address."),
                (f"Pay {n} different people",
                 f"Send each of them a payment and approve it in your wallet.{each} "
                 "Your own wallets do not count.")]
    if offer.kind == "tap":
        amount = f" of {_money(usd)} or more" if usd > 0 else ""
        return [("Have your friend ask for money",
                 "On their phone they open Tap and pay at the bottom of Home, choose Request "
                 f"instead and enter an amount{amount}."),
                ("Hold the phones back to back",
                 "Open Tap and pay on your phone and hold its back against the back of theirs "
                 "until the request appears."),
                approve]
    if offer.kind == "stake":
        host = _host(offer.link)
        return [(f"Open {host or offer.app}"[:40],
                 f"Open {host or offer.app} and connect the same wallet you use in AirPay."),
                (f"Stake {int(p.get('min_skr') or 100)} SKR or more",
                 "Enter the amount, confirm, and approve it in your wallet. Staked SKR is locked "
                 "and unstaking is not instant.")]
    return [(f"Pay {offer.app}"[:40],
             f"Pay {offer.app} from AirPay and approve the payment in your wallet.")]


def guide_for(offer: Offer) -> list[dict]:
    """How to do it, in order, each step with the detail of exactly how. Always opens with the
    claim, because nothing done before it counts, and ends with how the reward arrives."""
    own = _own(offer, "guide")
    if isinstance(own, list) and own:
        return [{"title": str(g.get("title", "")), "detail": str(g.get("detail", ""))} for g in own]
    first = ("Claim this offer",
             f"Tap Claim on this page. Only what you do after claiming counts, and you have "
             f"{_window(offer.window_hours)} to finish.")
    last = ("Scratch your card",
            f"A scratch card appears in Rewards. Scratch it to get {_prize(offer)}.")
    return [{"title": t, "detail": d} for t, d in (first, *_do_steps(offer), last)]


def payout_for(offer: Offer) -> str:
    """One sentence: when the reward arrives, and how."""
    own = _own(offer, "payout")
    if own:
        return str(own)
    if offer.kind in ON_CHAIN:
        return ("Your scratch card shows up in Rewards a few minutes after the transaction "
                "goes through, because it is read from the chain.")
    done = {"shop": "the purchase is delivered", "people": "the last payment goes through",
            "tap": "the payment goes through"}[offer.kind]
    return f"Your scratch card shows up in Rewards a minute or two after {done}."


def needs_for(offer: Offer) -> list[str]:
    """What somebody needs before starting. A Seeker comes first: only a Seeker can claim."""
    own = _own(offer, "needs")
    if isinstance(own, list) and own:
        return [str(x) for x in own]
    p, n = offer.params, offer.need
    usd = float(p.get("min_usd", 0) or 0)
    out = ["A Seeker phone"]
    fee = "A little SOL for the network fee"
    if offer.kind == "shop":
        out.append(f"{_money(usd * n)} or more in USDC or SOL" if usd > 0 else "Some USDC or SOL")
    elif offer.kind == "swap":
        if p.get("min_sol"):
            out += [f"{float(p['min_sol']):g} SOL or more to swap", "A little extra SOL for the network fee"]
        else:
            out += [f"{_money(usd)} or more to swap, for example in SOL" if usd > 0
                    else "Something to swap, for example SOL", fee]
    elif offer.kind == "people":
        out += [f"{n} people to pay", f"{_money(usd)} or more for each of them" if usd > 0
                else "Something to send each of them"]
    elif offer.kind == "tap":
        out += ["A friend with AirPay on a phone with NFC",
                f"{_money(usd)} or more to pay them" if usd > 0 else "Something to pay them"]
    elif offer.kind == "stake":
        out += [f"At least {int(p.get('min_skr') or 100)} SKR in this wallet", fee]
    else:
        out.append(fee)
    return out
