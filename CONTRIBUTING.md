# Contributing to iSMS GW

Thanks for your interest in improving iSMS GW!

## Development setup

1. Install **JDK 17** and the **Android SDK 36** (Android Studio is the easiest way).
2. Clone the repository and build: `./gradlew assembleDebug`.
3. Install on a device with a SIM card: `adb install -r app/build/outputs/apk/debug/*.apk`
   and set iSMS GW as the default SMS app. SMS features cannot be tested on an emulator without a modem.

## Guidelines

- **Kotlin** code follows the official Kotlin style; keep functions small and match the surrounding code.
- **UI strings** are bilingual and written inline: `t("Русский текст", "English text")` in Kotlin and
  `T('Русский текст', 'English text')` in the web panel. Always provide both.
- The **web panel** is dependency-free vanilla JS served under a strict Content-Security-Policy:
  no inline scripts or event handlers, no external resources, and every value from the server must go through `esc()`.
- **Security first:** never weaken the firewall, authentication or escaping; never log message texts or secrets.
- Update **`CHANGELOG.md`** under `[Unreleased]` for every user-visible change.

## Pull requests

- Create a branch from `main`, keep PRs focused, and describe how you tested the change on a real device.
- CI must be green (`.github/workflows/ci.yml`).

## Releases

Maintainers release with `./scripts/release.sh X.Y.Z` — see [docs/RELEASING.md](docs/RELEASING.md).
