# GitHub Actions Handoff

Дата: 2026-05-19

## Състояние

Подготвени са:

- server-side mobile call report endpoint-и в `broker/callreport/`
- Android prototype project в `mobile/calllog-android/`

Локалният Android toolchain беше премахнат. Няма активна локална JDK/SDK/Gradle инсталация за build.

## Какво да не се прави пак

Не опитвай локален Android build на този сървър.

Причина:

- host машината е `aarch64`
- Android Linux build tooling удари blocker с `aapt2`

Затова следващата стъпка трябва да е build в GitHub Actions на `x86_64` runner.

## Следваща стъпка

Да се добави GitHub Actions workflow, който:

1. checkout-ва repo-то
2. setup-ва JDK 17
3. setup-ва Android SDK
4. build-ва `mobile/calllog-android`
5. публикува `app-debug.apk` като artifact
