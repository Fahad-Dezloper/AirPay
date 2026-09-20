# Duffle Rewards — build plan

Branch `feature/rewards`, off `main` at `6fbaf3f`. Everything here is behind one flag
(`rewards.enabled` in local.properties → `BuildConfig.REWARDS`), default **off**, so `main`
behaviour never changes until we turn it on.

Deck: https://claude.ai/artifact/KW1AmFua5R5Btxj2LiK5CV — the why, the numbers and the sources.

## The numbers (decided 2026-09-20, in SKR — never in cents)

One campaign = **1,000,000 SKR**. Everything is denominated in SKR and shown that way in the
app: a small reward priced in money measurably underperforms no reward at all (Heyman &
Ariely; Gneezy & Rustichini), and SKR moved between $0.0077 and $0.0289 in the last 30 days,
so a dollar promise would break itself.

| Reward | Amount | Budget | Why this size |
|---|---|---|---|
| First payment | **1,000 SKR**, once per Seeker | 300k (300 users) | Matches Seeker Summer's tier-1 fortnightly reward; paid on the first completed payment, never on install |
| Each rewarded payment | **50 SKR** flat | 450k (9,000 payments) | A flat fee, not 2%: the old 20 SKR cap already bound on any payment over $19, and a percentage invites inflated self-payments |
| Weekly streak | **250 SKR** for 4 rewarded payments in a week | 250k (1,000 weeks) | The habit is the point; the card starts pre-filled with one (endowed progress: 19% → 34% completion for identical effort) |

**Per-user campaign cap: 2,500 SKR.** So the pool is 400 users at the cap, or roughly
800–1,200 in practice. Budget against the real audience — about 43,669 wallets hold SKR and
Season 2 runs about 9,000 daily actives — not the 150k phones.

**The bar this had to clear:** an average Season 1 holder (18,033 SKR) earns about **83 SKR a
week just by staking** at 24%. A user doing two payments a week earns 100 SKR; a full week
with the streak earns 450. Below that bar nobody would tap for it.

**Fixed, never random.** Uncertain rewards outperform fixed ones in the lab, but that is the
scratch-card mechanic Google pulled Paytm over in 2020, and it meets the sweepstakes test.
Not worth it.

**Review trigger:** if SKR moves ±40% from $0.0192, revisit every amount here.

## The rules being built (the whole spec, in one place)

A payment earns a reward only when **all** of these hold:

| Rule | Value | Why |
|---|---|---|
| Payer is a verified Seeker | Genesis token held, checked on mainnet **by the server** | one earner per real device; the phone's word isn't enough when money is at stake |
| Paid in person | the payment came from an NFC tap in Duffle | an online transfer can't prove two phones were together — our anti-farm edge |
| Minimum amount | **$5 equivalent**, per token in config (5 USDC/EURC, 0.05 SOL, 250 SKR) | $1 earns dust and clutters the ledger; $5 plus the weekly cap is the anti-wash mechanism |
| Reward size | **50 SKR flat** | see above |
| Rate limit | **4 rewarded payments a week**, 2,500 SKR per user per campaign | Tez's rule, which worked; the campaign cap bounds the worst case |
| Real counterparty | their wallet has a transaction older than **7 days**, **or** holds Genesis | wallets are free: without this, one Seeker + a spare phone farms itself |
| Counterparty cooldown | the same person earns you a reward once per **30 days** | stops two friends paying each other in circles |
| Fixed, never random | no lottery or scratch-card mechanics | Google pulled Paytm in 2020 over scratch cards it called gambling |
| Clawback | rewards stay locked **7 days** and can be reversed | wash patterns show up after the fact |

**Say it before they pay, not after.** When the person being paid has a new wallet, the review
screen says so plainly — "New wallet · this payment won't earn a reward" — next to the amount.
The device page already shows "Brand new" for the same wallet. Nobody should learn the rule by
not being paid.

