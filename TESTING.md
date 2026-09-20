# On-device tests still owed

Everything here needs two real phones and a human hand. It is deliberately batched: swapping
the one cable between the Seeker and the OnePlus costs more than the tests do, so these get run
in one sitting and the bugs fixed together.

**Rig reminders** (learned the hard way, don't relearn them):
- **Both phones can be on adb at once** (2026-09-21: Seeker `usb:0-1.1`, OnePlus `usb:0-1.2`).
  This replaces the long-standing one-cable rule — both sides of a tap can be watched live, so
  a test no longer has to be planned around which phone's logs matter. If only one cable is
  available again, fall back to: put the phone whose logs matter on it, pull the other's after.
- `adb logcat -G 16M` on **both** phones, `-c` to clear, run the test, then dump. Do not stream:
  the OnePlus drops off USB mid-session and kills the stream silently.
- Check every install with md5 against the local APK. `adb install` has reported success on a
  failure and failure on a success.
- **Both phones must run the same build** — wire v4 refuses an older one.
- Unlock the Seeker before testing: the HCE service declares `requireDeviceUnlock="true"`, so a
  locked phone serves nothing. The agent cannot enter the PIN.
- Don't screenshot mid-tap; it has been linked to reader mode being torn down.

## Run on 2026-09-20 — results

The first real two-phone session. **The tap works on wire v4**: two genuine NFC taps, both
`IsoDep, NfcA`, `maxTransceive=65279B`, request received and read in about 290ms, "you can
separate the phones now" straight after.

| | |
|---|---|
| Tap over NFC | ✅ twice |
| Payment end to end | ✅ `221qSFVu…`, 0.010025 SOL, confirmed on devnet |
| Genesis badge | ✅ "Verified Seeker — genesis 8eYAaFEy…" |
| Contacts | ✅ "known contact CPH2401" |
| Decline | ✅ server logged the rejection to the asking phone |
| Reward rules | ✅ refused, correctly: 0.01 SOL is under the 0.05 minimum |
| Offer | ✅ refused for the same reason — it wants 0.05 |
| **Warning before paying** | ❌ **bug, fixed** — see below |

**The bug.** The "won't earn" line was only asked for when the review screen opened. Going from
"Pay" to "Confirm" in about a second cancelled the request before it left the phone, so a fast
tapper never saw it — which is the one case it exists for. It now asks as soon as the amount is
known, and for a request it shows on the first screen, under the amount.

### Second tap, 0.05 SOL — the offer path

| | |
|---|---|
| Tap + payment | ✅ `125DKtKz…`, 0.05 SOL, confirmed |
| Warning before paying | ✅ **"New wallet · this payment won't earn SKR" on the first screen**, before Pay — the fix works |
| Tap reward | ✅ refused `new_counterparty` — the OnePlus wallet was 6.7 days old against a 7-day rule, correct to the hour |
| **Offer** | ✅ **paid 150 SKR**, 32 seconds after the payment landed |
| Balance shown | ❌ **bug, fixed** — said 300, should have been 450 |

**The bug.** Offer payouts were written to the claims table only, so they were missing from the
balance the app shows *and* never drew down the weekly pool — which quietly broke the promise
that an unfunded campaign costs nothing beyond the budget. Offer payouts now go into the same
ledger as tap rewards (`kind="offer"`), so the balance, the pool, the caps and the admin ledger
all see them. `kind` keeps them out of the weekly payment count and the streak, which are about
paying people, not claiming offers.

The already-paid claim was re-opened so the real code path settled it again rather than
hand-editing a number: balance 450, pool 2400 → 1950.

### The Rewards tab on a phone that is not a Seeker

| | |
|---|---|
| Reads correctly | ✅ 0 SKR, 0 of 4, 0 of 500, pool shown — no error, no blank |
| Claim refused | ✅ "Only a Seeker can claim this" |
| Offering Claim at all | ❌ **fixed** — it showed a green Claim button to a wallet that can never claim |

Same mistake as the earn-warning: told after the act instead of before, and answerable on the
spot because the app already knows whether the wallet holds Genesis. It now reads "Seeker only
— this wallet holds no Genesis Token" in place of the button, and the headline says the same
rather than dangling "a payment by tapping earns 50 SKR" at someone who cannot earn it.

### USDC over a tap

✅ Works — token list showed the real holdings, `transferChecked` paid 1 USDC over a tap.

❌ **Bug, fixed: the payer's own phone was notified of its own request.** Paying 1 USDC from
the Seeker raised "Seeker requests 1 USDC" **on the Seeker**. Two mistakes in one call:

- the paying phone registers the request before paying (so the server watches it, knows who to
  tell on a withdrawal, and can attribute the reward) — and the server delivered that request
  back to the payer, because it cannot tell who is calling. There is now a `deliver` flag: the
  payer says "I already have this". The relay after the phones part still delivers.
- the label sent was `Identity.label(us)` — our own name — instead of the payee's, which is why
  the notification read "Seeker requests" on the Seeker.

Still owed from this session: push to a closed app. (Expiry is gone — see item 9.)

## Run on 2026-09-21 — "Later", and the only expiry there is

| | |
|---|---|
| Later reaches the asker | ✅ `POST /v1/deferrals` → push on the OnePlus 1.8s later |
| The waiting sheet shows it | ✅ after a fix — it only ever checked "paid" and "rejected", so a
  deferral left it silent even though the notification had already arrived |
| Decline still separate | ✅ |
| **Wallet expiry: nothing was sent** | ✅ wallet returned after **72,000ms**, no `SENT`, and **no
  transaction on-chain** from that attempt — the claim in the error message is literally true |
| Retry after expiry | ✅ fresh blockhash, 13s, paid first time |
| USDC over a tap | ✅ twice |

**Still not tested, despite the session being called done:** paying a request that was deferred.
Both deferrals (`F3ro5c6K…`, `3H8tt4YN…`, 0.5 USDC each) are still `open` and unpaid; the two
payments that landed were *new* references from fresh taps. "A deferred request is still
payable" is the whole difference between Later and Decline and remains unproven. Test it from
the Seeker's **Requests** tab — no tap needed.

**Also still not tested:** push to a genuinely closed app. Every push in this session arrived
with the popup open. Swipe Duffle out of recents on the asker first.

## New, never run on the phones (added 2026-09-21)

13. **"Later" is now an answer.** The payer taps Later → the asker's row shows **PENDING ·
    "<who> will pay later"** instead of silence, and gets a notification if the app is closed.
    The request stays open and payable, so paying afterwards must still work normally.
    Needs **both** phones on the new build: the payer sends it, the asker renders it.
14. **The waiting clock ticks.** Replaced the scale-pulse with a drawn clock whose second hand
    jumps once a second and settles. Verified moving on the Seeker; worth a glance in a real
    wait to check it does not distract from the amount.

## Never run at all

1. ~~A real two-phone tap on the current build.~~ **Passed 2026-09-20** (see above).
2. ~~The OnePlus has an old build.~~ **Installed and md5-verified 2026-09-20.** It is one build
   behind again after the warning fix; update it at the next cable swap (payer-side change only,
   so it does not affect it as the payee).
3. **A token request between the phones.** USDC/EURC over the tap path. A token *payment* was
   verified on-chain earlier (Seeker 20 → 19 USDC, OnePlus account created), but not through a
   tapped request on this build.

## Rewards (R1/R3) — what a real tap must show

4. **One tap earns exactly one set of rows.** Partly proven today, but by an accidental payment
   from the review screen rather than a tap: reference `wmwSFDSu…` wrote `first_payment` 250 +
   `per_payment` 50, locked. Redo it as a genuine tap.
5. **A second payment to the same person inside 30 days earns nothing.** Verified only through
   the preview endpoint (`counterparty_cooldown`), never by a second settled payment.
6. **"New wallet · this payment won't earn SKR" on a genuinely new wallet.** Seen on the review
   screen while the OnePlus wallet was under the age line. Worth seeing once more against a
   freshly made wallet, since after 2026-09-20 19:32 UTC the OnePlus no longer triggers it.
7. **The earn moment on a real payment.** "You earned N SKR" has only been seen via
   `--es preview earned`, and once on a real payment that was approved by hand.
8. **The Rewards tab on the OnePlus** — i.e. on a phone that is *not* a Seeker. It must show
   nothing earned and the reason, not an error.

## Older behaviour never re-run since it was built

9. ~~Expiry agreement (wire v3 design).~~ **Not a test any more — wire v4 removed expiry.**
   A request never expires; only a transaction has a clock, and it starts when the payer
   presses Pay (a fresh blockhash, ~40-60s for the wallet to return). The old shared-deadline
   design left the payer about 28 seconds to read, decide and approve, which is what was
   actually costing payments. Nothing to observe here; don't re-add this test.
10. **Push to a closed app.** FCM under the new package `so.duffle.app` — the Firebase Android
    app was recreated after the rename.
11. **Contacts and the Genesis badge on a real tap** — "✓ Verified Seeker" on the confirm sheet,
    and a contact row written for the other phone.
12. **The queue**: two taps in a row producing two AWAITING PAYMENT rows, not a `6985` refusal.

## Known-bad things to watch for during the run

- The popup **resumes on the screen it was left on**. Reopening it with `am start` while a sheet
  is already up does not reset it — this is how a scripted tap hit "Confirm and pay" and opened
  the wallet for a real payment on 2026-09-20. Force-stop the app between scripted runs.
- `origin="tap"` is still the phone's claim (R2 corroborates it, but does not refuse on it).
