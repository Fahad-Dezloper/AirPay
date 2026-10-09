# What crypto can actually do for everyday spending — research, 2026-09-22

Three parallel investigations into whether Duffle can become an app someone opens on a Tuesday:
bill payment and recharge, Solana money primitives, and what Indian law permits. Sources and
dates are kept because the answers move; anything unverified is marked, and stays marked.

**The short version.** Recharges are a demo asset, not a business. Indian utilities are closed.
The everyday hook worth building is **tap to authorise a standing order**, using a Solana
Foundation primitive that shipped in June 2026 and almost nobody has used.

---

## 1. The decision

| Candidate | Verdict |
|---|---|
| **Subscriptions & Allowances** — tap to authorise | **Build this.** Devnet-capable, audited, Foundation primitive, fits the gesture |
| Fiat top-up (Coinflow / Sphere embedded ramp) | Cheap second. Public docs, no licence needed — you never hold the funds |
| Mobile recharge via Bitrefill | One real ₹10 refill as a recorded demo. Not a revenue line |
| "Your USDC could be earning 4.66%" | One honest read-only row that deep-links out. Not a tab, not a promise |
| Borrow against SOL | **No.** 85% liquidation threshold in an app whose job is spending means your own UI causes the liquidation |
| Cards | **No.** Every rail is a sales conversation and a programme-manager licence. Roadmap slide |
| Indian utility bills (electricity, gas, water, broadband) | **Impossible.** No crypto-native provider serves one, anywhere |

---

## 2. Bill payment and recharge

**Bitrefill is the only self-serve crypto→real-world rail that covers India.**
Docs <https://docs.bitrefill.com/docs/api-overview>, operator Airfill Prepaid AB (Sweden).

- **Personal API**: bearer token, self-serve from account settings, no approval step.
- `payment_method` accepts **`solana`** and **`usdc_solana`**.
- Flow: `GET /products?country=IN&type=phone_refill` → `GET /check_phone_number` →
  `POST /invoices` returns a payment address and exact price.
- **That address is just another recipient**, so `Chain.buildTransfer`, the Solana Pay
  `reference`, MWA signing and the existing confirmation polling all work unchanged.

India catalog, verified live 2026-09-22: Airtel ₹10–5,000, Vi, BSNL, Airtel DTH, Tata Play,
Dish TV, d2h, Sun Direct, and PhonePe/Flipkart/Swiggy/Zomato/Blinkit gift cards.
**Jio is out of stock** — 39% of the Indian market, so do not demo on it.

