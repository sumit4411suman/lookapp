# My Door Access — Phase 1 feasibility report

Status: **No door has been unlocked by this project yet.** Findings below were updated after testing on the owner's phone.
The build environment had no Android SDK and no access to zkteco.in, zkteco.com or GitHub reference repos, so
the APK was not compiled and vendor docs were not read.

## What is not yet known (needed from you)
1. ZKTeco model + firmware (Menu > System Info > Device Info, or the label on the back).
2. Card type: install the diagnostic app (this repo) and hold the Olympia card to the S25.
   - Card shown with ATQA/SAK -> 13.56 MHz. Nothing detected -> almost certainly 125 kHz.
3. Does the terminal have an Ethernet/RS485/Wiegand port, and is it wired to a lock directly or via a controller?
4. Is it standalone, or managed by ZKBioAccess / ZKBio Zlink?

## What follows from general NFC facts (not yet confirmed on your hardware)
- **125 kHz (EM/HID-prox style)**: a phone cannot read or emulate these. Phone-tap is impossible with this reader.
  Minimum alternative: add a 13.56 MHz or BLE reader/controller that accepts mobile credentials.
- **13.56 MHz MIFARE Classic / UID-only readers**: Android HCE cannot emulate MIFARE Classic, and HCE uses a
  random UID per tap, so a reader that only checks UID/sector keys will not accept the phone. Cloning the card
  would also be a bypass, which this project will not do.
- Phone-tap works only if the terminal supports a vendor mobile credential (e.g. ZKTeco's own mobile-access
  products over BLE/NFC/QR) on a model that lists it. This must be confirmed in ZKTeco documentation for your model.
- **In-app unlock**: requires a network (TCP/IP) or controller interface. ZKTeco's standalone PULL/Standalone SDK
  uses a LAN protocol (default port 4370) whose security is a comm key, not TLS; that conflicts with the
  "encrypted, no insecure fallback" requirement. Safer options: ZKBio Zlink / ZKBioAccess cloud or server with its
  official mobile API, or a separate relay/controller you control on an isolated LAN or VPN. Unverified - confirm in the SDK docs.
- Galaxy S25: NFC + HCE (Type A/B) are expected; the diagnostic app reports the actual feature flags.

## What exists in this repo
`app/`: a diagnostic Android app. It shows NFC/HCE feature flags and, when you tap a physical card, its UID,
technologies, ATQA/SAK and MIFARE type. It reads no data blocks and tries no keys. The Unlock button is
deliberately disabled; no unlock code exists because no authorized interface has been identified.

## Build
Open in Android Studio (Koala+), let it sync (AGP 8.5.2, Kotlin 1.9.24, JDK 17), Run on the S25 with USB debugging.

## Not done / not claimed
Release APK, HCE service, unlock integration, hardware tests (all 10 acceptance items), fingerprint/card regression.

## Next steps
Send: terminal model/firmware, the diagnostic output for the card, and the terminal's port list. Then Phase 2/3
can be chosen on facts (vendor mobile credential, official API, or a minimal hardware add-on).

## Findings from the owner's hardware (added)
- Phone: SM-S931B (Galaxy S25), Android 17. NFC, NFC enabled, HCE and HCE-F all reported true by the diagnostic app.
- Card: the Olympia card was not readable by the phone (all 13.56 MHz types polled). Conclusion: 125 kHz. A phone cannot read or
  emulate 125 kHz, so **tap-to-unlock with the existing X6 is not possible.** Not independently confirmed beyond that scan.
- Terminal (as reported by the owner): ZKTeco X6, standalone, BUT/GND exit input, NO/NC/COM relay, door-sensor input, no network.
- Chosen path: Option 1, a Wi-Fi relay across BUT/GND. See `docs/DESIGN.md`.
