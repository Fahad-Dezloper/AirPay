#!/usr/bin/env bash
# Build an APK for a friend and make the server reachable from the internet:  ./share.sh
#   1. makes (once) a signing key for friend builds, kept in secrets/ (never committed)
#   2. opens a free Cloudflare tunnel so the server has a public https address
#   3. restarts the server aware of that address (your data is kept)
#   4. builds a release-style APK that talks to that address, and checks it
# The APK lands in dist/AirPay.apk. Send that file.
#
# IMPORTANT — the address is only good while the tunnel stays up. A free tunnel gets a NEW address
# every time it is restarted, which strands every APK built for the old one. Keep this Mac awake,
# online and running Docker while your friend uses the app; if the tunnel ever restarts, run
# ./share.sh again and send the new APK. (The script keeps the Mac awake for you while it runs.)
set -euo pipefail
cd "$(dirname "$0")"
PORT=8787
PIDF=/tmp/airpay-tunnel.pid
URLF=/tmp/airpay-tunnel.url
LOG=/tmp/airpay-tunnel.log

say() { printf '\n\033[1m== %s\033[0m\n' "$*"; }
healthy() { python3 - "$1" <<'PY'
import sys, urllib.request
try:
    r = urllib.request.urlopen(sys.argv[1].rstrip("/") + "/healthz", timeout=8)
    sys.exit(0 if b'"ok"' in r.read() else 1)
except Exception:
    sys.exit(1)
PY
}

docker info >/dev/null 2>&1 || { echo "Docker is not running. Open Docker Desktop and run this again." >&2; exit 1; }
command -v cloudflared >/dev/null || { echo "cloudflared is not installed (brew install cloudflared)." >&2; exit 1; }

say "1/4  Signing key for friend builds"
touch local.properties
if ! grep -q '^share.storeFile=' local.properties; then
  mkdir -p secrets
  pass="$(openssl rand -hex 16)"
  keytool -genkeypair -keystore secrets/airpay-share.jks -alias airpay -keyalg RSA -keysize 2048 \
    -validity 10000 -storepass "$pass" -keypass "$pass" -dname "CN=AirPay, O=AirPay" >/dev/null 2>&1
  printf '\nshare.storeFile=secrets/airpay-share.jks\nshare.keyAlias=airpay\nshare.storePassword=%s\nshare.keyPassword=%s\n' \
    "$pass" "$pass" >> local.properties
  echo "created secrets/airpay-share.jks — back it up: a build signed with a different key cannot update this one"
else
  echo "already set up"
fi

say "2/4  Public address (Cloudflare tunnel)"
URL=""
if [ -f "$PIDF" ] && kill -0 "$(cat "$PIDF")" 2>/dev/null && [ -f "$URLF" ] && healthy "$(cat "$URLF")"; then
  URL="$(cat "$URLF")"
  echo "reusing the running tunnel"
else
  [ -f "$PIDF" ] && kill "$(cat "$PIDF")" 2>/dev/null || true
  : > "$LOG"
  nohup cloudflared tunnel --no-autoupdate --url "http://localhost:$PORT" > "$LOG" 2>&1 &
  echo $! > "$PIDF"
  for _ in $(seq 1 40); do
    URL="$(grep -oE 'https://[a-z0-9-]+\.trycloudflare\.com' "$LOG" | head -1 || true)"
    [ -n "$URL" ] && break
    sleep 1
  done
  [ -n "$URL" ] || { echo "The tunnel did not come up. See $LOG" >&2; exit 1; }
  echo "$URL" > "$URLF"
  # Keep the Mac awake for as long as the tunnel lives.
  nohup caffeinate -dimsu -w "$(cat "$PIDF")" >/dev/null 2>&1 &
fi
echo "public address: $URL"

say "3/4  Server (restarted so it knows its public address; data kept)"
TRUST_PROXY=1 PUBLIC_URL="$URL" ./run.sh start | tail -2
ok=""
for _ in $(seq 1 30); do healthy "$URL" && { ok=1; break; }; sleep 2; done
[ -n "$ok" ] || { echo "The server does not answer through the tunnel yet. See $LOG" >&2; exit 1; }
echo "answers through the tunnel"

say "4/4  Build the APK"
./gradlew :app:assembleShare -q -Pshare.server="$URL" 2>&1 | grep -v "CXX1101" || true
[ -f app/build/outputs/apk/share/app-share.apk ] || { echo "The build failed." >&2; exit 1; }
APK=app/build/outputs/apk/share/app-share.apk
mkdir -p dist
cp "$APK" dist/AirPay.apk

# Check what is about to be handed to somebody else.
BT="$(ls -d "$HOME"/Library/Android/sdk/build-tools/* | sort -V | tail -1)"
echo "signature:    $("$BT/apksigner" verify --print-certs dist/AirPay.apk 2>&1 | grep -m1 'Signer #1 certificate DN' || echo 'NOT SIGNED')"
if "$BT/aapt" dump badging dist/AirPay.apk 2>/dev/null | grep -q 'application-debuggable'; then
  echo "DEBUGGABLE build — refusing to share it" >&2; exit 1
fi
echo "debuggable:   no"
# What goes out must point at the public address, not this Mac's Wi-Fi one, and must not carry
# secrets. Read with Python: `strings | grep -q` under pipefail reports a miss on a hit.
python3 - "$URL" dist/AirPay.apk <<'PY'
import re, sys, zipfile
url, apk = sys.argv[1], sys.argv[2]
z = zipfile.ZipFile(apk)
blob = b"".join(z.read(n) for n in z.namelist() if n.startswith("classes") and n.endswith(".dex"))
props = open("local.properties").read()
bad = []
if url.replace("https://", "").encode() not in blob: bad.append("the public address is not in the APK")
if b"192.168." in blob: bad.append("a home-network address is in the APK")
for key in ("pyth.apiKey", "share.storePassword", "share.keyPassword"):
    m = re.search(r"^" + re.escape(key) + r"=(.*)$", props, re.M)
    if m and m.group(1).strip() and m.group(1).strip().encode() in blob: bad.append(key + " is in the APK")
if bad:
    sys.exit("REFUSING to share: " + "; ".join(bad))
print("server inside: " + url + "  (no home address, no secrets)")
PY

say "Done"
echo "APK:    $(pwd)/dist/AirPay.apk  ($(du -h dist/AirPay.apk | cut -f1))"
echo "sha256: $(shasum -a 256 dist/AirPay.apk | cut -d' ' -f1)"
echo "server: $URL   (keep this Mac awake, online, and Docker running)"
echo
echo "Send dist/AirPay.apk. On their Android phone: open it, allow 'install unknown apps' when asked."
