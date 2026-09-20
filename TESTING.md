# On-device tests still owed

Everything here needs two real phones and a human hand. It is deliberately batched: swapping
the one cable between the Seeker and the OnePlus costs more than the tests do, so these get run
in one sitting and the bugs fixed together.

**Rig reminders** (learned the hard way, don't relearn them):
- There is **one cable**. Only one phone is on adb at a time — plan each run so the phone on the
  cable is the one whose logs matter, and pull the other's afterwards.
- `adb logcat -G 16M` on **both** phones, `-c` to clear, run the test, then dump. Do not stream:
  the OnePlus drops off USB mid-session and kills the stream silently.
- Check every install with md5 against the local APK. `adb install` has reported success on a
  failure and failure on a success.
- **Both phones must run the same build** — wire v4 refuses an older one.
- Unlock the Seeker before testing: the HCE service declares `requireDeviceUnlock="true"`, so a
  locked phone serves nothing. The agent cannot enter the PIN.
- Don't screenshot mid-tap; it has been linked to reader mode being torn down.

## Never run at all

1. **A real two-phone tap on the current build.** Wire went 3 → 4 and the rewards layer landed;
   no genuine tap has happened since. This is the riskiest untested thing in the repo — nothing
   built on top of it is trustworthy until it passes.
2. **The OnePlus has an old build.** Only the Seeker has the rewards build. Install first
   (`http://192.168.1.34:8777/tap.apk` over wifi, or the cable), then verify the md5.
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

9. **Expiry agreement (wire v3 design).** Both phones sharing the receiver's blockhash, both
   polling every 4s, `EXPIRY_FLOOR_MS` 12s. Measured 0.59s apart once; never re-measured on v4.
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
