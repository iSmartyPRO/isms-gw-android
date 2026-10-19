# Changelog

All notable changes to this project are documented in this file.
The format follows [Keep a Changelog](https://keepachangelog.com/en/1.1.0/) and the project uses
[Semantic Versioning](https://semver.org/). The version lives in `version.properties`;
`versionCode = MAJOR*10000 + MINOR*100 + PATCH`.

Release notes on GitHub are generated from the section of this file that matches the release tag.

## [Unreleased]

### Added
- SIM memory joins the parts of long (concatenated) SMS into one message. Each message lists the SIM records it
  consists of (`parts`) and the number of parts it was sent in (`total`); incomplete messages are marked.

### Changed
- Deleting, copying, moving and syncing a long SMS from SIM memory now handles all of its parts at once
  (`DELETE /sims/{simId}/messages/{index}` accepts the index of any part).

## [1.2.0] - 2026-10-17

### Changed
- New app icon (adaptive, Android 13+ themed/monochrome, notification icon) and web panel logo.
- English is now the default language of the app and the web panel; Russian remains available via the flag switcher.
- `GET /sims` now includes the saved `balanceCode` of each SIM.
- SIM cards are shown on their plastic carrier — punch-out mini/micro/nano outline with the chip in the centre; new black (graphite) card color.
- The messages table turns into cards on phones.

### Added
- SIM memory: a second access path through the subscription's SmsManager (works on Samsung firmwares where the
  `content://sms/icc` provider is empty), diagnostics in the log, and **sync** — copy every SMS from the SIM to the
  phone, optionally clearing the SIM (app, web panel, `POST /api/v1/sims/{simId}/messages/sync`).
- **Destination filter** for outgoing SMS: blocklist of countries/ranges (Africa, satellite and premium presets), optional
  allowlist-only mode, short-code blocking, local-number normalisation (country code + trunk prefix) and a number checker.
  Enforced for every channel and re-checked before sending; blocked attempts go to the security log.
- Unit tests for the destination filter (run in CI).
- Screenshots gallery in the README.

### Fixed
- The *Phone permission* row in Settings now disappears immediately after the permission is granted.
- Several Android screens (Messages, Settings, welcome screen) still showed Russian text in English mode.
- The gateway notification is only updated when the notification permission is granted (Android 13+).

## [1.1.1] - 2026-10-17

### Fixed
- Declining an optional permission (such as *Phone*) no longer locks the app on the welcome screen; when a
  permission is permanently denied the system app settings are opened. Settings shows a row to grant it later.
- Restoring a backup that includes message history failed with *Request too large* (limit raised from 256 KB to 64 MB for restores).
- Refreshing the SIM registry could overwrite concurrent edits (label, daily limit, balance code).
- Race condition in the call log during click-to-call.
- Carrier non-delivery reports are now reflected in the phone's conversation as well.
- An invalid balance code now returns a clear error instead of being silently ignored.
- History cleanup no longer runs on the UI thread.
- Fewer system calls during bulk sending; faster cleanup and history import (index on message time).

## [1.1.0] - 2026-10-17

### Added
- **USSD** requests via a selected SIM with response history, a per-SIM *Balance* button,
  REST API (`POST /api/v1/ussd`), Telegram commands (`/ussd`, `/balance`), `ussd.result` webhook event
  and a dedicated `ussd` permission for users and API keys.
- **Calls:** log of incoming, outgoing and missed calls, missed/incoming call notifications in Telegram,
  `call.ringing`, `call.missed`, `call.ended` webhook events, click-to-call from the web panel and API
  (`POST /api/v1/calls`) via a selected SIM, and a `call` permission. Voice is not transmitted (Android limitation).
- **SIM memory:** view SMS stored on the SIM card, copy them to the phone, delete them (app, web panel, API).
- **Backup & restore:** export settings and optional history to a password-encrypted file
  (AES-256-GCM, PBKDF2) and restore it on the same or another phone.
- Retention periods for history, logs, USSD and calls with automatic cleanup.

### Security
- Telegram and SMS-server tokens, webhook secrets and the HTTPS keystore password are encrypted with an Android Keystore key.
- The webhook delivery queue and IP bans are stored in the database and survive restarts.

### Changed
- Outgoing messages appear in the phone's conversation immediately with a *sending* status;
  deleting a message in the web panel can also delete it from the phone.
- Database `ismsgw.db` upgraded to schema v3 (automatic migration).

## [1.0.0] - 2026-10-17

First public release.

### Added
- Default SMS app: conversations, notifications, reply, quick reply from incoming calls.
- Dual-SIM and eSIM support: SIM selection per message, routing strategies (default / fixed SIM / round-robin /
  least used), daily limits, per-SIM send and receive toggles. SIMs are identified by a stable subscription id.
- Send queue with delay, per-minute limit, retries and delivery reports; multi-part SMS.
- Local HTTP/HTTPS server with REST API v1 and a web admin panel.
- Webhooks signed with HMAC-SHA256 and automatic retries.
- Persistent WebSocket connection to your own SMS server with guaranteed delivery of incoming messages.
- Telegram bot: notifications about incoming SMS, failures and deliveries; commands (`/status`, `/send`, `/last`,
  `/queue`, `/sims`, `/server`); reply to an SMS by replying to the notification; chat pairing with a one-time code.
- Users and roles: an administrator and users restricted to selected SIMs with send / read / API / web permissions;
  API keys belong to users and can be limited to a subset of SIMs and IP ranges.
- Firewall: LAN only / whitelist / open, blacklist, CIDR, IPv6, rate limiting, automatic bans on brute force,
  admin panel restricted to the LAN.
- English and Russian UI with a flag switcher; light, dark and system themes.
- Autostart after reboot, CPU/Wi-Fi wake locks.

[Unreleased]: https://github.com/iSmartyPRO/isms-gw-android/compare/v1.2.0...HEAD
[1.2.0]: https://github.com/iSmartyPRO/isms-gw-android/releases/tag/v1.2.0
