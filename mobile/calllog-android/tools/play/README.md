# Google Play release scripts

All commands below run from:

```bash
cd mobile/calllog-android
```

Use Bash on Linux, macOS, WSL or Termux. The helper requires `gcloud`, `jq`, `curl`, `python3` and a JDK with `keytool`. It deliberately keeps service-account keys, upload keystores, passwords, AABs and `play-signing.properties` out of Git.

```bash
bash tools/play/playctl.sh --help
```

## What cannot be automated

The first Play Console app creation has legal declarations and owner-level acceptance, so create it once in Play Console before calling the API scripts:

- app name: **Relationship Manager**
- type: **App**
- package after first upload: `com.onlineimoti.relationshipmanager`
- product ID used by the Android and PHP code: `rm_company_license`

Create the Play Console app as a free app. The company license is an in-app managed product, not the price of the Play Store app itself.

## Recommended first-time sequence

### 1. Create and protect the upload key

```bash
bash tools/play/playctl.sh generate-upload-key
```

The command creates:

- a PKCS12 upload keystore under `~/.local/share/relationship-manager/play-signing/`;
- an ignored `play-signing.properties` file under the Android module;
- a private recovery text file in the same secure directory.

Back up both the `.jks` and recovery file offline. Do not commit, email or upload them.

### 2. Create the Google Cloud service account

Choose a globally unique Google Cloud project ID, for example `onlineimoti-relationship-manager`.

```bash
bash tools/play/playctl.sh create-service-account \
  onlineimoti-relationship-manager \
  "$HOME/.config/relationship-manager/google-play-service-account.json"
```

The script enables Android Publisher API and prints the exact service-account email. In Play Console, invite that email under **Users and permissions** and grant the permissions printed by the script. This owner/admin step cannot be bypassed by a local script.

### 3. Verify Play API access

After inviting the service account and giving it access to the app:

```bash
bash tools/play/playctl.sh verify-play-access \
  "$HOME/.config/relationship-manager/google-play-service-account.json"
```

A successful command prints the package and the currently visible one-time products.

### 4. Create the one-time company-license product

Choose the price in BGN. This example creates it at 19.90 BGN:

```bash
bash tools/play/playctl.sh create-company-license-product \
  "$HOME/.config/relationship-manager/google-play-service-account.json" \
  19.90
```

The command is safe to repeat: it checks whether `rm_company_license` already exists and does not overwrite it.

### 5. Build a signed Play AAB

The `versionCode` must be higher on every Google Play upload.

```bash
bash tools/play/playctl.sh build-play-aab 1000000001 1.0.0
```

The script prints the generated `.aab` path. It builds the public `playRelease` flavor, not the legacy internal APK.

### 6. Upload to Internal testing

```bash
bash tools/play/playctl.sh publish-internal \
  "$HOME/.config/relationship-manager/google-play-service-account.json" \
  app/build/outputs/bundle/playRelease/app-play-release.aab
```

The script creates a Play edit, uploads the AAB, assigns it to `internal` and commits the edit.

## Server environment template

The server module needs the same service account to validate purchases. Generate a private environment-file template:

```bash
bash tools/play/playctl.sh prepare-server-env \
  /srv/relationship-manager-secrets/google-play-service-account.json \
  /srv/relationship-manager-data
```

This writes a local template to:

```text
~/.config/relationship-manager/relationship-manager-play.env
```

Copy the JSON key to the named server path with owner-only permissions, then configure the actual PHP-FPM/Apache/container deployment to load the environment variables. The script does not guess a server’s process manager or web-root paths.

The required values are:

```text
RELATIONSHIP_MANAGER_DATA_DIR=/srv/relationship-manager-data
RELATIONSHIP_MANAGER_PLAY_PACKAGE_NAME=com.onlineimoti.relationshipmanager
RELATIONSHIP_MANAGER_PLAY_PRODUCT_IDS=rm_company_license
RELATIONSHIP_MANAGER_GOOGLE_PLAY_SERVICE_ACCOUNT_JSON_FILE=/srv/relationship-manager-secrets/google-play-service-account.json
```

## Safety notes

- Do not execute the scripts from a directory containing a production keystore in Git.
- Do not give the service account production release permissions unless you intentionally want automated production publishing.
- `publish-internal` only writes to the internal testing track.
- Keep the service-account JSON outside the web root and outside the Git checkout.
