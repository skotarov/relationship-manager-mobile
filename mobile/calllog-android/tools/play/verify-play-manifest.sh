#!/usr/bin/env bash
# Verifies that the public Play flavor does not accidentally inherit internal-only permissions/components.
set -Eeuo pipefail
IFS=$'\n\t'

readonly APP_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"

cd "$APP_DIR"
./gradlew --no-daemon :app:processPlayReleaseMainManifest

manifest="$(find app/build/intermediates -type f -path '*playRelease*' -name AndroidManifest.xml \
  | grep -E '/(merged_manifest|merged_manifests)/' \
  | head -n 1)"
[[ -n "$manifest" && -f "$manifest" ]] || {
  echo "ERROR: Could not locate the merged playRelease manifest." >&2
  exit 1
}

python3 - "$manifest" <<'PY'
import sys
import xml.etree.ElementTree as ET

path = sys.argv[1]
android = "{http://schemas.android.com/apk/res/android}"
root = ET.parse(path).getroot()
forbidden_permissions = {
    "android.permission.READ_PHONE_STATE",
    "android.permission.READ_CALL_LOG",
    "android.permission.READ_SMS",
    "android.permission.RECEIVE_SMS",
    "android.permission.SEND_SMS",
    "android.permission.WRITE_SMS",
    "android.permission.READ_CONTACTS",
    "android.permission.WRITE_CONTACTS",
    "android.permission.SYSTEM_ALERT_WINDOW",
}
actual_permissions = {
    element.attrib.get(android + "name", "")
    for element in root.findall("uses-permission")
}
remaining_permissions = sorted(forbidden_permissions & actual_permissions)

application = root.find("application")
forbidden_components = {
    ".CallReportAuthenticatorService",
    ".CallReportSyncService",
    ".CallReportTileService",
    ".PostCallOverlayService",
    ".CallScreeningBridgeService",
    ".SmsDeliverReceiver",
    ".CallStateReceiver",
    ".SystemCallHistoryActivity",
    ".SmsHistoryActivity",
}
actual_components = set()
if application is not None:
    actual_components = {
        element.attrib.get(android + "name", "")
        for element in application
    }
remaining_components = sorted(forbidden_components & actual_components)

if remaining_permissions or remaining_components:
    print("ERROR: Internal-only Android declarations remain in the Play manifest.", file=sys.stderr)
    if remaining_permissions:
        print("Permissions: " + ", ".join(remaining_permissions), file=sys.stderr)
    if remaining_components:
        print("Components: " + ", ".join(remaining_components), file=sys.stderr)
    raise SystemExit(1)

print("Play manifest check passed: no restricted local-device declarations found.")
print(path)
PY
