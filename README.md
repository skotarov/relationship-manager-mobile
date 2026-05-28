# Relation Management Mobile

Mobile prototype and CI setup for the OnlineImoti relation management workflow.

## Structure

- `mobile/calllog-android/` - Android prototype app
- `broker/callreport/` - server-side notes and integration docs
- `.github/workflows/android-build.yml` - GitHub Actions build for debug APK

## Android build

The repository builds the Android app in GitHub Actions on `ubuntu-latest` with JDK 17, Android SDK 35 and Gradle 8.10.2.

After a successful workflow run, download the `relation-management-debug-apk` artifact.

## Notes

The Android app is intentionally a thin native wrapper around the remote PHP flow hosted under `/broker/callreport/`.
