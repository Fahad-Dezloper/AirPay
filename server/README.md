# Duffle server

Optional backend for Duffle. It watches the chain for payment requests and tells the payee the
moment one is paid — live over SSE while the app is open, by push notification while it is
closed. The app works without it; the server makes confirmation instant and reachable.

**It is never the source of truth.** Every "paid" is verified from a transaction fetched through
the server's own RPC: the request's reference must be present, the transaction must have
succeeded, and the recipient's balance must have risen by at least the amount asked. Webhooks
are hints. The phone re-verifies on-chain again before showing ✓ Paid.

## Run it

Python 3.10+, standard library only.

```sh
cd server
python3 -m duffleserver            # http://0.0.0.0:8787, chain polling, no push
python3 -m unittest discover -s tests -t .
```

Point a debug build of the app at it by adding to `duffle/local.properties`:

```
duffle.server=http://<your-lan-ip>:8787
```

Debug builds allow plain HTTP to reach a LAN server; release builds require HTTPS.

## Configuration

| Variable | Default | |
|---|---|---|
| `PORT`, `HOST` | `8787`, `0.0.0.0` | |
| `DUFFLE_DB` | `duffle.db` | SQLite file |
| `DUFFLE_RPC_URL` | `https://api.devnet.solana.com` | Use a Helius devnet RPC URL for real rate limits |
| `PUBLIC_URL` | — | The server's public https URL, for the webhook |
| `HELIUS_API_KEY` | — | Enables webhook address sync |
| `HELIUS_WEBHOOK_ID` | — | From `setup_helius_webhook.py` |
| `HELIUS_AUTH` | — | Shared secret; Helius sends it back as `Authorization` |
| `HELIUS_WEBHOOK_TYPE` | `rawDevnet` | `raw` for mainnet |
| `FCM_PROJECT_ID` | — | Enables push |
| `GOOGLE_APPLICATION_CREDENTIALS` | — | Firebase service-account JSON (needs `google-auth[requests]`) |

## How a payment is found

1. **Registration.** Both phones register the request: the payee when its phone serves it, the
   payer when it reads it. The first registration fixes the recipient and amount; a later one
   with different details is refused (409). A repeat registration is a nudge to check now.
2. **Polling.** Each open request is checked with `getSignaturesForAddress(reference)` — every
   second while young, backing off to hourly. Nothing expires.
3. **Webhook (optional).** Helius watches recipient addresses and posts matching transactions.
   The server takes only the signature from it, fetches the transaction itself, and settles any
   open request it genuinely pays. Duplicate deliveries are ignored.
4. **Settlement.** A signature can settle one request only. The payee is notified over SSE and
   FCM.

## Enabling Helius

```sh
HELIUS_API_KEY=… PUBLIC_URL=https://your-server HELIUS_AUTH=$(openssl rand -hex 24) \
  python3 setup_helius_webhook.py
# → HELIUS_WEBHOOK_ID=…   set it, with the same HELIUS_AUTH, on the server
```

## Enabling push

1. Create a Firebase project and add an Android app with package `com.tapsol.tap`.
2. Put its values in `duffle/local.properties`: `firebase.appId`, `firebase.apiKey`,
   `firebase.projectId`, `firebase.senderId`. No `google-services.json` is needed.
3. Download a service-account key (Project settings → Service accounts). Running directly, set
   `FCM_PROJECT_ID` and `GOOGLE_APPLICATION_CREDENTIALS`. With Docker, put `FCM_PROJECT_ID` in
   `duffle/.env` and `FCM_KEY_FILE=<path to the key>` in `server/.env`; the key is mounted read-only,
   never copied into the image.

## Docker

```sh
cd server
docker compose up -d --build     # restarts on its own; database kept in a volume
docker compose logs -f
```

## API

| | |
|---|---|
| `POST /v1/requests` | `{reference, recipient, amount, label, payer?, genesis?}` → `{status, signature}`. With `payer`, the request is delivered to that address (SSE + push) — this is "Request" from the tap popup |
| `POST /v1/taps` | `{to, address, label, genesis?}` — relays an introduction when the phones parted before NFC HELLO crossed |
| `GET /v1/requests/{reference}` | `{status, signature, amount}` |
| `POST /v1/devices` | `{recipient, push_token}` |
| `GET /v1/events?recipient=` | SSE with `event: paid`, `event: request` and `event: tap`; on connect, replays the last 10 minutes of each; heartbeat every 15s |
| `POST /v1/helius` | Webhook receiver; 403 without the shared secret |
| `GET /healthz` | Counts and which integrations are on |

## Known limits

- **Anyone can address a request or an introduction to any wallet.** They are only
  notifications — nothing is paid without the recipient opening their wallet and approving —
  but they can be spam. Writes are rate-limited per IP; there is no sender authentication yet.
