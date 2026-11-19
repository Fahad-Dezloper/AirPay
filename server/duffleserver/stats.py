"""
How AirPay is doing, in counts, read straight from the server's own database.

    python -m duffleserver.stats                 # this folder's duffle.db, as a table
    python -m duffleserver.stats --db /data/duffle.db --json
    GET /v1/admin/stats   (Authorization: Bearer $DUFFLE_ADMIN_TOKEN)

Only counts and totals: no wallet address, name, code or amount tied to a person ever leaves
this module. It only reads. APK downloads come from GitHub's public release counts, the one
number the server cannot know by itself; that part is skipped when offline.
"""
from __future__ import annotations

import argparse
import json
import sqlite3
import sys
import time
import urllib.request

DAY = 86_400.0
REPO = "Fahad-Dezloper/AirPay"


def _one(db: sqlite3.Connection, sql: str, *args) -> int:
    try:
        row = db.execute(sql, args).fetchone()
    except sqlite3.OperationalError:
        return 0   # an older database without that table or column
    return int((row[0] if row else 0) or 0)


def _rows(db: sqlite3.Connection, sql: str, *args) -> list:
    try:
        return db.execute(sql, args).fetchall()
    except sqlite3.OperationalError:
        return []


def collect(db: sqlite3.Connection, now: float | None = None) -> dict:
    """Every count, as of [now]."""
    now = now or time.time()
    since = {"24h": now - DAY, "7d": now - 7 * DAY, "30d": now - 30 * DAY}

    def window(sql: str) -> dict:
        return {k: _one(db, sql, t) for k, t in since.items()}

    people = {
        "accounts": _one(db, "SELECT COUNT(*) FROM accounts"),
        "wallets": _one(db, "SELECT COUNT(*) FROM account_wallets"),
        "new_accounts": window("SELECT COUNT(*) FROM accounts WHERE created_at >= ?"),
        # Anyone who signed in, or whose session was renewed, in the window.
        "signed_in": window("SELECT COUNT(DISTINCT address) FROM sessions WHERE created_at >= ?"),
        "with_notifications": _one(db, "SELECT COUNT(DISTINCT recipient) FROM devices"),
    }
    done = "FROM topups WHERE state='delivered'"
    recharges = {
        "orders": _one(db, "SELECT COUNT(*) FROM topups"),
        "orders_window": window("SELECT COUNT(*) FROM topups WHERE created_at >= ?"),
        "delivered": _one(db, f"SELECT COUNT(*) {done}"),
        "buyers": _one(db, f"SELECT COUNT(DISTINCT buyer) {done}"),
        "usdc_delivered": round(float((_rows(db, f"SELECT COALESCE(SUM(CAST(usdc AS REAL)),0) {done}") or [[0]])[0][0] or 0), 2),
        "by_state": {s: n for s, n in _rows(db, "SELECT state, COUNT(*) FROM topups GROUP BY state ORDER BY 2 DESC")},
        "by_country": {c: n for c, n in _rows(db, f"SELECT country, COUNT(*) {done} GROUP BY country ORDER BY 2 DESC LIMIT 8")},
        "by_supplier": {c: n for c, n in _rows(db, "SELECT supplier, COUNT(*) FROM topups GROUP BY supplier")},
    }
    # The last two weeks, one row a day: who joined, how many recharges were placed and delivered.
    days = []
    for back in range(13, -1, -1):
        start = now - (back + 1) * DAY
        end = now - back * DAY
        days.append({
            "day": time.strftime("%Y-%m-%d", time.gmtime(end)),
            "new_accounts": _one(db, "SELECT COUNT(*) FROM accounts WHERE created_at >= ? AND created_at < ?", start, end),
            "orders": _one(db, "SELECT COUNT(*) FROM topups WHERE created_at >= ? AND created_at < ?", start, end),
            "delivered": _one(db, f"SELECT COUNT(*) {done} AND created_at >= ? AND created_at < ?", start, end),
        })
    return {"at": now, "people": people, "recharges": recharges, "days": days}


def downloads(repo: str = REPO, timeout: float = 8.0) -> dict | None:
    """APK downloads per release, from GitHub's public counts. None when it cannot be asked."""
    try:
        req = urllib.request.Request(f"https://api.github.com/repos/{repo}/releases",
                                     headers={"Accept": "application/vnd.github+json", "User-Agent": "airpay-stats"})
        with urllib.request.urlopen(req, timeout=timeout) as r:
            releases = json.load(r)
    except Exception:
        return None
    per = {rel.get("tag_name", "?"): sum(a.get("download_count", 0) for a in rel.get("assets", []) if a.get("name", "").endswith(".apk"))
           for rel in releases}
    return {"total": sum(per.values()), "by_release": per}


def _table(s: dict) -> str:
    p, rc = s["people"], s["recharges"]
    w = lambda d: f"{d['24h']:>5} today   {d['7d']:>5} this week   {d['30d']:>5} this month"
    lines = [f"AirPay stats   {time.strftime('%Y-%m-%d %H:%M UTC', time.gmtime(s['at']))}", ""]
    dl = s.get("downloads")
    lines += ["DOWNLOADS (APK, from GitHub)",
              f"  total            {dl['total']}   " + "  ".join(f"{k}: {v}" for k, v in dl["by_release"].items())
              if dl else "  could not reach GitHub", ""]
    lines += ["PEOPLE",
              f"  accounts         {p['accounts']}   ({p['wallets']} wallets linked)",
              f"  new accounts     {w(p['new_accounts'])}",
              f"  signed in        {w(p['signed_in'])}",
              f"  notifications on {p['with_notifications']}", ""]
    states = "  ".join(f"{k} {v}" for k, v in rc["by_state"].items()) or "none"
    countries = "  ".join(f"{k} {v}" for k, v in rc["by_country"].items()) or "none"
    lines += ["RECHARGES",
              f"  orders           {rc['orders']}   delivered {rc['delivered']}   buyers {rc['buyers']}   ${rc['usdc_delivered']:,.2f}",
              f"  orders in window {w(rc['orders_window'])}",
              f"  by state         {states}",
              f"  by country       {countries}", ""]
    lines += ["LAST 14 DAYS        new  orders  delivered"]
    lines += [f"  {d['day']}     {d['new_accounts']:>4}  {d['orders']:>6}  {d['delivered']:>9}" for d in s["days"]]
    return "\n".join(lines)


def main(argv: list[str] | None = None) -> int:
    ap = argparse.ArgumentParser(description=__doc__.split("\n")[1])
    ap.add_argument("--db", default="duffle.db")
    ap.add_argument("--json", action="store_true")
    ap.add_argument("--offline", action="store_true", help="skip GitHub download counts")
    a = ap.parse_args(argv)
    db = sqlite3.connect(f"file:{a.db}?mode=ro", uri=True)
    s = collect(db)
    s["downloads"] = None if a.offline else downloads()
    print(json.dumps(s, indent=2) if a.json else _table(s))
    return 0


if __name__ == "__main__":
    sys.exit(main())
