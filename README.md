<h1 align="center">CellUp</h1>

<p align="center">
  <b>Recharge your phone with crypto.</b><br>
  Prepaid top-ups in 140+ countries, paid in USDC straight from the Solana wallet you already have.
</p>

<p align="center">
  <img src="https://img.shields.io/badge/Solana-mainnet-14F195?logo=solana&logoColor=white" alt="Solana mainnet">
  <img src="https://img.shields.io/badge/Android-Kotlin%20%2B%20Compose-3DDC84?logo=android&logoColor=white" alt="Android, Kotlin and Compose">
  <img src="https://img.shields.io/badge/Built%20for-Solana%20Seeker-0B6FD6" alt="Built for the Solana Seeker">
  <img src="https://img.shields.io/badge/Wallet-never%20holds%20your%20keys-111418" alt="Never holds your keys">
</p>

CellUp is a native Android app for the Solana Seeker that does one thing: tops up a prepaid phone,
anywhere. Type the number, CellUp finds its network, shows that network's plans, and you pay the
exact USDC amount from your own wallet. It never holds your money or your keys; every payment is
signed in your wallet and checked on Solana mainnet.

> **Looking for AirPay?** The earlier, wider app (pay anyone, tap to pay, shop, SKR rewards),
> released as AirPay 1.0.2, is on the [`old`](../../tree/old) branch. The Android package is still
> `so.duffle.app` and some folders say `duffle`; those are earlier names of the same project.

---

## What it does

| | |
|---|---|
| 🌍 **140+ countries** | Every country Cryptorefills sells top-ups in. Small plans its minimum won't take (such as ₹10 and ₹20) come from Bitrefill instead. |
| 🔎 **Finds the network for you** | From the number itself: a live lookup first, then the number's prefix (libphonenumber), with a table of renamed networks. Tested on numbers from 12 countries. You can always change it. |
| 📋 **Every plan, sorted** | All plans by price, or by kind (plans, data, top-up), swipeable. Prices in the local currency: ₹49, Rp7,650, ₦1,000. |
| 💸 **Pay in USDC** | One transfer from your wallet to the supplier's one-off address. The network fee is a little SOL from your wallet. |
| 🧭 **Plain statuses** | Awaiting payment, Processing, Successful, Cancelled, Failed, Refunded, with a placed, paid, delivered timeline. Recharge again opens the same plan. |
| 🛟 **Help per order** | Ask about a recharge from inside the app. The email to support carries the order's details from the server's own records, and replies go to the customer. |

## How a recharge works

```
 number ──► network found ──► plans ──► pick one ──► order placed with the supplier
                                                         │
             wallet signs one USDC transfer ◄────────────┘  (exact amount, one-off address)
                         │
                         ▼
           supplier sees the payment ──► recharge delivered ──► push + "Successful"
```

The server places the order with **Cryptorefills** (or **Bitrefill** for the small plans), hands the
app the address and exact amount, then follows the order to the end and pushes the result. Abandoned
checkouts are cancelled so they never count as unpaid orders.

| Folder | What it is |
|---|---|
| [`app/`](app) | The Android app. Kotlin and Jetpack Compose. CellUp's screens are in `CellUpActivity.kt`, the recharge client in `TopUp.kt`, number handling in `Phones.kt`. |
| [`server/`](server) | The backend. Python and SQLite, in Docker, deployed to Render from `main`. Recharges in `topups.py` and `cryptorefills.py`, support mail in `support.py`. |
| [`payer/`](payer) | A small separate service that holds keys. Used by AirPay's rewards and fee sponsorship; CellUp doesn't need it. |

---

## Get it running

### The fast way: let an AI assistant set it up

[`AGENTS.md`](AGENTS.md) is written for AI coding assistants. Open the repository in yours and paste:

```text
Read AGENTS.md and set this project up for me. Check what's already installed and configured,
ask me only for what's missing, start the server, then build the app and install it on my
connected phone. Don't press Pay or approve anything in a wallet; tell me when it's my turn.
```

### The manual way

<details>
<summary><b>1. What you need</b></summary>

- macOS or Linux, **JDK 17**, the **Android SDK** (API 36) and `adb`
- **Docker** for the server, or **Python 3.10+** to run it directly
- An Android phone, Android 12 or newer, with a Solana wallet app (Seeker Wallet, Phantom, Solflare, Backpack)

</details>

<details>
<summary><b>2. Keys and accounts (each switches a feature on)</b></summary>

None of these are in the repository. Without them the server still runs; that feature stays off.

| What | Where it goes | What it switches on |
|---|---|---|
| Cryptorefills partner key (and a second for failover) | `CRYPTOREFILL_API`, `CRYPTOREFILL_API_2` in `server/.env` | Recharges |
| Bitrefill API key | `BITREFILL_API_KEY` in `server/.env` | Network lookup and the small plans |
| Gmail address and its **app password** | `SUPPORT_SMTP_USER`, `SUPPORT_SMTP_PASSWORD` in `server/.env` | Support emails sent by the server. Without them the app opens the phone's mail app instead. |
| Solana RPC URL (Helius or Alchemy) | `DUFFLE_MAINNET_RPC` in `server/.env` | Reliable chain reads |
| Firebase project | `firebase.*` in `local.properties`, service account via `FCM_KEY_FILE` | Push notifications |

`secrets/`, `.env` and `local.properties` are gitignored. Never commit them. On Render the same
names are set under the service's Environment (see [`render.yaml`](render.yaml)).

</details>

<details>
<summary><b>3. Start the server on your computer</b></summary>

```bash
./run.sh            # build and start in Docker, wait until healthy
./run.sh status
./run.sh logs
./run.sh stop
```

Or without Docker: `cd server && python -m duffleserver` (it reads the same environment).

</details>

<details>
<summary><b>4. Build and install the app</b></summary>

For a phone on USB talking to the server on your computer:

```bash
adb reverse tcp:8787 tcp:8787
./gradlew :app:installBenchmark -Pdev.server=http://localhost:8787
```

| Build | App id | Use it for |
|---|---|---|
| `debug` | `so.duffle.app.dev` | Development. Installs beside the released app. |
| `benchmark` | `so.duffle.app.bench` | Testing smoothness: release speed, debug signing. |
| `share` | `so.duffle.app` | A build to give someone else, signed with your own key. |

Debug builds of Jetpack Compose run several times slower than release builds; judge animations on
`benchmark`.

</details>

### Tests

```bash
./gradlew :app:testDebugUnitTest      # app
./run.sh test                         # server
```

---

## Good to know

- **It moves real money.** Recharges are paid in USDC on mainnet. Test with a small plan.
- **Minimums.** Cryptorefills refuses orders under 0.5 EUR; those plans are hidden or come from
  Bitrefill.
- **One network lookup per number.** US and Canadian numbers have no prefix data, so they rely on the
  live lookup, which can take a few seconds.
- **Not a wallet.** CellUp holds no keys and can't move anyone's funds.

## More docs

- [AGENTS.md](AGENTS.md): a map of the code for AI assistants and new contributors
- [server/README.md](server/README.md): how the server verifies payments
- [TESTING.md](TESTING.md): testing on real phones
