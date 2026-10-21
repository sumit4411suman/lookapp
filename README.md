# My Door Access

Android app (Samsung Galaxy S25) plus an ESP32 relay controller that triggers the exit-button input of a ZKTeco X6. See
`docs/DESIGN.md` for wiring, protocol and safety, and `FEASIBILITY.md` for why phone tap is not possible with this reader.

## Build the app
CI builds a debug APK on every push (GitHub Actions artifact `my-door-access-debug-apk`). Locally: Android Studio or
`gradle testDebugUnitTest assembleDebug` (JDK 17, Android SDK 35).

## Try it without the door
    tools/gen_secrets.sh x x 192.168.1.20       # prints pin + secret (ignore the generated firmware/relay/secrets.h)
    python3 tools/sim_selftest.py              # protocol self-test against the simulated relay
`tools/sim_relay.py` serves the same protocol on your PC, so the app can be tested with Test mode on and then off.
It drives no hardware.

## Real relay
Flash `firmware/relay/relay.ino` (Arduino ESP32 core + esp32_https_server) after running `tools/gen_secrets.sh <ssid> <password> <ip>`
with the controller's fixed IP. Enter the printed IP, pin and secret in the app's Setup section. Run Test connection first.

## Status
- Verified: app compiles and unit tests pass in CI (see Actions); protocol self-test against the simulator.
- Not verified: ESP32 firmware on hardware, relay on the real X6, any physical unlock.
