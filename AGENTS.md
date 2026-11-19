# Guide for AI assistants

You are working on **AirPay**, an Android app for the Solana Seeker that recharges prepaid
phones in 140+ countries, paid in USDC. It has a small backend. Read this file before changing or setting up anything. The human-facing overview is in
[README.md](README.md).

## First, the rules

1. **This app moves real money on Solana mainnet.** Never press Pay, Proceed or Confirm on
   a phone, never approve a wallet prompt, and never send funds. Ask the user to do those.
2. **Never print, log or commit a secret.** That means `.env`, `server/.env`,
   `local.properties`, and everything in `secrets/`. You may read setting *names*; do not echo
   values.
3. **Ask, do not guess.** If something in "What to ask the user for" below is missing, stop and
   ask. Do not invent a key, a wallet address, a Firebase project or a server URL.
4. **Verify by running.** Tests and a real build, not a read of the code. Say plainly what you
   ran and what you did not.
5. **Do not wipe data without being asked.** `./run.sh reset` and `./fresh.sh` delete the
   server's database and the app's data.

## Setting it up for someone

Work through this in order. At each step, check first, then ask only for what is missing.

1. **Tools.** Check `java -version` (17), `adb version`, `docker info`, and that
   `local.properties` has `sdk.dir`. If one is missing, tell the user which and how to install it.
2. **Settings files.** Check which of these exist, and which setting names they contain:
   `local.properties`, `.env`, `server/.env`.
3. **Ask the user** for anything missing from the table below that they want switched on. Tell
   them what each one is for, and that the app still builds without the optional ones.
4. **Server.** `./run.sh`. Confirm with
   `./run.sh status`.
5. **Server address.** Set `duffle.server` in `local.properties` to an address the phone can
   reach: the computer's Wi-Fi address on the same network, or a public tunnel (`./share.sh`).
6. **App.** `./gradlew :app:assembleBenchmark`, then
   `adb install -r app/build/outputs/apk/benchmark/app-benchmark.apk`. Compare the installed
   APK's checksum with the build's; `adb install` has reported failure on a successful install.
7. **Hand over.** Tell the user what is switched on, what is not and why, and that connecting a
   wallet and authorizing are theirs to do on the phone.

### What to ask the user for

| Ask for | Needed for | Required? |
|---|---|---|
| Firebase service-account JSON, and its path as `FCM_KEY_FILE` in `server/.env` | Push notifications | **Yes, to start the server in Docker** |
| `firebase.appId`, `firebase.apiKey`, `firebase.projectId`, `firebase.senderId` in `local.properties` | Push on the phone | No |
| `CRYPTOREFILL_API` (and `CRYPTOREFILL_API_2`) in `.env` | Recharges | **Yes, for recharges** |
| `BITREFILL_API_KEY` in `.env` | Small recharges under Cryptorefills' minimum, and network detection | No |
| `DUFFLE_RPC_URL` in `.env` (a mainnet provider URL) | Reliable chain reads and sends | No; public RPC is the fallback |
| `SUPPORT_SMTP_USER`, `SUPPORT_SMTP_PASSWORD` in `.env` | Help requests by email | No; the app opens the mail app instead |
| Which phone, if more than one is connected (`adb devices`) | Installing | Yes, when ambiguous |

## Where everything is

```
app/                       The Android app (Kotlin, Jetpack Compose, one module)
  src/main/java/so/duffle/app/
  src/test/java/so/duffle/app/     JVM unit tests
server/duffleserver/       The backend (Python, standard library + SQLite)
server/tests/
run.sh  start.sh  share.sh  fresh.sh      Scripts; each says what it does at the top
secrets/  .env  server/.env  local.properties      Not in git. Never commit.
```

### The app: which file does what

| Area | Files |
|---|---|
| Every screen: number, plans, paying, order status, history, help | `CellUpActivity.kt` (large; search by function name) |
| Small pieces those screens are built from | `RechargeUi.kt`, `Skeleton.kt`, `ScreenState.kt` |
| Design tokens and shared components (buttons, sheets, type) | `Design.kt`, `Palette.kt`, `Icons.kt`, `Haptics.kt` |
| Countries, operators, plans and orders from the server; the price check before paying | `TopUp.kt`, `Plans.kt` |
| Phone numbers: parsing, flags, which network | `Phones.kt` |
| Building and sending the USDC payment | `Chain.kt`, `Addresses.kt` |
| Wallets: Mobile Wallet Adapter, linked wallets | `Wallet.kt`, `WalletLink.kt`, `Wallets.kt` |
| Talking to the server, signing in | `ServerClient.kt`, `Session.kt` |
| The email updates go to | `Identity.kt` |
| Push notifications | `Push.kt` |
| Usage counts | `Analytics.kt` |
| Money formatting (use this, never format by hand) | `Money.kt`, `Json.kt` |

### The server: which file does what

| File | What it does |
|---|---|
| `web.py` | Every HTTP route. Start here to find an endpoint. |
| `topups.py` | Recharges: catalogue, placing an order, following it to delivered, failed or refunded. |
| `cryptorefills.py` | The Cryptorefills client (most recharges). |
| `shop.py` | The Bitrefill client (small recharges, and which network a number is on). |
| `support.py` | Help with an order, mailed to the support inbox. |
| `service.py` | Sign-in and push, shared by every route. |
| `store.py` | SQLite: accounts, sessions, push devices. Recharges and tickets have their own tables. |
| `auth.py` | Sign-in by wallet signature, sessions. |
| `chain.py` | The Solana RPC proxy the phones pay through. |
| `notify.py` | Push (FCM). |
| `stats.py` | Counts for the admin stats view. |

## Things that will trip you up

- **Names.** "Duffle", "Tap" and "AirPay" are the same app at different times. The package is
  `so.duffle.app` (the code namespace); the published app id is `com.airpayy.app` from 1.1.0; the product is AirPay.
- **The app is built knowing the server's address** (`duffle.server`). If the computer's Wi-Fi
  address or the tunnel address changes, rebuild and reinstall, or nothing loads.
- **A 401 from the server means the wallet has not signed in**, not that the server is down.
  The app asks for the sign-in when it first needs it.
- **The app checks every order against the plan shown** (`TopUp.matchesPlan`) before opening the
  wallet. Never remove it: it is what stops a wrong server answer from charging more.
- **Money is shown through `Money.kt`**, cut and never rounded up.
- **No em dashes in text the user sees.** House style.

## Before you say you are done

```bash
./gradlew :app:testDebugUnitTest
./run.sh test
./gradlew :app:assembleBenchmark
```

Report what passed, what you could not test (anything needing a real payment or a wallet
approval), and anything you changed that the user did not ask for.

## Commits

Small, logical commits with a message that says why. Do not commit `secrets/`, `.env`,
`local.properties`, APKs or screenshots. Do not force push.