**CryptoRefills** (<https://www.cryptorefills.com/en/api-docs>) is the fallback: Partner ID issued
instantly with no approval, Solana supported. Its India catalog is **its own marketing claim and
unverified** — sign up and check before relying on it.

**Bando is dead.** `bando.cool` pivoted to Mexican treasury bills; the API 404s and the docs
domain does not resolve. It was the obvious candidate and it is gone.

### The margins, which decide it

Published Indian reseller commissions (a1topup.com, Starter→Platinum):

| Service | Margin |
|---|---|
| Jio prepaid | 0.60–0.80% |
| Airtel prepaid | 0.80–1.00% |
| **Electricity bill** | **0.00%** |
| **Postpaid bill** | **0.00%** |
| DTH (Videocon/Airtel Digital) | 4.00–4.20% |
| Vi / BSNL | 3.00–3.50% |

The two operators covering 77% of India pay under 1%, and utility bills pay nothing at all.
Indian bill payment is a retention loss-leader. **Any plan where recharge margin funds SKR
rewards dies on this table** — fund rewards from the token and say so plainly.

### Traps
- **No devnet sandbox.** Test products are a Business API feature needing Partner Program
  approval. Either do one real mainnet refill for the demo, or ship the catalog read-only with
  an explicit "purchase disabled — demo catalog" state.
- **No documented quote expiry**, but a crypto-denominated invoice is a quote against a moving
  rate. This is the wire-v3 shared-blockhash problem again: two parties watching one perishable
  fact. Hard client-side expiry, and fail with "quote expired — nothing was sent".

---

## 3. India: where the walls are

Not legal advice. This is a map of which questions need a lawyer.

**Every route to a utility bill needs a licence an individual cannot hold:**

| To do this | You need |
|---|---|
| Convert crypto→INR yourself | FIU-IND VDA SP registration: company, Designated Director, full-time Principal Officer, live compliance-system demo, cyber audit |
| Touch the funds at any point | RBI Payment Aggregator — **₹15 crore to apply, ₹25 crore by year three** |
| Reach billers directly | BBPOU — **₹25 crore** |

**The unlicensed shortcut is being prosecuted now.** 17 June 2026: the Enforcement Directorate
searched six Bengaluru premises under **FEMA**, targeting five crypto payment firms alleged to
have moved **>$265M in USDT** by taking INR into company accounts and converting. USDT's India
premium jumped from 3–4% to over 8.5%.
<https://www.coindesk.com/markets/2026/06/29/usdt-jumps-to-8-5-premium-in-india-after-crypto-payment-crackdown>

**The tax makes it irrational even where legal.** Spending a VDA is a transfer: **30% + 4% cess**
under s.115BBH with no loss offset, plus **1% TDS** under s.194S which in a peer-to-peer flow
falls on the *user*. Section 285BAA transaction-level reporting went live 1 April 2026. Budget
2026 changed nothing. Nobody pays a ₹299 bill that way to save nothing over UPI.

**What is safe is exactly what Duffle already is:** non-custodial, no keys held, no fiat touched.
That maps onto the "payment gateway… without handling funds" carve-out in RBI's PA Directions
2025. **Devnet is not just a shortcut — it is the reason none of this applies yet.**

**If real-world utility is wanted in India**, the only route is integration, not construction:
a licensed counterparty owns the conversion leg and the regulatory exposure; Duffle stays the
wallet and the UX. Verify any partner's registrations yourself rather than trusting their site.

---

## 4. Solana primitives

### The one to build: Subscriptions & Allowances

Program `De1egAFMkMWZSN5rYXRj9CAdheBamobVNubTsi9avR44`, audited by Cantina, shipped ~June 2026.
<https://github.com/solana-program/subscriptions> · <https://solana.com/docs/payments/subscriptions/overview>
Design partners include **Helius**. Works with SPL Token and Token-2022.

Three models: fixed delegation (spend up to N), recurring delegation (cap resets per period),
and subscription plans (merchant publishes terms, approved pullers collect). A Subscription
Authority PDA becomes the single SPL delegate — which is the whole point, since plain SPL
`approve` allows one delegate and each new approval wipes the last.

**Why it fits Duffle better than anything else:**
- The gesture maps onto it. Tap a kid's phone to grant a weekly allowance; tap a gym to start a
  membership; tap a canteen to open a capped tab. That is a reason to open the app when *not*
  paying a friend.
- **The backend is already the missing piece.** Someone must actively pull each period;
  `duffleserver` is that puller. Most teams have no backend at all.
- It can live on devnet, so the demo stays on one cluster.

**Integration:** no HTTP transaction-builder exists, so build the instruction in `duffleserver`
(TypeScript `@solana/subscriptions`) and return base64; `ServerClient.ask` already has the shape.
Then `Wallet.signOnly` → `Chain.submit`. **Do not run a base64 v0 transaction through
`Chain.reserveSignatureSlot`** — that exists for sol4k's zero-slot serialisation and a proper
versioned transaction already has placeholders.

Verify the canonical devnet deployment exists before building; if not, the program is open
source and can be deployed.

### Yield, honestly

Jupiter Lend exposes base64 unsigned transactions with **no API key** at `lite-api.jup.ag`
(`POST /lend/v1/earn/deposit`, `/withdraw`). USDC **4.66%** on 2026-09-22, ~$1.18B TVL.
Kamino is the alternative at ~4.26%, keyless REST. **Both mainnet-only.**

Deposit-only Earn vaults have no liquidation — the risks are borrower default, contract risk,
and **withdrawals queueing when a pool is fully utilised**. That last one is the disclosure a
payments app owes its user: *your spending money may not be instantly spendable.*

### Not now
- **Cards**: Stripe Issuing + Bridge has a live Solana program
  (<https://docs.stripe.com/issuing/bridge-stablecoin-cards>, Bridge program
  `cardWArqhdV5jeRXXjUti7cHAa4mj41Nj3Apc6RPZH2`) but onboarding is sandbox-only until a signed
  addendum, then 6–8 weeks plus KYB. **The delegate-approval mechanism can be demoed on devnet
  unfunded and clearly labelled** — and it is the same shape as Subscriptions, so it is one
  mental model and two demos. Baanx (<https://docs.baanx.com/guides/introduction>, "native
  Solana integration") is the post-hackathon conversation.
- **Borrowing**: Jupiter SOL→USDC vault is 80% LTV, **85% liquidation threshold**, 1% penalty.
  In an app whose job is moving money, a tap can push a user into liquidation. Every other
  candidate fails safe; this one fails loud.

### Cheaper than expected: fiat in
**Coinflow** (<https://docs.coinflow.cash/>) — full public API reference and React SDK. Card,
ACH, SEPA, UK Faster Payments, PIX, Apple/Google Pay in, settling **instantly as USDC to a
Solana address you specify**. You never become the counterparty. Sphere's embedded ramp has a
self-serve register page. Either gives "top up → tap to pay" without a licence.

---

## 5. The competitive picture

**`phasmapay/app`** — *"NFC tap-to-pay on Solana Seeker. Instant USDC payments, AI gas
optimization, SKR cashback rewards."* Same device, same asset, **same SKR-rewards idea**.
Created 2026-02-25, last push 2026-03-09, stalled. Its one idea Duffle lacks: **ephemeral
receiver addresses**, so a merchant's wallet is not publicly readable. Adopt it or have a
reason not to.

**Cash App shipped USDC on Solana to ~60M users on 2026-05-27.** $2,000/day send. "Send
stablecoins to a friend" is no longer a differentiator at all — **the gesture and the device
are the product**, not the transfer.

**`hardusss/solana-p2p-nfc`** builds an asymmetric architecture *because iOS cannot do HCE*
(Android emits, iOS/Android read), and notes raw NFC deeplinks on iOS get hijacked by Apple
Wallet. Strong corroboration for the open iPhone question — still unverified on our hardware.

**`dpa9210/TapWifSol`** still active (2026-09-19). **SolWear** shipping wearable NFC hardware.
**TrueTap** appears dead (repo 404, domain does not resolve) but its pitch is still cached.

**The dApp Store is ~1,561 apps**, up from the 1,006 recorded in CLAUDE.md — that corpus is
stale by ~55%. **SP3ND** already lets Seeker users spend SKR at retailers with no off-ramp and
no KYC, and is ~#20 among Seeker dApps: an everyday hook already on the device.

---

## 6. Regulatory traps that touch our code

**1. The rewards ledger may be stored value.** *(Inference — needs a lawyer before mainnet.)*
Funding rewards from a Duffle treasury is fine. But rewards that accrue as a **balance inside
the app which the user later withdraws** is custodial stored value. That is the current shape:
`rewards` rows with locked/ready/settled and a displayed balance. **R6 should pay rewards as
immediate on-chain transfers, never as an app-held balance.** The Solana Pay `reference`
polling is not custody and is fine.

**2. The SKR layer is on the right side of the yield line, and that is worth saying aloud.**
The CLARITY Act (House 294–134; Senate Banking 15–9 on 2026-05-14) codifies the split: passive
yield for *holding* is prohibited, **activity-based rewards are permitted** — including
"payment and settlement rewards tied to actual transaction processing". Duffle pays for *using*
the app. The existing anti-wash rule ("reward only payments people would make anyway") is the
same instinct the statute encodes. A yield tab is on the wrong side of the same line.

**3. Custody is the firewall, and we already have it.** FinCEN FIN-2019-G001 turns on where
value is stored and whether the intermediary has "total independent control". Mere software
developers are not transmitters; service providers are; and FinCEN applies the test to a DApp
*and its operators*, so "decentralised" is not a defence. **Never build a pooled float, a
Duffle-controlled vault, or a sweep account.**

**4. A card or a fiat off-ramp detonates all of the above** — programme manager, KYC/AML,
sanctions screening, BIN sponsor; or money transmitter in most US states. This, not the
engineering, is why cards are out of scope.

---

## 7. Unverified — do not repeat as fact

- CryptoRefills' India operator list (their claim, not corroborated).
- Reloadly utility billers in India — probably none; the product appears de-emphasised.
- Bitrefill Business/Partner commission rates — not published.
- Whether the Subscriptions program is deployed at the canonical ID **on devnet**.
- Whether Kamino's `/ktx/...` endpoints return base64 (not POSTed to).
- Indian tax specifics read from secondary tax-blog summaries, not the Act — needs a CA.
- CryptoSe's claimed FIU registration (marketing page only).
- Kast's 8–12% "reserve rate" is an **introductory offer ending 2027-07-01**, not a standing rate.
- Rain's Solana support (only public case study is Avalanche).
- Non-India market licensing detail (Brazil, Philippines, Nigeria, Argentina) — thin.

---

## 8. What this changes about the plan

Nothing about the rewards layer, which stands — except **R6 must not create an app-held
balance**. The recharge idea becomes one recorded mainnet moment rather than a feature. The
everyday hook becomes **tap to authorise**, and the highest-leverage architectural move
available is making `Wire.kt` carry a **Solana Pay transaction request URL** instead of a
transfer: the same 100ms one-way tap then becomes "tap to do a thing" — subscribe, open a tab,
pay-and-earn-loyalty in one signature. One field, one wire-version bump, both phones reflashed.

With 17 days to 2026-10-09 and Phase 4 (the demo video, 25% of the score) not started, the
honest scope is **one new capability shipped properly, then the video** — not four half-built
tabs.
