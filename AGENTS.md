# Guide for AI assistants

You are working on **AirPay**, an Android payments app for the Solana Seeker, with a small
backend. Read this file before changing or setting up anything. The human-facing overview is in
[README.md](README.md).

## First, the rules

1. **This app moves real money on Solana mainnet.** Never press Pay, Proceed, Confirm or Swap on
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
   `local.properties`, `.env`, `server/.env`, `secrets/payer.json`, `secrets/sponsor.json`,
   `secrets/payer_hmac`.
3. **Ask the user** for anything missing from the table below that they want switched on. Tell
   them what each one is for, and that the app still builds without the optional ones.
4. **Server.** `./run.sh` (or `./run.sh payer` when both keys are in place). Confirm with
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
| `BITREFILL_API_KEY` in `.env` | Recharges, bills, gift cards | No; the shop is off without it |
| `DUFFLE_MAINNET_RPC` in `.env` | Reliable chain reads | No; public RPC is the fallback |
| SKR reward wallet: keypair at `secrets/payer.json`, public key as `PAYER_PUBKEY` in `server/.env` | Paying SKR rewards | Only for `./run.sh payer` |
| Fee wallet: keypair at `secrets/sponsor.json`, public key as `SPONSOR_PUBKEY` in `server/.env` | Sponsored network fees | No |
| `pyth.apiKey` in `local.properties` | Live prices | No |
| Which phone, if more than one is connected (`adb devices`) | Installing | Yes, when ambiguous |

Wallets must be **funded by the user**. Give them the public key and say how much; never ask for
or handle a private key in chat.

## Where everything is

```
app/                       The Android app (Kotlin, Jetpack Compose, one module)
  src/main/java/so/duffle/app/
  src/test/java/so/duffle/app/     JVM unit tests
server/duffleserver/       The backend (Python, standard library + SQLite)
server/tests/
payer/skrpayer/            The service that holds keys: SKR payouts and fee sponsoring
payer/tests/
run.sh  start.sh  share.sh  fresh.sh      Scripts; each says what it does at the top
secrets/  .env  server/.env  local.properties      Not in git. Never commit.
```

### The app: which file does what

| Area | Files |
|---|---|
| Home, navigation, purchase flow, most screens' state | `MainActivity.kt` (large; search by function name) |
| Design tokens and shared components (buttons, sheets, type) | `Design.kt`, `Palette.kt`, `Icons.kt` |
| First page before a wallet is connected | `Landing.kt` |
| Tap to pay: the sheet, its states, the pay and request flows | `TapPopupActivity.kt`, `PopupUi.kt`, `RequestActivity.kt` |
| NFC: acting as a card, reading a card, the bytes exchanged | `TapHostApduService.kt`, `TapReader.kt`, `TapExchange.kt`, `Wire.kt`, `Ndef.kt` |
| Building, submitting and confirming Solana transactions | `Chain.kt`, `Payments.kt`, `OfflineTx.kt` |
| Wallets: Mobile Wallet Adapter, linked wallets, switching | `Wallet.kt`, `WalletLink.kt`, `Wallets.kt`, `WalletsScreen.kt` |
| Pay anyone, names (`.skr`, `.sol`, `.air`), QR scanning | `PayAnyoneSheet.kt`, `Names.kt`, `Identity.kt`, `ScanActivity.kt`, `SolanaPay.kt`, `Qr.kt` |
| Shop: catalogue, product pages, orders, delivered codes | `Shop.kt`, `ShopScreen.kt`, `Orders.kt`, `Plans.kt`, `Vouchers.kt`, `CodeSheetScreen.kt` |
| Rewards, scratch cards, offers, referrals | `Rewards.kt`, `RewardsScreen.kt`, `ScratchStage.kt`, `OffersScreen.kt`, `Referrals.kt`, `ReferralsScreen.kt` |
| Buying SKR (Jupiter swap) | `Swap.kt` (API), `SwapSheet.kt` (the sheet, and the free-fees sheet) |
| Free network fees for SKR holders | `FreeGas.kt` (state), `Chain.kt` (`Sponsor`), `Payments.kt` |
| Seeker Genesis Token check | `Genesis.kt` |
| Talking to the server, signing in | `ServerClient.kt`, `Session.kt` |
| Notifications: system and in-app | `Push.kt`, `Alerts.kt`, `AlertsScreen.kt`, `Progress.kt` |
| History, messages with a person | `History.kt`, `Messages.kt`, `ThreadScreen.kt`, `Inbox.kt` |
| Prices and token lists | `Market.kt`, `Prices.kt`, `Pyth.kt`, `Tokens.kt`, `TokenCards.kt` |
| Money formatting (use this, never format by hand) | `Money.kt` |

### The server: which file does what

| File | What it does |
|---|---|
| `web.py` | Every HTTP route. Start here to find an endpoint. |
| `service.py` | The logic behind the routes: settling payments, orders, rewards. |
| `store.py` | SQLite: every table and query. |
| `auth.py` | Sign-in by wallet signature, sessions. |
| `chain.py`, `verify.py` | Reading Solana and proving a payment happened. |
| `shop.py`, `shop_guides.json`, `plans.py` | The Bitrefill catalogue, and the hand-written text for product pages. |
| `rewards.py`, `offers.py`, `stakes.py`, `swaps.py` | Reward rules and limits, offers, and on-chain proofs for them. |
| `payer_client.py` | The only way the server reaches the payer. |
| `notify.py` | Push and live events. |
| `seed.py` | The launch offers. Safe to run repeatedly. |

### The payer

| File | What it does |
|---|---|
| `engine.py` | SKR payouts: checks, caps, signs, records. |
| `sponsor.py` | The fee wallet. Read its header before touching it: it lists exactly which transactions it will sign and why. |
| `server.py` | Its small HTTP surface, reachable only by the server. |

## Things that will trip you up

- **Names.** "Duffle", "Tap" and "AirPay" are the same app at different times. The package is
  `so.duffle.app`; the product is AirPay.
- **The app is built knowing the server's address** (`duffle.server`). If the computer's Wi-Fi
  address or the tunnel address changes, rebuild and reinstall, or nothing loads.
- **A 401 from the server means the wallet has not authorized**, not that the server is down.
  The user authorizes in the app (it asks on the Rewards page).
- **`PREVIEWS` builds** (`debug`, `benchmark`) accept intent extras that show a screen without
  paying, for example `--ei preview_landing 0`, `--el preview_swapped 250000000`,
  `--ez preview_free_gas true`. They change what is drawn and never what is paid.
- **Reward settings live in the database**, table `reward_config`, and are read on every use.
  `demo_voucher` hands out a sample voucher card for demonstrations; keep it `0` for real users.
- **The fee wallet signs strangers' transactions**, so `sponsor.check()` is deliberately narrow.
  Never widen what it accepts without adding a test that attacks the new case.
- **A dotted name on a payment is refused unless this phone resolved it itself**
  (`PayRequest.validate(nameResolved = …)`). That is a safety rule, not a bug.
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
