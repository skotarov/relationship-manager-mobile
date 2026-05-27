# Project rules for CallReport Mobile

## Repository layout

This repository is `skotarov/callreport-mobile`.

The Android project is here:

- `mobile/calllog-android/`

Do not look for a root `/app` project first.

Important files:

- Android build config: `mobile/calllog-android/app/build.gradle.kts`
- APK workflow: `.github/workflows/android-build.yml`
- APK artifact name: `callreport-debug-apk`
- APK output path: `mobile/calllog-android/app/build/outputs/apk/debug/callreport-debug.apk`

## APK/update rules

Known values:

- Android package: `com.onlineimoti.calllog`
- `versionCode` is based on `GITHUB_RUN_NUMBER`
- APK update over an existing install requires the same package name, higher version code, and the same signing key
- The debug APK workflow uses a cached debug keystore with cache key `callreport-debug-keystore-v1`

## Work style

When the user says “направи го” for this repo:

- Change `main` directly unless the user explicitly asks for a PR or branch.
- For APK/build/install questions, inspect only:
  - `mobile/calllog-android/app/build.gradle.kts`
  - `.github/workflows/android-build.yml`
- After writing a file, verify the saved file once.

## Android tasks belong in this repo

Implement Android/mobile work here: UI, Settings, recent calls, pagination, permissions, app icon, theme, build version display, call detection, popups, local call log, WebView opening of remote pages, and Android-side settings.

## Server-side tasks do not belong in this repo

Do not implement PHP server-side logic directly in this Android repo unless explicitly requested.

For server-side work, prepare a Codex prompt for the `onlineimoti.com` project under `/broker/callreport/` instead of changing this Android repo.
