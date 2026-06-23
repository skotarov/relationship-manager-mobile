# Relationship Management Mobile

Android application repository for the OnlineImoti relationship management workflow.

## Structure

- `mobile/calllog-android/` - Android application source
- `.github/workflows/android-build.yml` - GitHub Actions build for debug APK

## Android build

The repository builds the Android app in GitHub Actions on `ubuntu-latest` with JDK 17, Android SDK 35 and Gradle 8.10.2.

After a successful workflow run, download the `relationship-management-debug-apk` artifact.

## Server-side repository

The PHP/HTML/CSS/JS server-side source for the remote flow under `/broker/callreport/` lives in:

- `skotarov/relationship-manager-server`
