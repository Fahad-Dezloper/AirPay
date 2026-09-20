# Duffle Rewards — build plan

Branch `feature/rewards`, off `main` at `6fbaf3f`. Everything here is behind one flag
(`rewards.enabled` in local.properties → `BuildConfig.REWARDS`), default **off**, so `main`
behaviour never changes until we turn it on.

Deck: https://claude.ai/artifact/KW1AmFua5R5Btxj2LiK5CV — the why, the numbers and the sources.

## The numbers (decided 2026-09-20, in SKR — never in cents)

Budget: **$200 a month of our own money, plus every SKR Duffle earns**, reinvested early. That
is about **10,400 SKR a month**, or a **2,400 SKR weekly pool**.

| Reward | Amount | Notes |
|---|---|---|
| First payment | **250 SKR** | once per Seeker, on the first completed payment — never on install |
| Each rewarded payment | **50 SKR** flat | ~$0.96; a flat amount, not a percentage, so nobody inflates a payment to themselves |
| Weekly streak | **200 SKR** | for 4 rewarded payments in a week |
| Per user per month | **500 SKR cap** | has to exceed a full streak week (400) or the cap silently eats the streak |
| Per user per campaign | **2,500 SKR cap** | |
| Weekly pool | **2,400 SKR**, first come | when it is out, the app says so — no open-ended promise |

**Every one of these is changeable while the server runs** — they live in the database, not the
code: `GET /v1/rewards/config` reads them (the app shows the live amounts), and a `POST` with
the admin token changes them. Unknown names and unusable values are ignored rather than
guessed at. Setting `weekly_pool` to 0 switches rewards off without a deploy.

### How Duffle makes money (and stops spending ours)

| Line | Amount |
|---|---|
| dApp offers | a dApp pays **500 SKR** per verified new paying user → **300 to the user, 200 to Duffle** |
| Sponsored campaigns | Duffle runs a sponsor's budget and keeps **15%** — one 100k SKR campaign ≈ $288, more than a month of costs |
| Later | a percentage of merchant payments |

**We stop paying at about 65 offer conversions a month**, or one sponsored campaign. Until
then: reinvest everything; from the point revenue covers costs, keep half; later keep more.

**What $200 a month buys:** ~208 rewarded payments, spread across roughly 20 users at the full
cap and 40–60 in practice. That is the right size for launch — the whole Seeker ecosystem is
about 9,000 daily actives, and a meetup is 20–50 people.

**Fixed, never random.** Uncertain rewards outperform fixed ones in the lab, but that is the
scratch-card mechanic Google pulled Paytm over in 2020, and it meets the sweepstakes test.

**Review trigger:** if SKR moves ±40% from $0.0192, revisit every amount here.

## The rules being built (the whole spec, in one place)

A payment earns a reward only when **all** of these hold:

| Rule | Value | Why |
|---|---|---|
| Payer is a verified Seeker | Genesis token held, checked on mainnet **by the server** | one earner per real device; the phone's word isn't enough when money is at stake |
| Paid in person | the payment came from an NFC tap in Duffle | an online transfer can't prove two phones were together — our anti-farm edge |
| Minimum amount | **$5 equivalent**, per token in config (5 USDC/EURC, 0.05 SOL, 250 SKR) | $1 earns dust and clutters the ledger; $5 plus the weekly cap is the anti-wash mechanism |
| Reward size | **50 SKR flat** | see above |
| Rate limit | **4 rewarded payments a week**, 500 SKR a month, 2,500 SKR a campaign | Tez's rule, which worked; the caps bound the worst case and spread the budget |
| Funded | only what is in this week's pool | an empty pool pays nothing and says so |
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

### R1 · Earn on a real tap — **built, not yet run on the phones**
- ✅ A settled payment runs the rules and writes reward rows (`Service.reward_for_payment`,
  called from `_settle`). Refusals are written down too, in `reward_attempts`.
- ✅ The Seeker check is the **server's own**, on mainnet (`genesis.py`), not the badge the
  phone claims — verified against the real wallet: `is_seeker` True, devnet history from
  2026-09-06.
