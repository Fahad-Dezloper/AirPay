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


def _step(title: str, detail: str) -> dict:
    return {"title": title, "detail": detail}


def _claim(window: str) -> dict:
    """Every guide opens the same way, because nothing done before the claim counts."""
    return _step("Claim this offer",
                 f"Tap Claim on this page. Only what you do after claiming counts, and you have {window} to finish.")


SEEKER = "A Seeker phone"
FEE = "A little SOL for the network fee"
#: Said on every offer read from the chain, so nobody thinks a slow card is a lost one.
CHAIN_PAYOUT = ("Your scratch card shows up in Rewards a few minutes after the transaction goes through, "
                "because we read it from the chain.")


def launch_offers(now: float, days: float = 30.0) -> list[dict]:
    """The launch campaign. AirPay's own offers carry no icon: the app draws its own mark.

    A scratch (`reward_min` set) pays about a quarter of the way up its range on average, so its
    budget covers roughly `budget / reward` claims at worst and many more in practice.

    The guides are written by hand rather than left to the defaults in offers.py, because each
    names real screens and buttons. They only describe what is known to be there: AirPay's own
    screens, and for the other companies the page the link opens and what the wallet will ask.

    The list is in the order the page shows it, AirPay's offers and other companies' taking
    turns, and each row's `order` is taken from its place here.
    """
    end = now + days * DAY
    month = 30 * 24
    rows = [
        {"id": "jupiter-first-swap", "app": "Jupiter", "kind": "swap", "featured": True,
         "what": "Swap $10 or more into USDC on Jupiter",
         "reward": 75, "budget": 4500, "window_hours": 72,
         "params": {"min_usd": 10, "receive": "USDC", "program": swaps.JUPITER_V6,
                    "package": "ag.jup.jupiter.android"},
         "link": "https://jup.ag", "icon": "https://static.jup.ag/jup/icon.png", "cta": "link",
         "about": "Make one swap of $10 or more into USDC on Jupiter and get SKR back. A swap is a single "
                  "trade: one token leaves your wallet and another arrives.",
         "where": "In the Jupiter app or on jup.ag",
         "needs": [SEEKER, "$10 or more to swap, for example in SOL", FEE],
         "guide": [
             _claim("3 days"),
             _step("Open Jupiter",
                   "Open the Jupiter app (it is on the Solana dApp Store) or go to jup.ag in your browser."),
             _step("Connect the same wallet",
                   "Connect the same wallet you use in AirPay. A swap from a different wallet cannot be "
                   "matched to you."),
             _step("Swap into USDC",
                   "Choose what you pay with, for example SOL, and choose USDC as what you receive. Enter "
                   "$10 or more, tap Swap, and approve it in your wallet."),
             _step("Scratch your card",
                   "A few minutes after the swap goes through, a scratch card appears in Rewards. Scratch "
                   "it to get your 75 SKR."),
         ],
         "payout": CHAIN_PAYOUT, "starts": now, "ends": end},

        {"id": "airpay-top-up", "app": "AirPay", "kind": "shop",
         "what": "Recharge a phone for the first time", "reward": 50, "budget": 5000, "window_hours": month,
         "params": {"scope": "recharge", "min_usd": 2}, "cta": "recharge",
         "about": "Recharge any prepaid phone from AirPay and get SKR back. It works for your own number or "
                  "anyone else's.",
         "where": "In AirPay",
         "needs": [SEEKER, "$2 or more in USDC or SOL", "The phone number to recharge"],
         "guide": [
             _claim("30 days"),
             _step("Open Phone under Recharge and bills",
                   "On Home, find the Recharge and bills section and tap the Phone tile."),
             _step("Enter the number and pick a plan",
                   "Type the phone number you want to recharge and pick a plan of $2 or more. A sheet shows "
                   "the price in USDC or SOL."),
             _step("Pay from your wallet",
                   "Tap Proceed with payment, then approve the payment when your wallet opens."),
             _step("Scratch your card",
                   "Once the recharge is delivered, a scratch card appears in Rewards. Scratch it to get "
                   "your 50 SKR."),
         ],
         "payout": "Your scratch card shows up in Rewards a minute or two after the recharge is delivered.",
         "starts": now, "ends": end},

        # The pool program, the pool and the JitoSOL mint were checked on mainnet (see swaps.py).
        # The stake pool program is shared by many pools, so it is the JitoSOL arriving that makes
        # this Jito's. Buying JitoSOL with SOL on Jupiter counts as well: the person ends up
        # holding the same token either way.
        {"id": "jito-stake-sol", "app": "Jito", "kind": "swap",
         "what": "Stake 0.1 SOL or more with Jito", "reward": 80, "budget": 4000, "window_hours": 72,
         "params": {"min_sol": 0.1, "receive": swaps.JITOSOL,
                    "program": [swaps.SPL_STAKE_POOL, swaps.JUPITER_V6]},
         "steps": ["Claim this offer.", "Stake 0.1 SOL or more with Jito.", "Scratch your card."],
         "link": "https://www.jito.network/staking/",
         "icon": "https://storage.googleapis.com/token-metadata/JitoSOL-256.png",
         "cta": {"label": "Stake with Jito", "target": "link"},
         "about": "Stake 0.1 SOL or more with Jito and get SKR back. You receive JitoSOL, a token that stands "
                  "for your staked SOL. Staking is your own choice, so only stake what you are happy to put in.",
         "where": "On jito.network",
         "needs": [SEEKER, "0.1 SOL or more to stake", "A little extra SOL for the network fee"],
         "guide": [
             _claim("3 days"),
             _step("Open Jito's staking page",
                   "Tap the button below, or open jito.network/staking in your browser."),
             _step("Connect the same wallet",
                   "Connect the same wallet you use in AirPay. A stake from a different wallet cannot be "
                   "matched to you."),
             _step("Stake 0.1 SOL or more",
                   "Enter 0.1 SOL or more, confirm, and approve it in your wallet. SOL leaves your wallet and "
                   "JitoSOL arrives. Swapping SOL for JitoSOL on Jupiter counts too."),
             _step("Scratch your card",
                   "A few minutes after the stake goes through, a scratch card appears in Rewards. Scratch it "
                   "to get your 80 SKR."),
         ],
         "payout": CHAIN_PAYOUT, "starts": now, "ends": end},

        {"id": "airpay-gift-card", "app": "AirPay", "kind": "shop",
         "what": "Buy any gift card", "reward": 200, "reward_min": 20,
         "budget": 6000, "window_hours": month,
         "params": {"scope": "gift", "min_usd": 5}, "cta": "shop",
         "about": "Buy a gift card in AirPay, paid straight from your wallet, and get SKR back. Any brand "
                  "counts as long as the card is $5 or more.",
         "where": "In AirPay",
         "needs": [SEEKER, "$5 or more in USDC or SOL"],
         "guide": [
             _claim("30 days"),
             _step("Find the gift cards",
                   "On Home, go to the Recharge and bills section. Gift cards are under the other tiles there "
                   "and under More."),
             _step("Choose a card of $5 or more",
                   "Pick a brand and an amount of $5 or more. You see the price before you pay."),
             _step("Pay from your wallet",
                   "Confirm the purchase, then approve the payment when your wallet opens."),
             _step("Scratch your card",
                   "Once the gift card is delivered, a scratch card appears in Rewards. It pays between 20 "
                   "and 200 SKR."),
         ],
         "payout": "Your scratch card shows up in Rewards a minute or two after the gift card is delivered.",
         "starts": now, "ends": end},

        # The staking program and the SKR mint were checked on mainnet (see stakes.py). The link is
        # Solana Mobile's own staking page, and the guide is written around that page because it is
        # the one place known for certain. The two-day wait is the whitepaper's: an epoch is two
        # days and unstaking has a one-epoch lockup. The icon is the one the SKR token registers.
        {"id": "skr-stake", "app": "SKR Staking", "kind": "stake",
         "what": "Stake 100 SKR or more", "reward": 100, "budget": 5000,
         "window_hours": 168, "params": {"min_skr": 100},
         "link": "https://stake.solanamobile.com", "cta": {"label": "Stake SKR", "target": "link"},
         "icon": "https://r2.solanamobiledappstore.com/skr/seeker.png",
         "about": "Stake 100 SKR or more with Solana Mobile and get 100 SKR back. The reward is for staking, "
                  "and staking is your own choice: only stake what you are happy to lock.",
         "where": "In Seeker Wallet or on stake.solanamobile.com",
         "needs": [SEEKER, "At least 100 SKR in this wallet", FEE],
         "guide": [
             _claim("7 days"),
             _step("Open stake.solanamobile.com",
                   "Tap the button below, or open stake.solanamobile.com in your browser. The Seeker's "
                   "built-in wallet also offers SKR staking."),
             _step("Connect the same wallet",
                   "Connect the same wallet you use in AirPay. A stake from a different wallet cannot be "
                   "matched to you."),
             _step("Stake 100 SKR or more",
                   "Enter 100 SKR or more, confirm, and approve it in your wallet. Staked SKR is locked, and "
                   "unstaking takes about two days."),
             _step("Scratch your card",
                   "A few minutes after the stake goes through, a scratch card appears in Rewards. Scratch it "
                   "to get your 100 SKR."),
         ],
         "payout": "Your scratch card shows up in Rewards a few minutes after you stake, because we read "
                   "the stake from the chain.",
         "starts": now, "ends": end},

        {"id": "airpay-tap-a-friend", "app": "AirPay", "kind": "tap",
         "what": "Pay a friend by tap", "reward": 60, "budget": 3600, "window_hours": month,
         "params": {"min_usd": 1}, "cta": "tap",
         "about": "Pay a friend $1 or more by holding your two phones together and get SKR back. No address "
                  "to type and no code to scan.",
         "where": "In AirPay",
         "needs": [SEEKER, "A friend with AirPay on a phone with NFC", "$1 or more to pay them"],
         "guide": [
             _claim("30 days"),
             _step("Your friend asks for the money",
                   "On their phone they tap Tap and pay at the bottom of Home, choose Request instead and "
                   "enter $1 or more."),
             _step("Open Tap and pay on your phone",
                   "Tap Tap and pay at the bottom of Home, or press the power button twice. A sheet opens "
                   "with your wallet card."),
             _step("Hold the phones together",
                   "Hold the back of your phone against their phone until the request appears, then approve "
                   "the payment in your wallet."),
             _step("Scratch your card",
                   "Once the payment goes through, a scratch card appears in Rewards. Scratch it to get your "
                   "60 SKR."),
         ],
         "payout": "Your scratch card shows up in Rewards a minute or two after the payment goes through.",
         "starts": now, "ends": end},

        # The Marinade program and the mSOL mint were checked on mainnet (see swaps.py). As with
        # Jito, buying mSOL with SOL on Jupiter counts as well.
        {"id": "marinade-stake-sol", "app": "Marinade", "kind": "swap",
         "what": "Stake 0.1 SOL or more with Marinade", "reward": 80, "budget": 4000, "window_hours": 72,
         "params": {"min_sol": 0.1, "receive": swaps.MSOL,
                    "program": [swaps.MARINADE, swaps.JUPITER_V6]},
         "steps": ["Claim this offer.", "Stake 0.1 SOL or more with Marinade.", "Scratch your card."],
         "link": "https://marinade.finance/app/",
         "icon": "https://raw.githubusercontent.com/solana-labs/token-list/main/assets/mainnet/"
                 "mSoLzYCxHdYgdzU16g5QSh3i5K3z3KZK7ytfqcJm7So/logo.png",
         "cta": {"label": "Stake with Marinade", "target": "link"},
         "about": "Stake 0.1 SOL or more with Marinade and get SKR back. You receive mSOL, a token that "
                  "stands for your staked SOL. Staking is your own choice, so only stake what you are happy "
                  "to put in.",
         "where": "On marinade.finance",
         "needs": [SEEKER, "0.1 SOL or more to stake", "A little extra SOL for the network fee"],
         "guide": [
             _claim("3 days"),
             _step("Open Marinade",
                   "Tap the button below, or open marinade.finance/app in your browser."),
             _step("Connect the same wallet",
                   "Connect the same wallet you use in AirPay. A stake from a different wallet cannot be "
                   "matched to you."),
             _step("Stake 0.1 SOL or more for mSOL",
                   "Enter 0.1 SOL or more, make sure you are receiving mSOL, confirm, and approve it in your "
                   "wallet. Swapping SOL for mSOL on Jupiter counts too."),
             _step("Scratch your card",
                   "A few minutes after the stake goes through, a scratch card appears in Rewards. Scratch it "
                   "to get your 80 SKR."),
         ],
         "payout": CHAIN_PAYOUT, "starts": now, "ends": end},

        {"id": "airpay-pay-three", "app": "AirPay", "kind": "people",
         "what": "Pay 3 different people", "reward": 250, "reward_min": 25,
         "budget": 7500, "window_hours": month,
         "params": {"count": 3, "min_usd": 1}, "cta": "pay",
         "about": "Pay 3 different people from AirPay, $1 or more each, and get SKR back. It is a reward for "
                  "using AirPay to pay real people.",
         "where": "In AirPay",
         "needs": [SEEKER, "3 people to pay", "$1 or more for each of them"],
         "guide": [
             _claim("30 days"),
             _step("Open Pay anyone",
                   "On Home, tap Pay anyone. Enter a name ending in .skr, .sol or .air, or paste a wallet "
                   "address."),
             _step("Send $1 or more",
                   "Enter $1 or more and approve the payment when your wallet opens."),
             _step("Do it for 3 different people",
                   "Pay two more people the same way. Your own wallets do not count, and paying one person "
                   "three times counts once."),
             _step("Scratch your card",
                   "After the third payment a scratch card appears in Rewards. It pays between 25 and 250 SKR."),
         ],
         "payout": "Your scratch card shows up in Rewards a minute or two after the third payment goes through.",
         "starts": now, "ends": end},

        {"id": "airpay-recharge-again", "app": "AirPay", "kind": "shop",
         "what": "Recharge twice this month", "reward": 150, "reward_min": 10,
         "budget": 4500, "window_hours": month,
         "params": {"scope": "recharge", "count": 2, "min_usd": 2}, "cta": "recharge",
         "about": "Recharge a prepaid phone from AirPay twice within 30 days and get SKR back. The two "
                  "recharges can be for the same number or for different ones.",
         "where": "In AirPay",
         "needs": [SEEKER, "$4 or more in USDC or SOL, for two recharges", "The phone number to recharge"],
         "guide": [
             _claim("30 days"),
             _step("Open Phone under Recharge and bills",
                   "On Home, find the Recharge and bills section and tap the Phone tile."),
             _step("Recharge once",
                   "Enter the number, pick a plan of $2 or more, tap Proceed with payment and approve it in "
                   "your wallet."),
             _step("Recharge a second time",
                   "Do the same again any time in the 30 days. Each recharge has to be $2 or more."),
             _step("Scratch your card",
                   "Once the second recharge is delivered, a scratch card appears in Rewards. It pays "
                   "between 10 and 150 SKR."),
         ],
         "payout": "Your scratch card shows up in Rewards a minute or two after the second recharge is delivered.",
         "starts": now, "ends": end},
    ]
    # Tens, so an offer added later can be slotted between two of these without renumbering.
    return [{**row, "order": 10 * (i + 1)} for i, row in enumerate(rows)]


def main() -> None:
    svc = Service(Store(os.environ.get("DUFFLE_DB", "duffle.db")), rpc=None)
    for o in launch_offers(time.time()):
        svc.put_offer(o)
        print("offer", o["id"], "·", o["reward"], "SKR · budget", o["budget"])


if __name__ == "__main__":
    main()
