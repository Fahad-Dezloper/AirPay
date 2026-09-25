# Section numbers

One number per section, the same in all three places: the app, the HTML sandbox, and the
source. Say "section 4, make the tiles taller" and nothing else needs describing.

To see the numbers **on the phone**, long-press the AirPay wordmark — the same switch that
shows diagnostics. They only exist in debug builds and never appear in a release.
In the **sandbox** they are always on, as small grey chips in the margin.

---

## Home

| § | Section | Kotlin | Sandbox |
|---|---|---|---|
| **1** | Top bar — avatar, wordmark, bell | `Header()` | `components/TopBar.js` |
| **2** | Balance — wallet row, total balance, card stack | `BalanceCard()` | `components/WalletSection.js` |
| **3** | People — the faces | `People()`, `Face()` | `components/People.js` |
| **4** | Offers and rewards — three tiles | `EarnTiles()`, `EarnTile()` | `components/Tiles.js` |
| **5** | Pay a bill — four tiles | `ShopTiles()` | `components/Tiles.js` |
| **6** | Transaction history — title, View all, rows | `HistoryStrip()`, `TxnRow()` | `components/History.js` |
| **7** | Pay bar — Pay via Tap, Scan any QR | `PayBar()`, `PayChoice()` | `components/PayBar.js` |

## Screens behind it

| § | Screen | Kotlin | How you get there |
|---|---|---|---|
| **8** | Thread — one person, everything between you | `Thread()`, `Bubble()`, `Say()` | tap a face in §3 |
| **9** | Notifications | `AlertsTab()` | the bell in §1 |
| **10** | Transactions — month by month | `HistoryTab()`, `MonthSheet()` | View all in §6 |
| **11** | Wallets — pick one, Done | `WalletsScreen()` | tap the stack in §2 |
| **12** | Shop — a shelf, then a product | `ShopSection()` | a tile in §5 |
| **13** | Earn — rewards, offers, referrals | `RewardsTab()` | a tile in §4 |
| **14** | Tap sheet — waiting, keypad, confirm, result | `PopupUi.kt` → `PopupScreen()` | Pay via Tap in §7 |
| **15** | Scanner | `ScanActivity.kt` | Scan any QR in §7 |

---

## Sub-parts

When one number is not enough, add a letter. These are the ones that come up:

- **2a** wallet row (icon + account name or address) · **2b** the balance figure ·
  **2c** the card stack
- **6a** the "Transaction history / View all" header · **6b** a row
- **14a** armed ("Touch both the mobiles") · **14b** the keypad · **14c** confirm ·
  **14d** waiting · **14e** the result

Anything not on this list, describe it once and it gets a number.
