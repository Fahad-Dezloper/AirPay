"""python -m skrpayer — configuration is files and environment, never the command line.

Secrets are FILES (mounted read-only), not environment variables: `docker inspect` and process
listings show an environment, they do not show a mounted file's contents.
"""
from __future__ import annotations

import json
import logging
import os
import stat
import sys
import threading
import time

from nacl.signing import SigningKey

from . import solana as sol
from .engine import Engine, Limits
from .server import serve
from .sponsor import Sponsor

SKR_MINT = "SKRbvo6Gf7GondiT3BbTfuRDPqLWei4j2Qy2NPGZhW3"


def load_key(path: str) -> SigningKey:
    mode = os.stat(path).st_mode
    if mode & (stat.S_IRWXG | stat.S_IRWXO):
        logging.warning("key file %s is readable by others (mode %o); tighten it", path, mode & 0o777)
    raw = open(path).read().strip()
    data = bytes(json.loads(raw)) if raw.startswith("[") else sol.b58decode(raw)
    if len(data) != 64:
        raise SystemExit("key file must be a 64-byte Solana keypair (JSON array or base58)")
    key = SigningKey(data[:32])
    if bytes(key.verify_key) != data[32:]:
        raise SystemExit("key file is inconsistent: public half does not match the secret half")
    return key


def main() -> None:
    logging.basicConfig(level=os.environ.get("LOG_LEVEL", "INFO"), format="%(asctime)s %(name)s %(message)s")
    # On a host that can only pass secrets as variables (Railway): see secretfiles.py.
    from .secretfiles import materialize
    materialize({"PAYER_KEY_B64": ("PAYER_KEYFILE", "payer.json"),
                 "SPONSOR_KEY_B64": ("SPONSOR_KEYFILE", "sponsor.json"),
                 "PAYER_HMAC_B64": ("PAYER_HMAC_SECRET_FILE", "hmac")})
    env = os.environ.get
    key = load_key(env("PAYER_KEYFILE", "/secrets/payer.json"))
    pub = sol.b58encode(bytes(key.verify_key))
    expected = env("PAYER_PUBKEY")
    if not expected:
        # Required, not optional: it is the check that the right key is mounted before it can pay.
        raise SystemExit("set PAYER_PUBKEY to the payer wallet's public key")
    if expected != pub:
        # The one check that catches mounting the wrong key before it can pay anything.
        raise SystemExit(f"PAYER_PUBKEY {expected} does not match the key file ({pub})")
    secret = open(env("PAYER_HMAC_SECRET_FILE", "/secrets/hmac")).read().strip().encode()
    if len(secret) < 32:
        raise SystemExit("HMAC secret must be at least 32 characters")
    scale = 10 ** 6  # SKR has 6 decimals; limits below are whole SKR
    limits = Limits(
        per_payout=int(env("MAX_PAYOUT_SKR", "1000")) * scale,
        per_day=int(env("MAX_DAY_SKR", "10000")) * scale,
        per_recipient_day=int(env("MAX_RECIPIENT_DAY_SKR", "1500")) * scale,
        min_payout=int(env("MIN_PAYOUT_SKR", "1")) * scale,
    )
    rpc = sol.Rpc(env("PAYER_RPC_URL", "https://api.mainnet-beta.solana.com"))
    engine = Engine(env("PAYER_DB", "/data/payer.db"), key, rpc, env("SKR_MINT", SKR_MINT), limits,
                    pause_file=env("PAYER_PAUSE_FILE", "/data/PAUSED"))
    logging.getLogger("skrpayer").info("payer %s · mint %s · token program %s · source %s",
                                       pub, engine.mint, engine.token_program, engine.source)

    # The fee wallet is a SECOND key, never the SKR one: it signs transactions other people
    # wrote, so it holds a few cents of SOL and nothing else. No key file, no sponsoring.
    sponsor = None
    sponsor_file = env("SPONSOR_KEYFILE", "/secrets/sponsor.json")
    if os.path.isfile(sponsor_file) and os.path.getsize(sponsor_file) > 0:
        skey = load_key(sponsor_file)
        spub = sol.b58encode(bytes(skey.verify_key))
        if spub == pub:
            raise SystemExit("the fee wallet must not be the SKR payer wallet")
        if env("SPONSOR_PUBKEY") and env("SPONSOR_PUBKEY") != spub:
            raise SystemExit(f"SPONSOR_PUBKEY does not match the key file ({spub})")
        sponsor = Sponsor(env("SPONSOR_DB", "/data/sponsor.db"), skey, rpc,
                          fee_units=int(env("SPONSOR_FEE_USDC_UNITS", "10000")),
                          per_user_day=int(env("SPONSOR_MAX_USER_DAY", "20")),
                          per_day=int(env("SPONSOR_MAX_DAY", "2000")))
        logging.getLogger("skrpayer").info("fee wallet %s · fee account %s · ready %s",
                                           spub, sponsor.fee_account, sponsor.ready())

    def loop():
        while True:
            for pid in engine.pending():
                try:
                    engine.advance(pid)
                except Exception:  # noqa: BLE001 — one bad payout must not stop the others
                    logging.getLogger("skrpayer").exception("advance %s", pid)
            time.sleep(5)

    threading.Thread(target=loop, name="advance", daemon=True).start()
    serve(engine, secret, env("HOST", "0.0.0.0"), int(env("PORT", "8788")), sponsor=sponsor).serve_forever()


if __name__ == "__main__":
    sys.exit(main())
