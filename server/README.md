# AirPay server

The backend for AirPay's phone recharges. It signs people in by wallet signature, lists
countries, networks and plans, places each recharge with Cryptorefills (or Bitrefill for the
small ones), follows the order until it is delivered, failed or refunded, and tells the buyer's
phone by push.

**It never holds money.** The buyer pays the supplier's deposit address directly from their own
wallet. The app builds that USDC transfer itself and refuses an order whose amount is not the
plan it showed, so a wrong answer from this server cannot change what is signed.

## Run it

Python 3.10+, standard library plus `pynacl` (signature checks) and, for push, `google-auth`.

```sh
cd server
python3 -m duffleserver            # http://0.0.0.0:8787
python3 -m unittest discover -s tests
```

Or in Docker, from the repository root: `./run.sh` (see the top of that file).

Point a debug build of the app at it by adding to `local.properties`:

```
duffle.server=http://<your-lan-ip>:8787
```

Debug builds allow plain HTTP to reach a LAN server; release builds require HTTPS.

## Configuration

| Variable | Default | |
|---|---|---|
| `PORT`, `HOST` | `8787`, `0.0.0.0` | |
| `DUFFLE_DB` | `duffle.db` | SQLite file |
| `DUFFLE_RPC_URL` | public mainnet | A mainnet provider URL, for real rate limits |
| `CRYPTOREFILL_API`, `CRYPTOREFILL_API_2` | none | Cryptorefills keys; the second is a fallback |
| `BITREFILL_API_KEY` | none | Small recharges, and which network a number is on |
| `SUPPORT_SMTP_USER`, `SUPPORT_SMTP_PASSWORD`, `SUPPORT_TO` | none | Help requests by email |
| `FCM_PROJECT_ID`, `GOOGLE_APPLICATION_CREDENTIALS` | none | Push notifications |
| `DUFFLE_ADMIN_TOKEN` | none | Enables `GET /v1/admin/stats` |
| `TRUST_PROXY` | off | Set behind a proxy so each caller gets their own rate limit |
| `AUTH_DOMAIN` | `www.airpayy.com` | The site named in the sign-in message |

## Routes

| Route | |
|---|---|
| `GET /healthz` | What is switched on (never the values) |
| `POST /v1/auth/challenge`, `/verify`, `/link`, `/logout` | Sign-in by wallet signature |
| `GET /v1/me` | The signed-in wallet and its account's wallets |
| `GET /v1/topup/countries`, `/operators`, `/plans` | The catalogue |
| `POST /v1/topup/detect` | Which network a number is on (no sign-in needed) |
| `POST /v1/topup/orders`, `GET /v1/topup/orders[/<id>]` | Place a recharge, follow it |
| `POST /v1/topup/orders/<id>/cancel`, `/confirm`, `/support` | Cancel, confirm it arrived, ask for help |
| `POST /v1/rpc` | Solana RPC, limited to the calls one USDC payment needs |
| `POST /v1/devices` | Register the phone for push |

Every route but the catalogue, detection, sign-in and health needs a session, and an order is
only ever shown to the wallet that placed it.
