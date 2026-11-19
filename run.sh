#!/usr/bin/env bash
# AirPay backend control.   ./run.sh [start|stop|restart|reset|logs|status|test]
#   start    build + start the server, wait until healthy
#   stop     stop it (data stays)
#   restart  stop, then start
#   reset    WIPE the database, then start fresh (asks first; backs the old one up)
#   logs     follow server logs
#   status   health + containers
#   test     run the server tests
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
  health
}

case "${1:-start}" in
  start)   start ;;
  stop)    dc down ;;
  restart) dc down; start ;;
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
    dc down
    docker volume rm server_duffle-data || true
    start ;;
  logs)    dc logs -f --tail 100 ;;
  status)  dc ps; health ;;
  test)    "$PY" -m unittest discover -s tests -q ;;
  *) sed -n 2,9p "$SELF"; exit 1 ;;
esac
