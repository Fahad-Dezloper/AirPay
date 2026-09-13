# Tap

**Two phones touch. Money moves.** No addresses, no QR codes, no copy-paste.

A native Android payments app for Solana Mobile, built for the Clock In hackathon.

## How it works

The tap carries **identity and intent — never money**. The payment itself is an ordinary Solana
transaction over the network, so there is nothing on the radio worth intercepting.

```
payee phone                              payer phone
(card / HCE)                             (reader mode)
     │                                        │
     │◄────────── SELECT F0536F6C616E61546170 │
     │                                        │
     │ {recipient, amount, token, nonce} ────►│
     │                                        │  confirm + biometric
     │◄───────── {payer, nonce echoed} ───────│
     │                                        │
     └──────── transaction submitted to Solana ────────►
```

The one-time nonce must be echoed back, so a recording of one tap cannot be replayed as a
second payment.

## Two tiers

| Pair | Experience |
|---|---|
| **Seeker ↔ Seeker** | Genesis Token verifies genuine hardware. "Verified Seeker" badge. |
| **Seeker ↔ any NFC Android** | Works fully. Wallet chosen via Mobile Wallet Adapter. |
| Seeker → iPhone | One-way only, via an NDEF Solana Pay link. |

Seeker hardware makes it better; it is not required to participate.

## Why NFC, and why nobody has done it

Verified by probe on a physical Seeker:

- NFC, HCE, HCE-F and an embedded secure element are all present and enabled
- The NFC routing table was using **95 of 1030 bytes** before this app — every registered
  card-emulation service on the device belonged to Google
- **Backpack, SP3ND, Jupiter and Solflare all request `android.permission.NFC`. None of them
  ships card emulation.**

The capability has been claimed by the biggest wallets on the platform and built by none of them.

## Status

**Phase 1 complete — two phones exchange bytes on a tap, on real hardware.**

Captured from a Solana Seeker acting as the card, read by a OnePlus CPH2401:

```
← 00A404000A F0536F6C616E61546170 00      ISO 7816 SELECT, over the air
SELECT ok → serving 132B request           payment request served
```

- [x] HCE service registered; AID live in the NFC routing table
- [x] Reader mode with platform NDEF handling and sounds disabled
- [x] Wire format with replay protection
- [x] **Two devices exchanging a payload end to end**
- [ ] Mobile Wallet Adapter, real USDC transfer
- [ ] Genesis Token verification
- [ ] Pairing animation

## Build

```bash
./gradlew :app:assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

Install on **both** phones. One taps *Request*, the other taps *Pay*, then hold them together.

## What this is not

- **Not tap-to-pay at a shop terminal.** That needs issuer keys and network tokenization.
  Phone to phone only.
- **Not an iPhone app.** Apple's NFC/SE entitlement requires a commercial agreement and, as of
  2026, being a financial institution or working with a licensed PSP.
