# Google Play Business CRM flow

This document describes the public `playRelease` package. It is separate from the existing full local APK distributed directly to users.

## Product split

| Channel | Variant | Package | Intended use |
| --- | --- | --- | --- |
| Direct APK | `internalDebug` | `com.onlineimoti.calllog` | Full personal/local Call Log use without a server account. Do not upload this build to public Google Play. |
| Google Play | `playRelease` | `com.onlineimoti.relationshipmanager` | Business CRM only: company sign-in, company Call Log history, notes, and CRM synchronization. |

The Play package must not expose manual server URL or access-token fields. It is not an in-app store and it does not contain an external checkout link.

## Public Play user flow

1. The app opens on **Company sign-in**.
2. The employee enters the work username/email and password created by the company administrator.
3. The app receives a short-lived `rms1_` company session from `/relationship-manager/api/mobile_auth.php`.
4. The app shows a clear in-app disclosure explaining that number, direction, date/time, and duration are read from Call Log and sent to the company CRM for call history and notes.
5. Only after the employee taps **Continue** does the app open Settings and request Android permissions, including `READ_CALL_LOG`.
6. Call Log reads, call-state popups, Call Screening lookups, background call synchronization, and note synchronization stop when the user signs out or the session expires.

The session token is sent only in `Authorization: Bearer` headers to mobile endpoints. It is never placed in a URL or WebView form URL.

## Server contract

The Android Play package expects the server-side branch `codex/play-enterprise-auth` to be deployed.

Required endpoint:

- `POST /relationship-manager/api/mobile_auth.php`
  - input: `username` or `email`, `password`, `device_name`, `app_package`
  - output: `session_token`, `expires_at_ms`, account/user data, and endpoint paths

Authenticated mobile endpoints:

- `/relationship-manager/api/mobile_lookup.php`
- `/relationship-manager/api/mobile_sync.php`
- `/relationship-manager/history_lookup.php`
- existing signed-ticket `/relationship-manager/api/form.php` and `/relationship-manager/api/submit.php`

Before enabling a customer, provision an active company account and active employee member in the trusted server environment. Passwords must be stored with `password_hash`; do not create members or passwords in the mobile repository.

## Play Console review package

Prepare all of the following before submitting a Call Log declaration:

- A reviewer company account and employee username/password.
- Exact steps: sign in, read the disclosure, grant Call Log, show a recent call, open a post-call note, verify synchronization.
- A short screen recording of that full flow on a clean device.
- A public privacy policy and account/data-deletion contact path.
- Accurate Data Safety answers for Call Log metadata, contacts when enabled, notes, company account data, and server synchronization.
- Store screenshots without real customer data, passwords, tokens, test controls, or internal URLs.

Describe the app as a business CRM for brokers and sales teams. Call Log is a core function, not an optional hidden feature.

## Billing and customer onboarding

The Play package is consumption-only:

- The company buys the CRM service directly from Online Imoti under its own commercial arrangement.
- The administrator receives employee accounts from Online Imoti.
- The app only signs in to an already activated company service.
- Do not add a direct external payment link, credit-card form, price table, or upgrade checkout inside the Play package.

A neutral message such as “Contact your company administrator for access” is appropriate on the sign-in screen.

## Release checklist

- [ ] Server enterprise-auth branch is merged and deployed with HTTPS.
- [ ] `RELATIONSHIP_MANAGER_DATA_DIR` is outside the public web root.
- [ ] `RELATIONSHIP_MANAGER_SUPPORT_EMAIL` and `RELATIONSHIP_MANAGER_TICKET_SECRET` are configured.
- [ ] At least one test company and test employee account are provisioned.
- [ ] Android `playRelease` lint and bundle build pass.
- [ ] Clean-install test passes: login → disclosure → Call Log permission → call list → post-call note → sync.
- [ ] Logout test passes: Home, receiver, screening, call sync, and note sync no longer use company Call Log/session data.
- [ ] Google Play App access, Data Safety, privacy policy, account deletion, and Call Log declaration are completed.
- [ ] Only the signed `playRelease` AAB is uploaded to Google Play Internal testing first.
