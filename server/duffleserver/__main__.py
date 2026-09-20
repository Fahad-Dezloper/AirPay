"""python -m duffleserver — configuration is environment variables only; see README.md."""
from __future__ import annotations

import logging
import os
import threading
from pathlib import Path

from .chain import Rpc
from .helius import AddressSync
from .web import serve
from .notify import Fcm
from .service import Service
from .store import Store


def load_dotenv() -> None:
    """Read duffle/.env (or ./.env) into the environment without overriding real variables.
    Secrets stay in a gitignored file instead of on a command line, where `ps` would show them."""
    here = Path.cwd()
    for candidate in (here / ".env", here.parent / ".env"):
        if not candidate.is_file():
            continue
        for line in candidate.read_text().splitlines():
            line = line.strip()
            if line and not line.startswith("#") and "=" in line:
                key, value = line.split("=", 1)
                os.environ.setdefault(key.strip(), value.strip().strip("'\""))
        return


def main() -> None:
    load_dotenv()
    logging.basicConfig(level=os.environ.get("LOG_LEVEL", "INFO"), format="%(asctime)s %(name)s %(message)s")
    env = os.environ.get
    # One Helius key serves both the devnet RPC and the webhooks API.
    helius_key = env("HELIUS_API_KEY") or env("HELIUS_DEVNET_RPC_API_KEY")
    rpc_url = env("DUFFLE_RPC_URL") or (
        f"https://devnet.helius-rpc.com/?api-key={helius_key}" if helius_key else "https://api.devnet.solana.com"
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
    service = Service(
        store,
        Rpc(rpc_url),
        fcm=Fcm(env("FCM_PROJECT_ID"), env("GOOGLE_APPLICATION_CREDENTIALS")),
        helius=helius,
    )
    helius.start()
    stop = threading.Event()
    threading.Thread(target=service.run_watcher, args=(stop,), name="watcher", daemon=True).start()

    host, port = env("HOST", "0.0.0.0"), int(env("PORT", "8787"))
    # Set DUFFLE_ADMIN_TOKEN to be able to change the reward amounts while it runs; without
    # one, the numbers are read-only.
    httpd = serve(service, host, port, env("HELIUS_AUTH"), admin_token=env("DUFFLE_ADMIN_TOKEN"))
    logging.getLogger("duffle").info(
        "listening on %s:%d · rpc %s · push %s · helius webhook %s", host, port,
        rpc_url.split("?")[0],
        "on" if service.fcm.enabled else "off", "on" if helius.enabled else "off (chain polling only)",
    )
    try:
        httpd.serve_forever()
    finally:
        stop.set()


if __name__ == "__main__":
    main()
