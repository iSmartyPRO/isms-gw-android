<div align="center">

<img src="branding/icon-rounded.svg" width="112" alt="iSMS GW logo">

# iSMS GW — Android SMS & USSD Gateway

**Turn any Android phone into a secure, self-hosted SMS & USSD gateway.**<br>
Dual-SIM & eSIM · REST API · Web admin panel · Webhooks · Telegram bot · Multi-user · No cloud required

[![Release](https://img.shields.io/github/v/release/iSmartyPRO/isms-gw-android?sort=semver&color=00BEF1)](https://github.com/iSmartyPRO/isms-gw-android/releases/latest)
[![Build](https://img.shields.io/github/actions/workflow/status/iSmartyPRO/isms-gw-android/ci.yml?branch=main&label=build)](https://github.com/iSmartyPRO/isms-gw-android/actions/workflows/ci.yml)
![Android](https://img.shields.io/badge/Android-8.0%2B-3DDC84?logo=android&logoColor=white)
![Kotlin](https://img.shields.io/badge/Kotlin-2.0-7F52FF?logo=kotlin&logoColor=white)
![Target SDK](https://img.shields.io/badge/targetSdk-36-0B0F1A)

[Download APK](https://github.com/iSmartyPRO/isms-gw-android/releases/latest) ·
[Features](#features) ·
[Quick start](#quick-start) ·
[API](#rest-api) ·
[Security](#security) ·
[Releases](#releasing)

</div>

---

## Why iSMS GW?

Most SMS gateways either lock you into a paid cloud or expose your phone to the internet behind a single static token.
**iSMS GW** runs entirely on your device: it is a full default SMS app with a built-in gateway that you control
through a polished web panel, a REST API, webhooks, a Telegram bot, or a persistent connection to your own server.
Messages never pass through the developer — they only go where *you* send them.

## Features

| | |
|---|---|
| 📨 **Full SMS app** | Conversations, notifications, quick reply, multi-part SMS, delivery reports — a proper default SMS handler (required by Google Play). |
| 📶 **Dual-SIM & eSIM** | Every SIM / eSIM profile is tracked by a stable subscription id, so permissions and settings survive slot swaps. Per-SIM labels, colors, send/receive toggles and daily limits. Removed SIMs stay listed as *inactive*. |
| 🧠 **Smart routing** | Pick a SIM per message, or a strategy: system default, fixed SIM, round-robin, least used today. Queue with throttling, per-minute limit and retries to avoid carrier bans. |
| 🌐 **REST API** | Send SMS, track status, poll and acknowledge incoming messages, run USSD, place calls, read SIM memory. |
| 🖥️ **Web admin panel** | Fast, dependency-free SPA served by the phone. Light / dark / system themes, English & Russian. Configure *everything* from your desktop. |
| 🔔 **Webhooks** | `sms.received`, `sms.sent`, `sms.delivered`, `sms.failed`, `ussd.result`, `call.*` — HMAC-SHA256 signed, persistent retry queue that survives reboots. |
| 🤖 **Telegram bot** | Incoming SMS and missed calls in Telegram, reply to an SMS by replying to the message, `/send`, `/ussd`, `/balance`, `/status`… Paired with a one-time code. |
| 🔌 **Persistent server link** | The phone keeps a WebSocket connection to your server — works behind NAT and on mobile data, no public IP needed. Incoming SMS are re-delivered until acknowledged. |
| #️⃣ **USSD** | Single-step USSD requests (balance, data left, own number) per SIM, with history and a one-tap *Balance* button. |
| 📞 **Calls** | Incoming / missed call log and notifications, click-to-call from the web panel or API (voice stays on the phone). |
| 💾 **SIM memory** | View, copy to phone and delete SMS stored on the SIM card. |
| 👥 **Users & roles** | Administrator plus users restricted to specific SIMs and permissions (send, read, API, web, USSD, calls). Every user owns their API keys. |
| 🗄️ **Backup & restore** | Encrypted backup file (AES-256-GCM, PBKDF2) with settings, users, keys and optional history — migrate to another phone in minutes. |

## Security

Security is a first-class feature, not an afterthought:

- **Firewall before authentication** — *LAN only* (default), *whitelist only* or *open*; blacklist, IPv4/IPv6 CIDR rules, separate restrictions for the admin panel.
- **Brute-force protection** — automatic IP bans after repeated failures, per-IP rate limiting, login delay; bans persist across restarts.
- **Hardened web panel** — PBKDF2-SHA256 passwords, HttpOnly + SameSite=Strict sessions bound to the client IP, CSRF header, strict CSP with no inline scripts, every SMS rendered as escaped text.
- **API keys** are shown once and stored as SHA-256 hashes; scoped (`send`, `read`, `ussd`, `call`), limited to the owner's SIMs and optional IP ranges.
- **Secrets at rest** (bot tokens, server tokens, webhook secrets, TLS keystore password) are encrypted with a non-exportable **Android Keystore** AES-256 key.
- **HTTPS** with a certificate generated on the device; its SHA-256 fingerprint is shown in the app for verification.
- No analytics, no ads, no developer cloud.

## Quick start

1. **Install** the APK from the [latest release](https://github.com/iSmartyPRO/isms-gw-android/releases/latest).
2. **Set iSMS GW as the default SMS app** and grant the permissions on the welcome screen.
3. Tap the **power button** on the *Gateway* tab. The panel address (e.g. `http://192.168.1.15:8080`) and a temporary admin password appear.
4. Open the address in a desktop browser, sign in, **change the password** and create an **API key**.
5. Send your first SMS:

```bash
curl -X POST http://192.168.1.15:8080/api/v1/sms \
  -H "Authorization: Bearer sg_YOUR_API_KEY" \
  -H "Content-Type: application/json" \
  -d '{"to": ["+15551234567"], "text": "Hello from iSMS GW"}'
```

## Integration modes

All modes can run at the same time and are enabled independently.

| Mode | How it works | Best for |
|---|---|---|
| **Local REST API** | The phone runs an HTTP(S) server on your network | Apps on the same LAN or VPN |
| **Webhooks** | The phone POSTs signed events to your URL | CRMs, helpdesks, bots, ERPs |
| **Persistent link** | The phone keeps a WebSocket to your server and receives commands | Phones behind NAT / on mobile data, device fleets |
| **Polling** | `GET /api/v1/sms?unacked=1` + `POST /api/v1/sms/ack` | When you cannot receive webhooks |
| **Telegram bot** | Notifications and commands in your private chat | Owners and small teams |

> **Coming next:** **iSMS GW Server** — a self-hosted hub that connects many phones and SIM cards behind a single API and dashboard.

## REST API

Base URL: `http(s)://<phone-ip>:<port>/api/v1` · Auth: `Authorization: Bearer <api-key>` · JSON (UTF-8)

| Method | Endpoint | Scope | Description |
|---|---|---|---|
| `POST` | `/sms` | `send` | Send SMS to one or many recipients (`to`, `text`, optional `simId` / `sim`, `ref`) |
| `GET` | `/sms/{id}` | `read` | Message status: `queued → sending → sent → delivered \| failed` |
| `GET` | `/sms?direction=in&unacked=1` | `read` | Poll messages (`direction`, `status`, `since_id`, `unacked`, `limit`) |
| `POST` | `/sms/ack` | `read` | Acknowledge received messages `{ "ids": [...] }` |
| `GET` | `/sims` | `read` | SIM cards and eSIM profiles available to the key |
| `GET` | `/sims/{simId}/messages` | `read` | SMS stored in SIM memory |
| `DELETE` | `/sims/{simId}/messages/{index}` | `send` | Delete an SMS from SIM memory |
| `POST` | `/ussd` | `ussd` | Run a USSD request `{ "code": "*100#", "simId": 1 }` |
| `GET` | `/ussd/{id}` | `ussd` | USSD result |
| `GET` / `POST` | `/calls` | `call` | Call log / click-to-call `{ "to": "+1555…", "simId": 1 }` |
| `GET` | `/status` | `read` | Device, battery, SIMs and statistics |

The full documentation with examples is built into the web panel under **API docs**.

### Webhook signature

```
X-ISMS-Event: sms.received
X-ISMS-Timestamp: 1760000000
X-ISMS-Signature: sha256=HMAC_SHA256(secret, timestamp + "." + body)
```

A reference WebSocket server for the persistent-link mode lives in [`examples/sms_server.py`](examples/sms_server.py).

## Requirements

- Android **8.0 (API 26)** or newer, at least one active SIM or eSIM.
- iSMS GW must be the **default SMS app** (Android and Google Play policy for SMS access).
- For reliable 24/7 operation keep the phone charging and exclude iSMS GW from battery optimization (on Samsung also from *Sleeping apps*).

## Project structure

```
.
├── app/                     Android application module
│   └── src/main/
│       ├── java/app/simgate/
│       │   ├── data/        Config, SQLite database, crypto, i18n, users, backup
│       │   ├── sms/         SIM registry, send queue, receivers, USSD, calls, SIM memory
│       │   ├── server/      Embedded HTTP server, REST + admin API, firewall, TLS
│       │   ├── net/         Webhooks, persistent WebSocket link, Telegram bot
│       │   ├── service/     Foreground service and lifecycle
│       │   └── ui/          Jetpack Compose UI
│       └── assets/web/      Web admin panel (vanilla JS, no CDN)
├── branding/                Logo sources (SVG)
├── docs/                    Release guide, Google Play guide, privacy policy
├── examples/                Reference SMS server (Python)
├── scripts/                 Release helper
├── .github/                 CI and release workflows, issue & PR templates
├── version.properties       Single source of the app version (SemVer)
└── CHANGELOG.md
```

## Building from source

Requirements: JDK 17 and Android SDK 36.

```bash
./gradlew assembleDebug                    # debug APK
./gradlew assembleRelease bundleRelease    # release APK + AAB
```

Release signing reads `keystore.properties` (never committed):

```properties
storeFile=keystore/upload.jks
storePassword=...
keyAlias=...
keyPassword=...
```

Without it, release builds are produced unsigned.

## Releasing

Versions follow [SemVer](https://semver.org/) and live in [`version.properties`](version.properties)
(`versionCode = MAJOR*10000 + MINOR*100 + PATCH`). Pushing a tag `vX.Y.Z` triggers the
[release workflow](.github/workflows/release.yml): it builds a signed APK and AAB and publishes a GitHub Release
with notes taken from [CHANGELOG.md](CHANGELOG.md).

```bash
./scripts/release.sh 1.2.0        # bump version, commit, tag v1.2.0
git push origin main --tags       # CI builds and publishes the release
```

See [docs/RELEASING.md](docs/RELEASING.md) for the one-time setup of the signing secrets.

## Privacy

iSMS GW has no cloud service, analytics or ads. Read the full [privacy policy](docs/privacy-policy.html).

## Contributing

Issues and pull requests are welcome — please read [CONTRIBUTING.md](CONTRIBUTING.md), and report vulnerabilities
privately as described in [SECURITY.md](SECURITY.md).

---

<div align="center">

© 2026 Developed by [**iSmartyPro**](https://www.ismarty.pro)

</div>
