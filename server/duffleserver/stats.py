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
DELIVERED = ("all_delivered", "complete", "delivered")


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
        "air_names": _one(db, "SELECT COUNT(*) FROM handles"),
    }
    paid = "FROM requests WHERE status='paid'"
    payments = {
        "paid": _one(db, f"SELECT COUNT(*) {paid}"),
        "paid_window": window(f"SELECT COUNT(*) {paid} AND paid_at >= ?"),
        "by_tap": _one(db, f"SELECT COUNT(*) {paid} AND origin='tap'"),
        "people_who_paid": _one(db, f"SELECT COUNT(DISTINCT payer) {paid} AND payer IS NOT NULL"),
        "by_token": {s: n for s, n in _rows(db, f"SELECT symbol, COUNT(*) {paid} GROUP BY symbol ORDER BY 2 DESC")},
        "requests_open": _one(db, "SELECT COUNT(*) FROM requests WHERE status='open'"),
    }
    marks = ",".join("?" * len(DELIVERED))
    shop = {
        "orders": _one(db, "SELECT COUNT(*) FROM purchases"),
        "delivered": _one(db, f"SELECT COUNT(*) FROM purchases WHERE status IN ({marks})", *DELIVERED),
        "orders_window": window("SELECT COUNT(*) FROM purchases WHERE created_at >= ?"),
        "buyers": _one(db, "SELECT COUNT(DISTINCT buyer) FROM purchases WHERE buyer IS NOT NULL"),
        "usd_total": round(float(_rows(db, "SELECT COALESCE(SUM(usd),0) FROM purchases")[0][0] or 0), 2)
        if _rows(db, "SELECT 1 FROM purchases LIMIT 1") else 0.0,
        "by_category": {c or "other": n for c, n in _rows(
            db, "SELECT category, COUNT(*) FROM purchases GROUP BY category ORDER BY 2 DESC LIMIT 8")},
    }
    rewards = {
        "cards": _one(db, "SELECT COUNT(*) FROM rewards"),
        "skr_by_state": {s: int(v or 0) for s, v in _rows(db, "SELECT state, SUM(skr) FROM rewards GROUP BY state")},
        "offer_claims": _one(db, "SELECT COUNT(*) FROM claims"),
        "skr_paid_out": _one(db, "SELECT COALESCE(SUM(skr),0) FROM payouts WHERE state='confirmed'"),
        "referrals": _one(db, "SELECT COUNT(*) FROM referrals"),
        "referrals_paid": _one(db, "SELECT COUNT(*) FROM referrals WHERE paid_at IS NOT NULL"),
    }
    # The last two weeks, one row a day: who joined and how many payments landed.
    days = []
    for back in range(13, -1, -1):
        start = now - (back + 1) * DAY
        end = now - back * DAY
        days.append({
            "day": time.strftime("%Y-%m-%d", time.gmtime(end)),
            "new_accounts": _one(db, "SELECT COUNT(*) FROM accounts WHERE created_at >= ? AND created_at < ?", start, end),
            "payments": _one(db, f"SELECT COUNT(*) {paid} AND paid_at >= ? AND paid_at < ?", start, end),
            "orders": _one(db, "SELECT COUNT(*) FROM purchases WHERE created_at >= ? AND created_at < ?", start, end),
        })
    return {"at": now, "people": people, "payments": payments, "shop": shop, "rewards": rewards, "days": days}


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
    p, pay, shop, rw = s["people"], s["payments"], s["shop"], s["rewards"]
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
              f"  notifications on {p['with_notifications']}      .air names {p['air_names']}", ""]
    tokens = "  ".join(f"{k} {v}" for k, v in pay["by_token"].items()) or "none"
    lines += ["PAYMENTS",
              f"  paid             {pay['paid']}   by tap {pay['by_tap']}   by {pay['people_who_paid']} people",
              f"  paid in window   {w(pay['paid_window'])}",
              f"  tokens           {tokens}",
              f"  open requests    {pay['requests_open']}", ""]
    cats = "  ".join(f"{k} {v}" for k, v in shop["by_category"].items()) or "none"
    lines += ["SHOP",
              f"  orders           {shop['orders']}   delivered {shop['delivered']}   buyers {shop['buyers']}   ${shop['usd_total']:,.2f}",
              f"  orders in window {w(shop['orders_window'])}",
              f"  categories       {cats}", ""]
    st = "  ".join(f"{k} {v}" for k, v in rw["skr_by_state"].items()) or "none"
    lines += ["REWARDS",
              f"  scratch cards    {rw['cards']}   SKR by state: {st}",
              f"  offers claimed   {rw['offer_claims']}   SKR paid out {rw['skr_paid_out']}",
              f"  referrals        {rw['referrals']}   rewarded {rw['referrals_paid']}", ""]
    lines += ["LAST 14 DAYS        new  payments  orders"]
    lines += [f"  {d['day']}     {d['new_accounts']:>4}  {d['payments']:>8}  {d['orders']:>6}" for d in s["days"]]
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
