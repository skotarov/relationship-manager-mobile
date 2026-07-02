# Google Play release checklist — Relationship Manager

This checklist applies to the Android app in `mobile/calllog-android/`.

## Current release identity

- **Package ID:** `com.onlineimoti.calllog`
- **Visible name:** `Relationship Manager`
- **Minimum SDK:** Android 10 / API 29
- **Target SDK:** Android 15 / API 35
- **Publishing artifact:** signed Android App Bundle (`.aab`)

Keep this package ID. Google Play package names are permanent, and the existing ID lets internal users receive an update from the Play build.

## Build a signed AAB from GitHub Actions

Run the manual **Build signed Google Play bundle** workflow. It validates inputs, runs `lintRelease`, builds `bundleRelease`, verifies the AAB signature, and uploads the AAB artifact for 30 days.

Before the first run, add these GitHub repository Actions secrets:

| Secret | Value |
| --- | --- |
| `PLAY_UPLOAD_KEYSTORE_BASE64` | Upload keystore encoded as base64 on one line. |
| `PLAY_UPLOAD_STORE_PASSWORD` | Upload keystore password. |
| `PLAY_UPLOAD_KEY_ALIAS` | Upload key alias. |
| `PLAY_UPLOAD_KEY_PASSWORD` | Upload key password. |

Keep an offline backup of the upload key. Never commit a `.jks`, `.keystore`, or `play-signing.properties` file. The repository ignores all of them.

Use a new, higher positive integer for every Play upload. Suggested first public release values:

- `version_code`: `1261830001`
- `version_name`: `1.0.0`

The workflow permits Android version codes up to `2100000000`.

For a local manual build, copy `play-signing.properties.example` to `play-signing.properties`, set the four values, then run:

```bash
cd mobile/calllog-android
gradle --no-daemon \
  -PplayVersionCode=1261830001 \
  -PplayVersionName=1.0.0 \
  :app:lintRelease :app:bundleRelease
```

Upload the resulting `app/build/outputs/bundle/release/app-release.aab` to **Internal testing** first.

## Mandatory Play Console preparation

1. Create the app as an **App**, accept Play App Signing, set the support email, and choose the price model carefully.
2. Prepare the store listing: Bulgarian title, short description, long description, 512×512 icon, feature graphic, and at least two real-device screenshots.
3. Complete App content: Data safety, content rating, ads declaration, target audience, and App access.
4. Test a clean install, denied permission paths, call start/end UI, note saving, CRM synchronization, WebView forms, offline behavior, upgrade from the current internal APK, and reinstall.
5. For a personal developer account created after 13 November 2023, complete the applicable Play testing requirement before production.

Use the **Business** category. Do not use screenshots containing real customer data, test/debug controls, tokens, or internal URLs.

## Critical policy gate: Call Log and contacts

The release app uses `READ_CALL_LOG`, `READ_PHONE_STATE`, `READ_CONTACTS`, `WRITE_CONTACTS`, and optional overlay access. These are sensitive permissions.

The intended public use is an **enterprise CRM**: a broker sees business calls, records a note, and synchronizes it to the broker's company account. `READ_CALL_LOG` requires an accurate Call Log permissions declaration and must be essential to the CRM's core function.

Before submitting to closed or production testing, the release must require a verified company account before it requests or uses Call Log access. The current app supports local/free mode with an empty default server URL and a manually entered access token, so it does not yet prove the required enterprise-login condition for a public CRM release.

Required product decision:

- **Enterprise CRM release — preferred:** implement mandatory company sign-in/session validation, show a clear explanation before the permission dialog, and request Call Log only after successful sign-in.
- **Non-CRM public release:** remove Call Log access and ensure the app remains useful without it. This materially changes Relationship Manager and is not the intended route.

The enterprise route also needs a public privacy-policy page and a clear account/data-deletion path. The policy must explain local storage, optional company sync, collected data, retention, access, and deletion requests.

## Data Safety inventory to verify against the released app and backend

Do not copy these as final Play Console answers without verifying the deployed server and all release features.

| Area | Likely data category | Why it needs review |
| --- | --- | --- |
| Call log | Phone numbers and call metadata | Read on-device and may be synchronized when remote mode is enabled. |
| Contact linking | Contacts and names | Used for optional contact-link and synchronization functions. |
| Notes and CRM associations | User-generated content | Stored locally and may be sent to the configured company service. |
| Company login | User/account identifiers | Required after enterprise sign-in is implemented. |
| Controlled WebView screens | Data submitted in the controlled form/history pages | Must be included in Data safety answers. |

All controlled network traffic must use HTTPS. Do not package, display, or log access tokens.

## Store listing draft direction

**Title:** Relationship Manager

**Short description:**

> Manage business calls, contact notes and CRM follow-ups from one place.

Core description points:

- Shows recent business calls directly on the phone.
- Lets a broker add a structured note after a call.
- Organizes contact context and CRM follow-ups.
- Company synchronization is available only for authorized company accounts.
- Does not record calls or sell call/contact data.

The final listing must describe only enabled release features. The CRM Call Log function must be prominent, not hidden in settings.

## Release stop checklist

- [ ] Upload key is backed up offline and all four GitHub secrets are present.
- [ ] A signed AAB built successfully through the manual workflow.
- [ ] `lintRelease` and real-device smoke tests passed.
- [ ] Mandatory company sign-in is in place before Call Log permission is used.
- [ ] Call Log declaration accurately describes enterprise CRM usage.
- [ ] Public privacy policy and deletion/contact path are available.
- [ ] Data safety, content rating, ads, target audience, and App access are complete.
- [ ] Store assets and Bulgarian listing are final.
- [ ] Internal testing and any required closed-test gate are complete before production.
