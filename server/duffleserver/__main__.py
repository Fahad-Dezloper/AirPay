"""python -m duffleserver — configuration is environment variables only; see README.md."""
from __future__ import annotations

import logging
import os
import threading
from pathlib import Path

from .chain import PUBLIC_MAINNET, Rpc
from .cryptorefills import Cryptorefills
from .notify import Fcm
from .service import Service
from .shop import Shop
from .store import Store
from .topups import TopupDesk
from .web import serve


def load_dotenv() -> None:
    """Read duffle/.env (or ./.env) into the environment without overriding real variables.
    Secrets stay in a gitignored file instead of on a command line, where `ps` would show them."""
    here = Path.cwd()
    # Both files, not the first one found. server/.env holds the path to the Firebase key and
    # duffle/.env holds everything else; stopping at the first meant a local run silently saw
    # none of the real secrets, because the small one happens to be nearer. setdefault keeps a
    # real environment variable winning over both, which is how Docker passes them in.
    for candidate in (here / ".env", here.parent / ".env"):
        if not candidate.is_file():
            continue
        for line in candidate.read_text().splitlines():
            line = line.strip()
            if line and not line.startswith("#") and "=" in line:
                key, value = line.split("=", 1)
                os.environ.setdefault(key.strip(), value.strip().strip("'\""))


def _host(url: str) -> str:
    """Only the host of an RPC address: providers put the API key in the path or the query, and
    the log is read by more people than the key should be."""
    from urllib.parse import urlparse
    return urlparse(url).netloc or "?"


def main() -> None:
    load_dotenv()
    # On a host that can only pass secrets as variables: see secretfiles.py.
    from .secretfiles import materialize
    materialize({"FCM_JSON_B64": ("GOOGLE_APPLICATION_CREDENTIALS", "fcm.json")})
    logging.basicConfig(level=os.environ.get("LOG_LEVEL", "INFO"), format="%(asctime)s %(name)s %(message)s")
    env = os.environ.get
    # Mainnet: the phones pay real USDC through this, and recharge deposit addresses are read
    # from it. DUFFLE_RPC_URL is the provider; the public endpoint is the fallback.
    rpc_url = env("DUFFLE_RPC_URL") or env("DUFFLE_MAINNET_RPC") or PUBLIC_MAINNET
    rpc = Rpc(rpc_url, fallback=PUBLIC_MAINNET)
    store = Store(env("DUFFLE_DB", "duffle.db"))
    service = Service(store, rpc, fcm=Fcm(env("FCM_PROJECT_ID"), env("GOOGLE_APPLICATION_CREDENTIALS")))
    stop = threading.Event()

    host, port = env("HOST", "0.0.0.0"), int(env("PORT", "8787"))
    # Bitrefill sells the small recharges Cryptorefills refuses (under its minimum). Its token
    # lives here rather than in the APK, where decompiling it would hand over the order history.
    shop = Shop(env("BITREFILL_API_KEY"), country=env("SHOP_COUNTRY", "IN"))

    def classify(address: str) -> str | None:
        """'owner' for a wallet (or an address with nothing in it yet), 'token_account' for a
        USDC account, None for anything else: the app is never left to guess."""
        info = rpc.call("getAccountInfo", [address, {"encoding": "jsonParsed"}]).get("value")
        if info is None or info.get("owner") == "11111111111111111111111111111111":
            return "owner"
        parsed = (info.get("data") or {}).get("parsed") if isinstance(info.get("data"), dict) else None
        if info.get("owner") == "TokenkegQfeZyiNwAJbNbGKPFXCWuBvf9Ss623VQ5DA" and parsed and \
                parsed.get("type") == "account" and \
                parsed.get("info", {}).get("mint") == "EPjFWdd5AufqSSqeM2qN1xzybapC8G4wEGGkZwyTDt1v":
            return "token_account"
        return None

    # Recharges: Cryptorefills, our partner, first. Two keys, so a revoked one fails over.
    topups = TopupDesk(store, Cryptorefills([env("CRYPTOREFILL_API"), env("CRYPTOREFILL_API_2")]),
                       deliver=service._deliver, classify=classify, shop=shop)
    # Help with a recharge, mailed to the support inbox (Gmail: an app password, never the login).
    from .support import Mailer, SupportDesk
    support = SupportDesk(store, topups, Mailer(env("SUPPORT_SMTP_USER"), env("SUPPORT_SMTP_PASSWORD"),
                                                to=env("SUPPORT_TO") or "airpayhq@gmail.com",
                                                host=env("SUPPORT_SMTP_HOST") or "smtp.gmail.com"))

    def follow_topups():
        # Once at start: correct any recent Bitrefill order read as delivered when it was not.
        try:
            if topups.recheck_bitrefill():
                logging.getLogger("duffle").warning("corrected Bitrefill orders shown as delivered")
        except Exception:  # noqa: BLE001
            logging.getLogger("duffle").exception("bitrefill recheck failed")
        while not stop.wait(10):
            try:
                topups.watch()
            except Exception:  # noqa: BLE001 — one bad answer must not stop the watching
                logging.getLogger("duffle.topups").exception("watch")
    threading.Thread(target=follow_topups, name="topups", daemon=True).start()
    httpd = serve(service, host, port, admin_token=env("DUFFLE_ADMIN_TOKEN"),
                  shop=shop, topups=topups, support=support)
    log = logging.getLogger("duffle")
    log.info("listening on %s:%d · rpc %s · push %s", host, port, _host(rpc_url),
             "on" if service.fcm.enabled else "off")
    log.info("recharges (cryptorefills) %s · small ones (bitrefill) %s",
             "on" if topups.cr.enabled else "off", "on" if shop.enabled else "off")
    log.info("support email %s", "on" if support.enabled else
             "off (set SUPPORT_SMTP_USER and SUPPORT_SMTP_PASSWORD; the app opens the mail app instead)")
    try:
        httpd.serve_forever()
    finally:
        stop.set()


if __name__ == "__main__":
    main()
