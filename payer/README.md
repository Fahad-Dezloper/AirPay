# skr-payer

The only thing that holds the SKR key. The main server asks it to pay; it checks, caps and signs.

## Set up
1. Put the keypair JSON (64-byte array) at `tap/secrets/payer.json`. Never paste it anywhere else.
   `secrets/` is gitignored.
2. Put its PUBLIC key in `server/.env` as `PAYER_PUBKEY=...`. The payer refuses to start if the
   file does not match this key.
3. `secrets/payer_hmac` is the shared secret between server and payer (already generated).
4. Fund the payer wallet with SKR (what it pays) and a little SOL (fees, and opening new SKR
   accounts for recipients).
5. `cd server && docker compose --profile payer up -d --build`

## Limits (env, with defaults)
`MAX_PAYOUT_SKR=1000` per payout, `MAX_DAY_SKR=10000` per day, `MAX_RECIPIENT_DAY_SKR=1500`.
Create a file named `paused` in the payer data volume to stop all payouts at once.

## First real payout
Claim a tiny amount first and check the signature on Solscan before raising any cap.
