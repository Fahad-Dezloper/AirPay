#!/usr/bin/env bash
# Everything up, and the app on your phone, in one go:   ./start.sh
#                                         stop it all:   ./start.sh stop
#
# What "start" does, in order:
#   1. starts the AirPay server, opens the public tunnel and builds a fresh APK that points at it
#      (this is ./share.sh, unchanged)
#   2. starts the SKR payer too, when its key is set up
#   3. installs that APK on every phone connected by USB, keeping the app's data, and opens it
#
# One phone only:  ADB_SERIAL=<serial> ./start.sh     (see `adb devices` for serials)
#
# The tunnel gets a NEW address whenever it is restarted, so a build made by an earlier run stops
# working. That is why this always builds and reinstalls: the app on the phone and the address the
# server is on are made together.
set -euo pipefail
cd "$(dirname "$0")"
PKG=so.duffle.app
URLF=/tmp/airpay-tunnel.url
PIDF=/tmp/airpay-tunnel.pid

say() { printf '\n\033[1m== %s\033[0m\n' "$*"; }

if [ "${1:-start}" = "stop" ]; then
  say "Stopping the tunnel and the server (data is kept)"
  [ -f "$PIDF" ] && kill "$(cat "$PIDF")" 2>/dev/null || true
  pkill -f "cloudflared tunnel" 2>/dev/null || true
  (cd server && docker compose --profile payer stop)
  echo "stopped. The app on the phone cannot reach the server until you run ./start.sh again."
  exit 0
fi

docker info >/dev/null 2>&1 || { echo "Docker is not running. Open Docker Desktop and run this again." >&2; exit 1; }

say "A. Server, tunnel and a fresh APK"
./share.sh
URL="$(cat "$URLF")"

say "B. SKR payer"
if [ -f secrets/payer.json ] && grep -q '^PAYER_PUBKEY=.' server/.env; then
  # The same address as above, or the server would be restarted without knowing its own.
  TRUST_PROXY=1 PUBLIC_URL="$URL" ./run.sh payer | tail -1
  echo "payer started"
else
  echo "no payer key set up (secrets/payer.json and PAYER_PUBKEY): rewards will not pay out"
fi

say "C. Phones"
if [ -n "${ADB_SERIAL:-}" ]; then
  PHONES="$ADB_SERIAL"
else
  PHONES="$(adb devices | awk 'NR>1 && $2=="device"{print $1}')"
fi
if [ -z "$PHONES" ]; then
  echo "No phone is connected. The APK is ready at dist/AirPay.apk."
  echo "Plug a phone in, allow USB debugging, and run ./start.sh again."
  exit 0
fi
for phone in $PHONES; do
  name="$(adb -s "$phone" shell getprop ro.product.model 2>/dev/null | tr -d '\r')"
  echo "-- $name ($phone)"
  out="$(adb -s "$phone" install -r dist/AirPay.apk 2>&1 || true)"
  if echo "$out" | grep -q "Success"; then
    echo "   installed, data kept"
  elif echo "$out" | grep -q "INSTALL_FAILED_UPDATE_INCOMPATIBLE"; then
    # A build signed with another key is on the phone. Replacing it means removing it, which
    # deletes its data, so that is left to you rather than done silently.
    echo "   NOT installed: the phone has a build signed with a different key."
    echo "   To replace it (this wipes the app's data on that phone):"
    echo "     adb -s $phone uninstall $PKG && adb -s $phone install dist/AirPay.apk"
    continue
  else
    echo "   NOT installed: $(echo "$out" | tail -1)"
    continue
  fi
  adb -s "$phone" shell am force-stop "$PKG" >/dev/null 2>&1 || true
  adb -s "$phone" shell am start -n "$PKG/.MainActivity" >/dev/null 2>&1 && echo "   opened (unlock the phone if the screen is off)"
done

say "Done"
echo "server: $URL"
echo "Keep this Mac awake, online and Docker running. Stop everything with ./start.sh stop"
