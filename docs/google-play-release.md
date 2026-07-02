# Google Play release checklist — Relationship Manager

This checklist applies to the Android app in `mobile/calllog-android/`.

For the detailed company-login, reviewer-access, and deployment flow, read [Google Play Business CRM flow](google-play-business-crm.md).

## Distribution identities

| Distribution | Variant | Package ID | Purpose |
| --- | --- | --- | --- |
| Existing full local APK | `internalDebug` | `com.onlineimoti.calllog` | Keeps the sideloaded personal/local Call Log app and its debug-only SMS/testing behavior. Do not upload this package to public Google Play. |
| Google Play Business CRM | `playRelease` | `com.onlineimoti.relationshipmanager` | Public signed release with company login before Call Log use; no internal SMS, storage, debug-only, or manual server-token controls. |

The existing internal package is signed with a debug key that is present in the repository. A Google Play release must use a private Play upload key, so it cannot safely update that legacy APK under the same package ID. The public Play app is therefore a **separate clean install**. Do not use the debug key for Play signing.

The public Play package ID is permanent after the Play Console app is created.

## Build a signed AAB from GitHub Actions

Run the manual **Build signed Google Play bundle** workflow. It validates inputs, runs `lintPlayRelease`, builds `bundlePlayRelease`, verifies the AAB signature, and uploads the AAB artifact for 30 days.

Before the first run, add these GitHub repository Actions secrets:

| Secret | Value |
| --- | --- |
| `PLAY_UPLOAD_KEYSTORE_BASE64` | Upload keystore encoded as base64 on one line. |
| `PLAY_UPLOAD_STORE_PASSWORD` | Upload keystore password. |
| `PLAY_UPLOAD_KEY_ALIAS` | Upload key alias. |
| `PLAY_UPLOAD_KEY_PASSWORD` | Upload key password. |

Keep an offline backup of the upload key. Never commit a `.jks`, `.keystore`, or `play-signing.properties` file. The repository ignores all of them.

Use a new, higher positive integer for every Play upload. Suggested first public release values:

- `version_code`: `1`
- `version_name`: `1.0.0`

The workflow permits Android version codes up to `2100000000`.

For a local manual build, copy `play-signing.properties.example` to `play-signing.properties`, set the four values, then run:

```bash
cd mobile/calllog-android
gradle --no-daemon \
  -PplayVersionCode=1 \
  -PplayVersionName=1.0.0 \
  :app:lintPlayRelease :app:bundlePlayRelease
```

Upload the resulting `app/build/outputs/bundle/playRelease/app-play-release.aab` to **Internal testing** first.

## Mandatory Play Console preparation

1. Create the app as an **App** using `com.onlineimoti.relationshipmanager`, accept Play App Signing, set the support email, and choose the price model carefully.
2. Prepare the store listing: Bulgarian title, short description, long description, 512×512 icon, feature graphic, and at least two real-device screenshots.
3. Complete App content: Data safety, content rating, ads declaration, target audience, and App access.
4. Create a reviewer company account with a username/password and write exact test instructions.
5. Test a clean install: login, disclosure, denied/accepted permission paths, call start/end UI, note saving, CRM synchronization, WebView forms, offline behavior, logout, session expiry, and reinstall.
6. For a personal developer account created after 13 November 2023, complete the applicable Play testing requirement before production.

Use the **Business** category. Do not use screenshots containing real customer data, test/debug controls, tokens, or internal URLs.

## Critical policy gate: Call Log and contacts

The release app uses `READ_CALL_LOG`, `READ_PHONE_STATE`, `READ_CONTACTS`, `WRITE_CONTACTS`, and optional overlay access. These are sensitive permissions.

The intended public use is an **enterprise CRM**: a broker sees business calls, records a note, and synchronizes it to the broker's company account. `READ_CALL_LOG` is requested only after successful company sign-in and the in-app disclosure; it must remain essential to the CRM's core function.

The Play build must keep these controls in place:

- mandatory active company session before Call Log is read or synchronized;
- clear in-app disclosure before the Android Call Log permission dialog;
- no manual base URL or access-token settings;
- background Call Log and note synchronization stops on logout or session expiry;
- signed form tickets instead of bearer sessions in WebView URLs.

The enterprise route also needs a public privacy-policy page and a clear account/data-deletion path. The policy must explain local storage, company sync, collected data, retention, access, and deletion requests.

## Data Safety inventory to verify against the released app and backend

Do not copy these as final Play Console answers without verifying the deployed server and all release features.

| Area | Likely data category | Why it needs review |
| --- | --- | --- |
| Call log | Phone numbers and call metadata | Read on-device only after company sign-in and synchronized to the authenticated company CRM. |
| Contact linking | Contacts and names | Used only for enabled contact-link and synchronization features. |
| Notes and CRM associations | User-generated content | Stored locally and synchronized to the authenticated company service while the session is active. |
| Company login | User/account identifiers | Required before Call Log is requested or used in the Play build. |
| Controlled WebView screens | Data submitted in controlled form/history pages | Must be included in Data Safety answers. |

All controlled network traffic must use HTTPS. Do not package, display, or log passwords, access tokens, or signed form tickets.

## Billing and customer onboarding

The Play build is a company service client, not an in-app purchase flow:

- the company purchases the CRM service directly from Online Imoti;
- Online Imoti creates the organization and employee accounts;
- the Play app only allows access to an already activated company account;
- do not add a price table, card form, checkout, upgrade flow, or direct external payment link in the Play package.

## Store listing draft direction

**Title:** Relationship Manager

**Short description:**

> Manage business calls, contact notes and CRM follow-ups from one place.

Core description points:

- Shows recent business calls directly on the phone after company sign-in.
- Lets a broker add a structured note after a call.
- Organizes contact context and CRM follow-ups.
- Company synchronization is available only for authorized company accounts.
- Does not record calls or sell call/contact data.

The final listing must describe only enabled release features. The CRM Call Log function must be prominent, not hidden in settings.

## Release stop checklist

- [ ] Upload key is backed up offline and all four GitHub secrets are present.
- [ ] Server company-login branch is merged, deployed, and has a provisioned reviewer account.
- [ ] A signed AAB built successfully through the manual workflow.
- [ ] `lintPlayRelease` and real-device smoke tests passed.
- [ ] Mandatory company sign-in and disclosure are in place before Call Log is used.
- [ ] Call Log declaration accurately describes enterprise CRM usage and includes reviewer instructions/video.
- [ ] Public privacy policy and deletion/contact path are available.
- [ ] Data safety, content rating, ads, target audience, and App access are complete.
- [ ] Store assets and Bulgarian listing are final.
- [ ] Internal testing and any required closed-test gate are complete before production.
