# Google Play release checklist — OnlineImoti CRM

This checklist applies to the Android app in `mobile/calllog-android/`.

## Before the first upload

1. Choose the final public app name and update `app_name`, the Play Store title, icon and screenshots together.
2. Create the Google Play Console app with package id `com.onlineimoti.calllog`.
3. Enable **Play App Signing** and create a dedicated upload key. Never use the debug key.
4. Create a local signing file from `play-signing.properties.example`:

   ```bash
   cd mobile/calllog-android
   cp play-signing.properties.example play-signing.properties
   ```

   Fill in the file with the location of the upload key, its alias and the two local secrets. `play-signing.properties` and key files are intentionally ignored by Git.

5. Build the signed bundle:

   ```bash
   gradle --no-daemon \
     -PplayVersionCode=1 \
     -PplayVersionName=1.0.0 \
     :app:bundleRelease
   ```

   Upload the generated `app/build/outputs/bundle/release/app-release.aab` to the **Internal testing** track first.

## Required Play Console content

- Category: Business.
- A public privacy-policy URL.
- Accurate Data safety declarations for call log, contacts, phone numbers, locally stored notes and server synchronization.
- App access instructions and a dedicated review account or test access token that lets reviewers complete the core CRM flow.
- Screenshots from the release build only, with no debug/test panels and no real customer data.
- A support email address and an account/data-deletion contact path.

## Release controls included in this branch

- No SMS/MMS permissions or default-SMS components.
- No `MANAGE_EXTERNAL_STORAGE` or full-screen intent permission.
- Local notes use app-private storage; archive import/export continues through the Android document picker.
- Backup is disabled and cleartext HTTP is blocked in the release manifest.
- Debug defaults and debug/test navigation are isolated from release builds.
- The default access token is no longer packaged into the app.

## Versioning

Use a new, higher integer for every Play upload:

- `-PplayVersionCode=1`, then `2`, `3`, and so on.
- Keep the visible version semantic, for example `-PplayVersionName=1.0.0`, then `1.0.1`.

## Manual GitHub Actions bundle release

The `Build signed Play bundle` workflow expects these GitHub repository secrets:

- `PLAY_UPLOAD_KEYSTORE_BASE64`: Base64 content of the upload `.jks` file.
- `PLAY_SIGNING_PROPERTIES_BASE64`: Base64 content of `play-signing.properties`, with `storeFile=play-upload.jks`.

Run it manually with the next version code and version name. It uploads a signed `.aab` artifact for Play Console.