- ✅ `POST /v1/rewards/preview` — the review screen asks what a payment *would* earn, so the
  rules exist once. `GET /v1/rewards/<address>` is the earnings summary.
- ✅ App: "Earns N SKR" / "New wallet · this payment won't earn SKR" on the review screen, and
  "You earned N SKR" on the ✓ screen. All behind `rewards.enabled` in local.properties.
- **Known weakness, to fix in R2:** `origin="tap"` is the phone's claim. A modified build
  could send it for an online transfer. The server should corroborate — both phones
  registering the same reference seconds apart is evidence the app can't fake alone.
- **Check (still to do):** a real devnet tap between the two phones produces exactly one set
  of reward rows, and a second tap to the same person inside 30 days produces none.
- **Decision:** does the earn moment feel worth the screen space?

### R2 · Anti-farm hardening — **built, on-device run still owed**
- ✅ `patrol.py`: pure pattern checks over locked rewards — **round trip** (the same amount back
  within 6h), **burst** (4+ rewarded payments in 10 minutes, first one spared), and
  **uncorroborated tap**. Reversal carries a reason, and settles what has matured.
- ✅ Runs every 10 minutes from the watcher. Nothing it finds is urgent: the reward it might
  reverse is locked for a week, which is what the week is for.
- ✅ Corroboration: a real tap leaves **two** registrations of one reference — the phone that
  served it, then the phone that received it over the radio and knows who is paying. The store
  records `payer_at` and `registrations` and derives it.
  **It is evidence, not proof**, so an uncorroborated tap alone never loses a reward; it only
  strengthens a pattern that already fired. A farmer with two phones registers twice too, and
  an honest payer whose phone was briefly offline registers once.
- ✅ `GET /v1/admin/rewards` (admin token): paid by rule and by day, refusals by reason,
  reversals, distinct people.
- ✅ App: a reversed reward shows as **−N with the reason**, in the place the reward was. It is
  not deleted — someone who saw 300 SKR and later sees 0 is owed the reason on the same screen.
- 23 tests, deliberately half of them **must-not-reverse**: a repayment the next day, a
  different amount, a different token, three friends in ten minutes, a queue of separate payers.
  A rule that fires on honest behaviour costs more than the farming it prevents.
- **Check (still to do):** scripted farming run on devnet — see TESTING.md.
- **Decision:** is anything else farmable? This is the phase to be paranoid.

### R3 · Streaks and the weekly picture
- Streak state and a "3 of 5 this week" strip in the app; weekly cap shown honestly.
- **Check:** streak advances once per day at most, resets on a missed week.
- **Decision:** does a streak change behaviour at a meetup, or is it noise?

### R4 · Offers — **unfunded first** (decided 2026-09-20)

Nobody will fund an offer for an app with no users, and waiting for a sponsor before building
the rail means never having the numbers that would win one. So the first offers are **funded by
Duffle out of the weekly pool that already exists** — no new money, the same 2,400 SKR a week —
and the thing sold to dApps later is not the SKR at all. It is the proof:

> *37 verified Seeker owners opened your offer, 12 paid you. Here are the signatures.*

A dApp funds the **second** offer, once that number exists. The code path is identical; a
`funder` field and a budget are the only difference between a Duffle offer and a paid one, so
nothing has to be rebuilt when the first one says yes.

- Server: `offers` (funder, dApp, address to pay, token, minimum, window, reward, budget) and
  `claims` (who opened it, when, and the payment that satisfied it).
- Verification watches the **claimer's** wallet, not the dApp's: a busy dApp address is
  unwatchable, while the set of people who opened an offer is small and known.
- Spend comes out of the same weekly pool as tap rewards, so the campaign cannot cost more than
  is already budgeted — an empty pool pays nothing and says so.
- App: an Offers tab, the seeded offers, claim + state + proof.
- **Check:** a devnet payment to the offer address pays out once; a payment outside the window,
  below the minimum, or from a non-Seeker pays nothing.
- **Decision:** after the first real numbers — which dApps to approach, and with what price.

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
