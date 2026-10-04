#!/usr/bin/env bash
# AirPay backend control.   ./run.sh [start|stop|restart|reset|logs|status|payer|test]
#   start    build + start the server, wait until healthy, seed the launch offers (safe to repeat)
#   stop     stop everything (data stays)
#   restart  stop, then start
#   reset    WIPE the database, then start fresh (asks first; backs the old one up)
#   logs     follow server logs
#   status   health + containers
#   payer    also start the SKR payer (needs secrets/payer.json and PAYER_PUBKEY in server/.env)
#   test     run server + payer tests
set -euo pipefail
SELF="$(cd "$(dirname "$0")" && pwd)/$(basename "$0")"
cd "$(dirname "$SELF")/server"
PY="${PY:-$HOME/.venvs/duffle/bin/python}"
dc() { docker compose "$@"; }

health() {
  dc exec -T duffle python -c "import urllib.request;print(urllib.request.urlopen('http://127.0.0.1:8787/healthz',timeout=4).read().decode())"
}

wait_healthy() {
  for _ in $(seq 1 40); do
    if health >/dev/null 2>&1; then return 0; fi
    sleep 1
  done
  echo "server did not become healthy; run ./run.sh logs" >&2; return 1
}

start() {
  dc up -d --build "$@"
  wait_healthy
  dc exec -T duffle python -m duffleserver.seed
  health
}

case "${1:-start}" in
  start)   start ;;
  payer)
    [ -f ../secrets/payer.json ] || { echo "missing secrets/payer.json" >&2; exit 1; }
    grep -q '^PAYER_PUBKEY=.' .env || { echo "set PAYER_PUBKEY in server/.env" >&2; exit 1; }
    # The fee wallet's key is optional, but the mount is not: without a file here Docker makes a
    # DIRECTORY of that name. An empty file means "no fee wallet", which the payer understands.
    [ -e ../secrets/sponsor.json ] || { : > ../secrets/sponsor.json; chmod 600 ../secrets/sponsor.json; }
    dc --profile payer up -d --build
    wait_healthy
    dc exec -T duffle python -m duffleserver.seed
    health ;;
  stop)    dc --profile payer down ;;
  restart) dc --profile payer down; start ;;
  reset)
    # WIPE=yes skips the question, for scripts (fresh.sh) that have already been asked.
    if [ "${WIPE:-}" != "yes" ]; then
      read -r -p "Wipe the server database? Type 'wipe' to confirm: " a
      [ "$a" = "wipe" ] || { echo "cancelled"; exit 1; }
    fi
    mkdir -p ../secrets/backup
    if dc ps -q duffle | grep -q .; then
      dc cp duffle:/data/duffle.db "../secrets/backup/duffle-$(date +%Y%m%d-%H%M%S).db" || true
    fi
    # Remember whether the payer was running, so it comes back up after the wipe.
    had_payer="$(dc --profile payer ps -q skr-payer 2>/dev/null || true)"
    dc --profile payer down
    docker volume rm server_duffle-data || true
    if [ -n "$had_payer" ] || [ "${WITH_PAYER:-}" = "1" ]; then "$SELF" payer; else start; fi ;;
  logs)    dc logs -f --tail 100 ;;
  status)  dc ps; health ;;
  test)
    (cd . && "$PY" -m unittest discover -s tests -q)
    (cd ../payer && "$PY" -m unittest discover -s tests -q) ;;
  *) sed -n 2,9p "$SELF"; exit 1 ;;
esac
