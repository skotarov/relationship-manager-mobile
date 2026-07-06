# Relationship Manager: Play Console checklist for the first internal test

This checklist assumes the Android project is built from the `playRelease` flavor and uses package ID `com.onlineimoti.relationshipmanager`.

## Before opening Play Console

1. Install the internal build for normal development only. Do **not** use it as the Play package.
2. Generate the Play upload key once:

   ```bash
   cd mobile/calllog-android
   bash tools/play/playctl.sh generate-upload-key
   ```

3. Build the first signed Android App Bundle. Use a new, positive version code for every upload:

   ```bash
   bash tools/play/playctl.sh build-play-aab 1000000001 1.0.0
   ```

4. Keep the generated `.jks`, recovery file and `play-signing.properties` private and outside Git.

## Play Console: create the app

Create a new app with these values:

- App name: **Relationship Manager**
- Default language: **Bulgarian (bg-BG)**
- App or game: **App**
- Category: **Business**
- Price: **Free**
- Package after the first bundle upload: `com.onlineimoti.relationshipmanager`

The application is free to install. The one-time company license uses Google Play Billing product ID `rm_company_license`; one purchase unlocks creation of one company, while invited colleagues join without another purchase.

## First internal test release

1. Upload the signed `playRelease` AAB in **Testing → Internal testing**.
2. Add your Google account as an internal tester and accept the tester link from Play Console.
3. Install the Play-distributed build through that tester link, not from the local APK.
4. In the app, enter the server URL, buy or restore the test company license, create a company, then log in from a second test account or invitation.

## Service account and automated upload

After the app exists in Play Console:

```bash
bash tools/play/playctl.sh create-service-account \
  onlineimoti-relationship-manager \
  "$HOME/.config/relationship-manager/google-play-service-account.json"
```

Invite the service-account email printed by that command in **Users and permissions**, give it app access plus testing-release and order/subscription permissions, then verify it:

```bash
bash tools/play/playctl.sh verify-play-access \
  "$HOME/.config/relationship-manager/google-play-service-account.json"
```

Only after the first bundle is accepted by Play, create the managed one-time product:

```bash
bash tools/play/playctl.sh create-company-license-product \
  "$HOME/.config/relationship-manager/google-play-service-account.json" \
  19.90
```

Future internal releases can then be uploaded automatically:

```bash
bash tools/play/playctl.sh publish-internal \
  "$HOME/.config/relationship-manager/google-play-service-account.json" \
  app/build/outputs/bundle/playRelease/app-play-release.aab
```

## Play Console declarations still owned by the account owner

The account owner must complete the current declarations in Play Console. Use the actual deployed server behavior when answering them:

- App access: provide reviewer instructions and a test company account once the server registration flow is live.
- Ads: no ads, unless ads are added later.
- Data safety: describe the account, organization, contact and CRM data sent to the configured company server; do not claim that the app collects no data.
- Privacy policy: publish a public policy URL before production rollout.
- Content rating and target audience: complete according to the actual intended business audience and functionality.

Do not submit to production until the privacy-policy URL, Data safety answers, and reviewer access details match the released Android and server behavior.
