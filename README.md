# AirPay

**Spend your crypto on everyday life.** AirPay is a native Android app for the Solana Seeker that
turns the wallets you already have into a way to pay: recharge a phone, pay a bill, buy a gift
card, pay a person by tapping phones, and earn SKR while you do it.

Built for Clock In, the Solana Mobile hackathon. It runs on **Solana mainnet** and never holds
anyone's keys: every payment is signed in the user's own wallet.

> The Android package is `so.duffle.app` and some folders say `duffle`. That was the app's
> earlier name. It is the same app.

## What it does

| | |
|---|---|
| **Tap to pay** | Hold two phones together. One acts as a card over NFC, the other reads it, and the pay sheet opens. Works Seeker to Seeker and Seeker to any Android phone with NFC. |
| **Pay anyone** | Type a `.skr`, `.sol` or `.air` name, or scan a Solana Pay QR code. |
| **Bills and recharges** | Phone recharges, bill payments and gift cards for 150+ services, paid in USDC or SOL. Sold and delivered by Bitrefill. |
| **Every wallet in one place** | Link Seeker Wallet, Phantom, Solflare and Backpack through Mobile Wallet Adapter and switch between them. |
| **Earn SKR** | Verified Seeker owners earn SKR as scratch cards, paid to their own wallet. Plus offers and referrals. |
| **Buy SKR** | Swap any token you hold for SKR inside the app, at the best rate Jupiter finds. |
| **Free network fees** | A wallet holding 1,000 SKR pays no gas. AirPay's fee wallet signs as the fee payer, so the user never needs SOL. |

## How it is put together

```
┌────────────────────┐   signs    ┌───────────────────────┐
│  Android app       │──────────► │  The user's wallet    │
│  app/              │   (MWA)    │  (Seeker, Phantom, …) │
└─────────┬──────────┘            └───────────────────────┘
          │ HTTPS
          ▼
┌────────────────────┐   asks     ┌───────────────────────┐
│  AirPay server     │──────────► │  SKR payer            │
│  server/           │   (HMAC)   │  payer/               │
│  watches the chain,│            │  the only thing that  │
│  shop, rewards,    │            │  holds a key: pays    │
│  push              │            │  rewards, sponsors    │
└────────────────────┘            │  network fees         │
                                  └───────────────────────┘
```

- **`app/`** The Android app. Kotlin and Jetpack Compose, one module.
- **`server/`** The backend. Python, standard library plus SQLite, in Docker. It is never the
  source of truth: every "paid" is checked against a transaction it fetched from Solana itself.
- **`payer/`** A separate small service that holds the two keys (the SKR reward wallet and the
  fee wallet). The server can only ask it; it checks, caps and signs.

The tap carries **who and how much, never money**. The payment is an ordinary Solana transaction,
found afterwards on-chain by its Solana Pay `reference`, so the phones do not need to stay
together and there is nothing on the radio worth stealing.

## Set it up

Using an AI coding assistant? Point it at [AGENTS.md](AGENTS.md). It tells the assistant where
everything is and which things it must ask you for.

### 1. What you need

- macOS or Linux, **JDK 17**, the **Android SDK** (API 36) and `adb`
- **Docker** (for the server)
- **Python 3.10+** (only to run the server's tests outside Docker)
- An Android phone with NFC, Android 12 or newer, with a Solana wallet app installed
- Optional: `cloudflared`, to reach the server from outside your Wi-Fi

### 2. Things only you can supply

None of these are in the repository. The app builds without them; each one switches a feature on.

| What | Where it goes | What it switches on |
|---|---|---|
| Firebase project (Android app `so.duffle.app`) | `firebase.*` in `local.properties`, and the service-account JSON named by `FCM_KEY_FILE` in `server/.env` | Push notifications. **The server will not start in Docker without `FCM_KEY_FILE`.** |
| Bitrefill API key | `BITREFILL_API_KEY` in `.env` | Recharges, bills and gift cards |
| A Solana RPC URL (Helius or Alchemy) | `DUFFLE_MAINNET_RPC` in `.env` | Reliable chain reads. Public RPC works but is rate limited. |
| SKR reward wallet keypair | `secrets/payer.json`, and its public key as `PAYER_PUBKEY` in `server/.env` | Paying SKR rewards. Must hold SKR and a little SOL. |
| Fee wallet keypair | `secrets/sponsor.json`, and its public key as `SPONSOR_PUBKEY` in `server/.env` | Free and USDC-paid network fees. Must hold a little SOL (0.05 is plenty). |
| Pyth API key | `pyth.apiKey` in `local.properties` | Live token prices on Home |

`secrets/`, `.env` and `local.properties` are gitignored. Never commit them.

### 3. `local.properties` (repository root)

```properties
sdk.dir=/path/to/Android/sdk
# Where the app finds the server. Your computer's Wi-Fi address, or a public https address.
duffle.server=http://192.168.1.50:8787
rewards.enabled=true
# Optional
firebase.appId=
firebase.apiKey=
firebase.projectId=
firebase.senderId=
pyth.apiKey=
```

### 4. Start the server

```bash
./run.sh            # build and start the server, wait until healthy, seed the offers
./run.sh payer      # the same, plus the SKR payer and fee wallet (needs the keys from step 2)
./run.sh status     # health and containers
./run.sh logs       # follow the logs
./run.sh stop       # stop everything; data is kept
./run.sh test       # server and payer tests
```

### 5. Build and install the app

```bash
./gradlew :app:assembleBenchmark
adb install -r app/build/outputs/apk/benchmark/app-benchmark.apk
```

| Build | Use it for |
|---|---|
| `debug` | Development. Allows plain `http://` to a server on your Wi-Fi. |
| `benchmark` | Day-to-day testing on a real phone: release speed, debug signing, preview hooks on. |
| `share` | A build to hand to someone else. Points at a public address and is signed with your own key. |

### 6. One command for everything

```bash
./start.sh          # server + public tunnel + fresh APK on every connected phone
./start.sh stop
./fresh.sh          # start over as a new user: wipes the server database and the app's data
./share.sh          # server + public tunnel + a signed APK in dist/ to send to someone
```

The free tunnel gets a **new address every time it restarts**, and the app is built knowing the
server's address. If the app says it cannot load anything, the address has changed: run
`./start.sh` again.

## Tests

```bash
./gradlew :app:testDebugUnitTest      # app
./run.sh test                         # server and payer
```

## Good to know

- **It moves real money.** The app is on mainnet. Test with small amounts.
- **A wallet that just switched or disconnected has to authorize again.** Open Rewards and
  approve once in the wallet. Until then the server refuses its requests.
- **Free network fees need the fee wallet funded.** With no SOL in it, nothing is sponsored.
- **`demo_voucher`** is a server setting, off by default, that hands out a sample voucher card
  for demonstrations. Its code is made up and redeems nothing. Leave it off for real users.

## What this is not

- **Not tap-to-pay at a shop terminal.** That needs card network keys. This is phone to phone.
- **Not an iPhone app.** Apple restricts NFC card emulation to licensed payment providers.
- **Not a wallet.** It holds no keys and cannot move anyone's funds.

## More

- [AGENTS.md](AGENTS.md): a map of the code for AI assistants and new contributors
- [server/README.md](server/README.md): how the server finds and verifies a payment
- [payer/README.md](payer/README.md): the SKR payer, its limits and how to pause it
- [TESTING.md](TESTING.md): how to test on real phones
