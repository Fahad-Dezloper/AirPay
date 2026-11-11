"""python -m duffleserver — configuration is environment variables only; see README.md."""
from __future__ import annotations

import logging
import os
import threading
from pathlib import Path

from .chain import PUBLIC_MAINNET, Rpc
from .genesis import Chainfacts
from .helius import AddressSync
from .web import serve
from .notify import Fcm
from .service import Service
from .shop import Shop
from .store import Store
from .cryptorefills import Cryptorefills
from .topups import TopupDesk


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
    # On a host that can only pass secrets as variables (Railway): see secretfiles.py.
    from .secretfiles import materialize
    materialize({"FCM_JSON_B64": ("GOOGLE_APPLICATION_CREDENTIALS", "fcm.json"),
                 "PAYER_HMAC_B64": ("PAYER_HMAC_SECRET_FILE", "payer_hmac")})
    logging.basicConfig(level=os.environ.get("LOG_LEVEL", "INFO"), format="%(asctime)s %(name)s %(message)s")
    env = os.environ.get
    # One Helius key serves both the devnet RPC and the webhooks API.
    helius_key = env("HELIUS_API_KEY") or env("HELIUS_DEVNET_RPC_API_KEY")
    rpc_url = env("DUFFLE_RPC_URL") or (
        f"https://devnet.helius-rpc.com/?api-key={helius_key}" if helius_key else "https://api.devnet.solana.com"
    )
    # Genesis Tokens only exist on mainnet, so the reward check needs a second endpoint even
    # though payments run on devnet. Asking devnet would tell every Seeker owner they own nothing.
    mainnet_url = env("DUFFLE_MAINNET_RPC") or (
        f"https://mainnet.helius-rpc.com/?api-key={helius_key}" if helius_key else PUBLIC_MAINNET
    )
    store = Store(env("DUFFLE_DB", "duffle.db"))
    helius = AddressSync(
        store,
        api_key=helius_key,
        webhook_id=env("HELIUS_WEBHOOK_ID"),
        public_url=env("PUBLIC_URL"),
        webhook_type=env("HELIUS_WEBHOOK_TYPE", "rawDevnet"),
        auth=env("HELIUS_AUTH"),
    )
    rpc = Rpc(rpc_url)
    service = Service(
        store,
        rpc,
        fcm=Fcm(env("FCM_PROJECT_ID"), env("GOOGLE_APPLICATION_CREDENTIALS")),
        helius=helius,
        facts=Chainfacts(Rpc(mainnet_url, fallback=PUBLIC_MAINNET), rpc),
    )
    helius.start()
    stop = threading.Event()
    threading.Thread(target=service.run_watcher, args=(stop,), name="watcher", daemon=True).start()

    host, port = env("HOST", "0.0.0.0"), int(env("PORT", "8787"))
    # Set DUFFLE_ADMIN_TOKEN to be able to change the reward amounts while it runs; without
    # one, the numbers are read-only.
    # The Bitrefill token lives here rather than in the APK, where decompiling it would hand
    # over the account's order history.
    shop = Shop(env("BITREFILL_API_KEY"), country=env("SHOP_COUNTRY", "IN"))
    # CellUp's recharges: Cryptorefills, our partner (topups.py). Two keys, so a revoked one fails
    # over to the other; their go-live checklist asks for exactly that.
    mainnet = Rpc(mainnet_url, fallback=PUBLIC_MAINNET)

    def classify(address: str) -> str | None:
        """'owner' for a wallet (or an address with nothing in it yet), 'token_account' for a
        USDC account, None for anything else: the app is never left to guess."""
        info = mainnet.call("getAccountInfo", [address, {"encoding": "jsonParsed"}]).get("value")
        if info is None or info.get("owner") == "11111111111111111111111111111111":
            return "owner"
        parsed = (info.get("data") or {}).get("parsed") if isinstance(info.get("data"), dict) else None
        if info.get("owner") == "TokenkegQfeZyiNwAJbNbGKPFXCWuBvf9Ss623VQ5DA" and parsed and \
                parsed.get("type") == "account" and \
                parsed.get("info", {}).get("mint") == "EPjFWdd5AufqSSqeM2qN1xzybapC8G4wEGGkZwyTDt1v":
            return "token_account"
        return None
    # Bitrefill (the AirPay shop) sells the small recharges Cryptorefills refuses (under its
    # minimum); everything Cryptorefills can sell still goes to Cryptorefills.
    topups = TopupDesk(store, Cryptorefills([env("CRYPTOREFILL_API"), env("CRYPTOREFILL_API_2")]),
                       deliver=service._deliver, classify=classify, shop=shop)
    # Help with a recharge, mailed to the support inbox (Gmail: an app password, never the login).
    from .support import Mailer, SupportDesk
    support = SupportDesk(store, topups, Mailer(env("SUPPORT_SMTP_USER"), env("SUPPORT_SMTP_PASSWORD"),
                                                to=env("SUPPORT_TO") or "airpayhq@gmail.com",
                                                host=env("SUPPORT_SMTP_HOST") or "smtp.gmail.com"))

    def follow_topups():
        while not stop.wait(10):
            try:
                topups.watch()
            except Exception:  # noqa: BLE001 — one bad answer must not stop the watching
                logging.getLogger("duffle.topups").exception("watch")
    threading.Thread(target=follow_topups, name="topups", daemon=True).start()
    if not env("HELIUS_AUTH"):
        logging.getLogger("duffle").warning(
            "HELIUS_AUTH is not set: the webhook route accepts anyone. Payments are still verified"
            " on-chain, but set it so strangers cannot make the server do that work.")
    httpd = serve(service, host, port, env("HELIUS_AUTH"),
                  admin_token=env("DUFFLE_ADMIN_TOKEN"), shop=shop, topups=topups, support=support)
    logging.getLogger("duffle").info(
        "listening on %s:%d · rpc %s · genesis %s · push %s · helius webhook %s · shop %s", host, port,
        _host(rpc_url), _host(mainnet_url),
        "on" if service.fcm.enabled else "off", "on" if helius.enabled else "off (chain polling only)",
        "on" if shop.enabled else "off",
    )
    logging.getLogger("duffle").info("recharges (cryptorefills) %s · small ones (bitrefill) %s",
                                     "on" if topups.cr.enabled else "off", "on" if shop.enabled else "off")
    logging.getLogger("duffle").info("support email %s", "on" if support.enabled else
                                     "off (set SUPPORT_SMTP_USER and SUPPORT_SMTP_PASSWORD; the app opens the mail app instead)")
    try:
        httpd.serve_forever()
    finally:
        stop.set()


if __name__ == "__main__":
    main()
