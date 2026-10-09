#!/usr/bin/env bash
# Start over, end to end:  ./fresh.sh          (full: wipe database + app, as a new user)
#                          ./fresh.sh app      (only rebuild and reinstall the app; nothing wiped)
#   1. wipes the server database (the old one is backed up to secrets/backup/) and starts the server
#   2. points the app at this Mac's CURRENT address, builds it, installs it on the phone,
#      wipes the app's data and opens it
# The SKR payer is started too when secrets/payer.json and PAYER_PUBKEY are in place.
# Phone: ADB_SERIAL=<serial> ./fresh.sh  (default: the Seeker).
#
# Why step 2 rewrites the address every time: the app is built knowing where the server is,
# and the router hands this Mac a new address now and then. A build pointing at yesterday's
# address cannot reach anything — no bills, no sign-in, no rewards — and says only "couldn't load".
set -euo pipefail
cd "$(dirname "$0")"
MODE="${1:-full}"
SERIAL="${ADB_SERIAL:-SM02G4061995454}"
PKG=so.duffle.app
PORT=8787

say() { printf '\n\033[1m== %s\033[0m\n' "$*"; }

lan_ip() {
  local ifc
  ifc="$(route -n get default 2>/dev/null | awk '/interface:/{print $2}')"
  ipconfig getifaddr "$ifc" 2>/dev/null
}

if [ "$MODE" = "full" ]; then
  docker info >/dev/null 2>&1 || { echo "Docker is not running. Open Docker Desktop and run this again." >&2; exit 1; }

  say "1/3  Server: wipe the database and start"
  if [ -f secrets/payer.json ] && grep -q '^PAYER_PUBKEY=.' server/.env; then
    echo "payer key found: the SKR payer will start too"
    export WITH_PAYER=1
  else
    echo "no payer key set up yet: starting the server without the SKR payer"
  fi
  WIPE=yes ./run.sh reset
fi

say "App: point it at this Mac, build"
IP="$(lan_ip || true)"
[ -n "$IP" ] || { echo "Could not find this Mac's network address. Connect to Wi-Fi and run again." >&2; exit 1; }
URL="http://$IP:$PORT"
touch local.properties
if grep -q '^duffle.server=' local.properties; then
  [ "$(grep '^duffle.server=' local.properties)" = "duffle.server=$URL" ] || \
    sed -i '' "s#^duffle.server=.*#duffle.server=$URL#" local.properties
else
  printf '\nduffle.server=%s\n' "$URL" >> local.properties
fi
echo "the app will look for the server at $URL"
./gradlew :app:assembleBenchmark -q
APK=app/build/outputs/apk/benchmark/app-benchmark.apk

say "Phone: install, open"
if ! adb -s "$SERIAL" get-state >/dev/null 2>&1; then
  echo "The phone ($SERIAL) is not connected to adb. The app is built;" >&2
  echo "plug the phone in and run:  adb -s $SERIAL install -r $APK" >&2
  exit 1
fi
adb -s "$SERIAL" install -r "$APK" | tail -1
# Same file on the phone as on the Mac, or the install did not take.
phone="$(adb -s "$SERIAL" shell md5sum "$(adb -s "$SERIAL" shell pm path "$PKG" | head -1 | cut -d: -f2 | tr -d '\r')" | cut -c1-32)"
mac="$(md5 -q "$APK")"
[ "$phone" = "$mac" ] || { echo "Install check failed: the phone has a different build ($phone vs $mac)." >&2; exit 1; }
adb -s "$SERIAL" shell cmd package compile -m speed -f "$PKG" >/dev/null
if [ "$MODE" = "full" ]; then adb -s "$SERIAL" shell pm clear "$PKG" >/dev/null; fi
adb -s "$SERIAL" shell input keyevent KEYCODE_WAKEUP
adb -s "$SERIAL" shell am start -n "$PKG/.MainActivity" >/dev/null

# Can the phone actually reach the server? Say so now rather than leaving "couldn't load" to find.
# A ping first: a phone that has not spoken to the Mac lately sometimes fails its first lookup.
ping -c 3 -i 0.3 "$(adb -s "$SERIAL" shell "ip -f inet addr show wlan0 2>/dev/null; ip -f inet addr show wlan1 2>/dev/null" | awk '/inet /{sub("/.*","",$2); print $2; exit}')" >/dev/null 2>&1 || true
reach=""
for _ in 1 2 3; do
  reach="$(adb -s "$SERIAL" shell "printf 'GET /healthz HTTP/1.0\r\n\r\n' | nc -w 4 $IP $PORT 2>&1 | tail -1" | tr -d '\r')"
  case "$reach" in *'"ok"'*) break ;; esac
  sleep 1
done

say "Done"
echo "App installed (md5 ${mac:0:8}) and opened."
case "$reach" in
  *'"ok"'*) echo "The phone can reach the server at $URL." ;;
  *) echo "WARNING: the phone could NOT reach $URL ($reach)." >&2
     echo "Check that the phone and this Mac are on the same Wi-Fi, and that the server is running (./run.sh status)." >&2 ;;
esac
if [ "$MODE" = "full" ]; then echo "It is a new user: unlock the phone if it is locked, then tap Connect wallet."; fi
