# Publishing iSMS GW on Google Play

Everything you need to fill in the Play Console.

> **Main review risk — the SMS & Call Log permissions policy.** SMS permissions are only allowed for an app that
> is the **default SMS handler** (or qualifies for an exception). iSMS GW is designed around this rule: it is a full
> SMS client (conversations, notifications, reply, quick reply from incoming calls), it requests the default-SMS role
> **before** any SMS permission, and it stops sending/receiving through the gateway when it loses the role.
> Approval is still at Google's discretion — submit a detailed declaration and a demo video.

## 1. Artifacts

| What | Where |
|---|---|
| App Bundle for Play | `iSMS-GW-<version>-release.aab` from the [GitHub release](https://github.com/iSmartyPRO/isms-gw-android/releases) |
| APK for direct install | `iSMS-GW-<version>-release.apk` from the same release |
| Upload key | Your private keystore (see [RELEASING.md](RELEASING.md)) — never commit it |
| Privacy policy | [`privacy-policy.html`](privacy-policy.html) — publish it at a public URL (e.g. GitHub Pages) |
| Icon source | [`branding/icon.svg`](../branding/icon.svg) (export a 512×512 PNG for the store) |

Enable **Play App Signing**: Google keeps the app signing key, you sign uploads with your upload key.

## 2. Technical requirements — done

- `targetSdk 36` (Android 16) — required for new apps since 31 Aug 2026.
- `minSdk 26` (Android 8.0).
- AAB format, upload-key signing, R8 minification.
- Edge-to-edge, no orientation lock (API 36 large-screen requirement).
- Foreground service type `specialUse` with a subtype description in the manifest.
- No `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` (the system settings screen is opened instead).
- No ads, analytics or third-party data-collection SDKs.

## 3. Store listing

**App name (30):** `iSMS GW — SMS & USSD Gateway`

**Short description (80):** `SMS app with REST API, web panel, Telegram bot and webhooks. Dual-SIM, firewall.`

**Full description:**
```
iSMS GW turns your Android phone into a secure, self-hosted SMS & USSD gateway — and it is a complete SMS app too.

MESSAGING
• Conversations, notifications, quick reply from incoming calls
• Choose the SIM for every message

GATEWAY FOR BUSINESS & AUTOMATION
• REST API: send SMS, delivery status, receive incoming messages
• Web admin panel in your desktop browser — configure everything from there
• Webhooks signed with HMAC-SHA256 — incoming SMS and statuses straight into your CRM
• Persistent connection to your own server (WebSocket) — works on mobile data and behind NAT
• Telegram bot: incoming SMS in Telegram, send SMS and control the gateway with commands
• USSD requests (balance, data left) and click-to-call per SIM
• Dual-SIM and eSIM: routing strategies, daily limits, per-SIM send/receive toggles
• Queue with throttling and per-minute limits, retries, delivery reports, long SMS

SECURITY
• Built-in firewall: local network only / whitelist, blacklist, CIDR rules
• Automatic IP bans on brute force, rate limiting
• Users and roles with access to selected SIM cards; scoped API keys stored as hashes
• HTTPS with a certificate generated on the device; secrets encrypted with Android Keystore

PRIVACY
• No cloud, no ads, no analytics. Messages only go where you configure them.

iSMS GW must be set as the default SMS app to work.
```

**Category:** Communication. **Tags:** SMS, Messaging.
**Graphics:** 512×512 icon (from `branding/icon.svg`), 1024×500 feature graphic, at least 2 phone screenshots
(Gateway, Messages, Settings); web panel screenshots can be shown in the feature graphic.

## 4. Permissions declaration (SMS & Call Log)

**Core functionality:** `Default SMS handler`

**Permissions:** SEND_SMS, RECEIVE_SMS, READ_SMS, RECEIVE_MMS, RECEIVE_WAP_PUSH.

**Justification:**
```
iSMS GW is a default SMS handler. Its core functionality is sending, receiving and displaying SMS
conversations, with built-in, user-configured automation (an "SMS gateway"): the user can send SMS
from their own computer or server via a local web panel/REST API protected by a password, API keys
and an IP firewall, and can forward incoming SMS to webhook URLs, a WebSocket server that the user
configures, or a Telegram bot the user creates and links with a one-time code. No data is sent to
the developer; there is no cloud service, analytics or ads.

The app asks to become the default SMS handler before requesting any SMS permission. When the app is
not the default handler, the gateway stops sending and receiving and the UI asks the user to restore
the role. READ_SMS is used to display conversations; RECEIVE_SMS/RECEIVE_MMS/RECEIVE_WAP_PUSH are
required for the default SMS handler role (MMS are acknowledged but not processed); SEND_SMS sends
messages typed by the user or requested by the user's own configured clients.
```

**CALL_PHONE** (not a restricted group, explain it in the description): used only on an explicit action of the user
or their own clients — USSD requests (balance, data left) via a selected SIM and click-to-call from the web panel/API.
The app never accesses call audio.

**Demo video (required, 30–90 s):**
1. First launch → welcome screen → *Set as default* → system dialog.
2. Conversations, open a chat, send an SMS manually, receive an SMS and its notification.
3. Start the gateway → sign in to the web panel from a computer → send an SMS from the panel.
4. Firewall and API key settings.

Upload it to YouTube (unlisted) and paste the link into the form.

## 5. Foreground service (App content → Foreground service permissions)

Type: **specialUse**.
```
The foreground service runs only after the user explicitly starts the gateway. It keeps a local HTTP
server (REST API + web admin panel) reachable on the user's LAN and maintains an optional persistent
WebSocket connection to the user's own SMS server, so that SMS sending requests are executed and
incoming SMS are forwarded immediately. A persistent notification with a Stop action is always shown.
Stopping the gateway stops the service. No other FGS type fits a user-controlled local server.
```

## 6. Data safety

| Question | Answer |
|---|---|
| Does the app collect or share user data? | **Yes** — SMS are transferred, but only to destinations configured by the user, and to the user's own Telegram bot (via Telegram servers) |
| Data types | Messages → **SMS/MMS**; Personal info → **Phone number** (SIM numbers and contacts' numbers) |
| Collected / shared | Mark SMS and Phone number as **Shared**, purpose **App functionality** |
| Processed ephemerally? | No (stored on the device) |
| Required or optional? | **Optional** — the gateway and integrations are turned on manually |
| Encrypted in transit | **Yes**, with HTTPS / WSS (recommended in the app) |
| Can users request deletion? | **Yes** — clear history in the panel or uninstall the app |
| Independent security review | No |

## 7. Other App content sections

- **Privacy policy:** URL of the published `privacy-policy.html`.
- **Ads:** none.
- **App access:** "All functionality is available without special access" — the web panel password is generated
  and shown in the app on the *Gateway* tab after the gateway is started.
- **Content rating (IARC):** Utility / communication, users can communicate (SMS), no location sharing. Expected: Everyone.
- **Target audience:** 18+.
- **News app / COVID-19 / financial features / government app:** no.

## 8. Publishing

1. Internal testing → upload the AAB, add testers.
2. New personal developer accounts: **closed testing with at least 12 testers for 14 days** before production access.
3. Complete sections 4–7 and submit for review.
4. Every new version: `./scripts/release.sh X.Y.Z` → `git push origin main --tags` → upload the AAB from the GitHub release.