**Devnet only.** Devnet payments are free, so real SKR must never be paid for a devnet
payment. Until mainnet, rewards are recorded in a ledger and shown in the app; payout is
either simulated or devnet-SKR. Phase R6 decides.

## Phases

Each phase is small, ends in something runnable on the two phones, and has a decision point
before the next one starts.

### R0 · Rules engine, no UI
- Pure functions + tables: `reward_for(payment, history, config)`, nothing else.
- Server: `rewards` table (reference, payer, counterparty, amount, token, skr, state,
  earned_at, unlock_at), `reward_config`, weekly/yearly counters.
- Tests first, including the farming cases: self-tap with a fresh wallet, two Seekers in a
  loop, dust payments, the same counterparty twice in a month, cap overflow.
- **Check:** `python3 -m unittest` — every rule has a must-pay and a must-not-pay test.
- **Decision:** are the numbers above right before anything is built on them?

### R1 · Earn on a real tap
- The server already watches each payment reference; when one settles, run the rules and
  write a reward row.
- Counterparty check: wallet age/history via the same RPC the device page uses.
- App: "You earned X SKR" on the success screen, behind the flag.
- **Check:** a real devnet tap between the two phones produces exactly one reward row, and a
  second tap to the same person inside 30 days produces none.
- **Decision:** does the earn moment feel worth the screen space?

### R2 · Anti-farm hardening
- Clawback job: locked rewards, pattern checks (reciprocal pairs, round-trip amounts, bursts),
  reversal with a reason.
- Admin view: rewards issued, by rule, per day; rejected attempts and why.
- **Check:** scripted farming run on devnet (two phones, back-and-forth) earns once, then nothing.
- **Decision:** is anything else farmable? This is the phase to be paranoid.

### R3 · Streaks and the weekly picture
- Streak state and a "3 of 5 this week" strip in the app; weekly cap shown honestly.
- **Check:** streak advances once per day at most, resets on a missed week.
- **Decision:** does a streak change behaviour at a meetup, or is it noise?

### R4 · Offers (the layer that pays for itself)
- Server: offers (dApp, address to pay, token, amount, window, budget, reward), claim rules.
- Verification reuses the payment checker: a payment to the dApp's address, in the window,
  from a verified Seeker.
- App: an Offers tab, one seeded offer, claim + state.
- **Check:** a devnet payment to the offer address pays out once; a payment outside the window
  or from a non-Seeker pays nothing.
- **Decision:** would a real dApp fund one? Worth asking two before building more.

### R5 · SKR tiers
- Read the payer's staked SKR (mainnet) → tier → multiplier on both rewards and offers.
- App: tier badge and what the next tier gives.
- **Check:** a wallet with staked SKR gets the higher rate; an unreachable RPC falls back to
  the base rate, never a guessed one.
- **Decision:** does a tier actually make anyone stake? Ask the first users.

### R6 · Payout
- Choose: simulated ledger, devnet SKR from a treasury keypair (server-signed), or nothing
  until mainnet.
- If devnet SKR: treasury key handling, rate limits, and the same transaction whitelist
  discipline the Q2 fee sponsorship will need.
- **Check:** a payout moves devnet SKR and the ledger matches the chain.
- **Decision:** what we claim on stage — "rewards are live on devnet" vs "rewards are simulated".

### R7 · Demo
- One clean run: tap → pay → earn → offer → tier, on two phones, recorded.
- Deck updated with whatever the numbers turned out to be.

## Out of scope here (Q2, in the deck)

Sponsored fees paid in SKR — the server signs as fee payer so users never need SOL, and
charges a little SKR for it. Needs its own security work: the server must inspect and
whitelist every transaction before signing, or a crafted transaction drains the sponsor
wallet. Also unverified: whether Seeker Wallet and Phantom will sign a transaction where they
are not the fee payer. One-hour spike before it is promised to anyone.

## Kill rule

If dApp offers don't cover half the reward spend by week 8 of a real launch, tap rewards
pause. It is a launch budget, not a promise — and saying so up front is part of the pitch.
