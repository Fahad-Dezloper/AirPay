"""
One-time: create the Helius devnet webhook that points at this server.

    HELIUS_API_KEY=… PUBLIC_URL=https://your-server HELIUS_AUTH=<random secret> \
        python3 setup_helius_webhook.py

Prints the webhook id; set it as HELIUS_WEBHOOK_ID on the server, which from then on keeps the
webhook's address list equal to the set of Duffle recipients by itself.
"""
from __future__ import annotations

import json
import os
import sys
import urllib.error
import urllib.request

from duffleserver.helius import API, webhook_body


def main() -> None:
    key, url, auth = os.environ.get("HELIUS_API_KEY"), os.environ.get("PUBLIC_URL"), os.environ.get("HELIUS_AUTH")
    missing = [n for n, v in [("HELIUS_API_KEY", key), ("PUBLIC_URL", url), ("HELIUS_AUTH", auth)] if not v]
    if missing:
        sys.exit(f"set {', '.join(missing)} first")
    if not url.startswith("https://"):
        sys.exit("PUBLIC_URL must be https — Helius has to reach it from the internet")
    # Helius requires at least one address; the server replaces the list on its first sync.
    placeholder = os.environ.get("SEED_ADDRESS", "11111111111111111111111111111111")
    body = json.dumps(webhook_body(url, [placeholder], os.environ.get("HELIUS_WEBHOOK_TYPE", "rawDevnet"), auth)).encode()
    req = urllib.request.Request(f"{API}?api-key={key}", body, {"Content-Type": "application/json"}, method="POST")
    try:
        out = json.loads(urllib.request.urlopen(req, timeout=20).read())
    except urllib.error.HTTPError as e:
        sys.exit(f"Helius refused: HTTP {e.code} {e.read().decode()[:300]}")
    print("HELIUS_WEBHOOK_ID=" + str(out.get("webhookID")))


if __name__ == "__main__":
    main()
