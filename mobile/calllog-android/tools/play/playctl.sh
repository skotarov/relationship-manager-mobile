#!/usr/bin/env bash
# Relationship Manager Play release helper.
# Run with: bash tools/play/playctl.sh <command> [arguments]
# No passwords, service-account JSON files, AABs or generated signing properties are committed.

set -Eeuo pipefail
IFS=$'\n\t'

readonly PACKAGE_ID="${RELATIONSHIP_MANAGER_PLAY_PACKAGE_ID:-com.onlineimoti.relationshipmanager}"
readonly PRODUCT_ID="${RELATIONSHIP_MANAGER_PLAY_PRODUCT_ID:-rm_company_license}"
readonly API_BASE="https://androidpublisher.googleapis.com/androidpublisher/v3"
readonly REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../../.." && pwd)"
readonly APP_DIR="$REPO_ROOT/mobile/calllog-android"

TEMP_GCLOUD_CONFIG=""
ACCESS_TOKEN=""

cleanup() {
  if [[ -n "$TEMP_GCLOUD_CONFIG" && -d "$TEMP_GCLOUD_CONFIG" ]]; then
    rm -rf "$TEMP_GCLOUD_CONFIG"
  fi
}
trap cleanup EXIT

usage() {
  cat <<'EOF'
Relationship Manager – Google Play helper

Usage:
  bash tools/play/playctl.sh <command> [arguments]

Commands:
  create-service-account <gcp-project-id> <output-json-path>
      Creates the Google Cloud service account, enables Android Publisher API and
      creates a local JSON key. Then follow the printed one-time Play Console step.

  verify-play-access <service-account-json>
      Verifies that the service account can call the Google Play Developer API for
      com.onlineimoti.relationshipmanager. Run after inviting the service account
      in Play Console.

  create-company-license-product <service-account-json> <price-bgn>
      Creates the managed one-time product rm_company_license in Google Play.
      Example price-bgn values: 9.99, 19.00, 49.90

  generate-upload-key [secure-local-directory]
      Creates a new upload keystore and the ignored play-signing.properties file.
      Run this only once, then back up the secure local directory offline.

  build-play-aab <version-code> <version-name>
      Builds a signed Play AAB after generate-upload-key has completed.
      Example: build-play-aab 1000000001 1.0.0

  publish-internal <service-account-json> <aab-path>
      Uploads an already-built AAB to the internal testing track through the
      Android Publisher API.

  prepare-server-env <remote-service-account-json-path> <data-directory> [output-file]
      Produces an environment-file template for the PHP deployment. It does not
      copy secrets to a server because each hosting setup loads PHP environment
      variables differently.

Environment overrides:
  RELATIONSHIP_MANAGER_PLAY_PACKAGE_ID
  RELATIONSHIP_MANAGER_PLAY_PRODUCT_ID
EOF
}

fail() {
  printf 'ERROR: %s\n' "$*" >&2
  exit 1
}

note() {
  printf '\n==> %s\n' "$*"
}

require_command() {
  command -v "$1" >/dev/null 2>&1 || fail "Missing required command: $1"
}

require_file() {
  [[ -f "$1" ]] || fail "File not found: $1"
}

require_integer() {
  [[ "$1" =~ ^[1-9][0-9]*$ ]] || fail "Expected a positive integer, got: $1"
}

json_field() {
  jq -r "$2" "$1"
}

start_service_account_session() {
  local key_file="$1"
  require_file "$key_file"
  require_command gcloud
  require_command jq

  local client_email project_id
  client_email="$(json_field "$key_file" '.client_email // empty')"
  project_id="$(json_field "$key_file" '.project_id // empty')"
  [[ -n "$client_email" ]] || fail "The JSON file does not contain client_email."
  [[ -n "$project_id" ]] || fail "The JSON file does not contain project_id."

  TEMP_GCLOUD_CONFIG="$(mktemp -d)"
  export CLOUDSDK_CONFIG="$TEMP_GCLOUD_CONFIG"
  gcloud auth activate-service-account "$client_email" --key-file="$key_file" --quiet >/dev/null
  gcloud config set project "$project_id" --quiet >/dev/null
  ACCESS_TOKEN="$(gcloud auth print-access-token)"
  [[ -n "$ACCESS_TOKEN" ]] || fail "Could not obtain a Google access token."
}

api_call() {
  local method="$1"
  local url="$2"
  local body_mode="${3:-}"
  local body_value="${4:-}"
  local response status response_body
  local -a curl_args=(
    --silent --show-error --location
    --request "$method"
    --header "Authorization: Bearer $ACCESS_TOKEN"
    --header "Accept: application/json"
    --write-out $'\n%{http_code}'
  )

  case "$body_mode" in
    json)
      curl_args+=(--header "Content-Type: application/json; charset=utf-8" --data "$body_value")
      ;;
    binary)
      curl_args+=(--header "Content-Type: application/octet-stream" --data-binary "@$body_value")
      ;;
    "")
      ;;
    *)
      fail "Unsupported API body mode: $body_mode"
      ;;
  esac

  response="$(curl "${curl_args[@]}" "$url")" || fail "Network request failed: $url"
  status="${response##*$'\n'}"
  response_body="${response%$'\n'*}"
  if [[ ! "$status" =~ ^2[0-9][0-9]$ ]]; then
    printf '%s\n' "$response_body" >&2
    fail "Google Play API request failed with HTTP $status."
  fi
  printf '%s' "$response_body"
}

create_service_account() {
  local project_id="${1:-}"
  local key_output="${2:-}"
  [[ -n "$project_id" ]] || fail "Usage: create-service-account <gcp-project-id> <output-json-path>"
  [[ -n "$key_output" ]] || fail "Usage: create-service-account <gcp-project-id> <output-json-path>"
  [[ "$project_id" =~ ^[a-z][a-z0-9-]{4,28}[a-z0-9]$ ]] || fail "Invalid Google Cloud project ID."

  require_command gcloud
  require_command jq
  require_command install

  note "Checking Google Cloud login"
  if ! gcloud auth list --filter='status:ACTIVE' --format='value(account)' | grep -q .; then
    gcloud auth login
  fi

  if ! gcloud projects describe "$project_id" >/dev/null 2>&1; then
    note "Creating Google Cloud project $project_id"
    gcloud projects create "$project_id" --name="Relationship Manager Play"
  fi

  gcloud config set project "$project_id" --quiet
  note "Enabling Android Publisher API"
  gcloud services enable androidpublisher.googleapis.com --quiet

  local service_account_id="relationship-manager-play"
  local service_account_email="${service_account_id}@${project_id}.iam.gserviceaccount.com"
  if ! gcloud iam service-accounts describe "$service_account_email" >/dev/null 2>&1; then
    note "Creating service account"
    gcloud iam service-accounts create "$service_account_id" \
      --display-name="Relationship Manager Google Play" \
      --description="Server-side Google Play purchase verification and internal-release publishing"
  fi

  key_output="$(python3 - "$key_output" <<'PY'
import os, sys
print(os.path.abspath(os.path.expanduser(sys.argv[1])))
PY
)"
  [[ ! -e "$key_output" ]] || fail "Refusing to overwrite existing key file: $key_output"
  install -d -m 700 "$(dirname "$key_output")"
  umask 077
  note "Creating service-account key"
  gcloud iam service-accounts keys create "$key_output" --iam-account="$service_account_email"
  chmod 600 "$key_output"

  cat <<EOF

Service account created:
  $service_account_email

One required manual Play Console action remains because only the Play Console
account owner/admin can grant Play permissions:
  1. Open Play Console → Users and permissions → Invite new users.
  2. Invite: $service_account_email
  3. Grant at least:
     - View app information and download bulk reports (read-only)
     - Release apps to testing tracks
     - Manage testing tracks and edit tester lists
     - View financial data, orders, and cancellation survey responses
     - Manage orders and subscriptions
  4. Give access to the Relationship Manager app after it exists in Play Console.

Keep this file private and outside Git:
  $key_output
EOF
}

verify_play_access() {
  local key_file="${1:-}"
  [[ -n "$key_file" ]] || fail "Usage: verify-play-access <service-account-json>"
  start_service_account_session "$key_file"
  note "Checking Play Developer API access for $PACKAGE_ID"
  local response
  response="$(api_call GET "$API_BASE/applications/$PACKAGE_ID/inappproducts")"
  printf '%s\n' "$response" | jq '{access: "ok", package: "'"$PACKAGE_ID"'", products: (.inappproduct // [] | map(.sku))}'
}

price_to_micros() {
  local price_bgn="$1"
  python3 - "$price_bgn" <<'PY'
from decimal import Decimal, InvalidOperation, ROUND_HALF_UP
import sys
try:
    value = Decimal(sys.argv[1])
except InvalidOperation:
    raise SystemExit(1)
if value <= 0 or value >= Decimal('1000000'):
    raise SystemExit(1)
print(int((value * Decimal('1000000')).quantize(Decimal('1'), rounding=ROUND_HALF_UP)))
PY
}

create_company_license_product() {
  local key_file="${1:-}"
  local price_bgn="${2:-}"
  [[ -n "$key_file" && -n "$price_bgn" ]] || fail "Usage: create-company-license-product <service-account-json> <price-bgn>"
  require_command jq
  local price_micros
  price_micros="$(price_to_micros "$price_bgn")" || fail "Invalid BGN price: $price_bgn"
  start_service_account_session "$key_file"

  note "Checking whether $PRODUCT_ID already exists"
  local get_response get_status get_body
  get_response="$(curl --silent --show-error --location \
    --header "Authorization: Bearer $ACCESS_TOKEN" \
    --header "Accept: application/json" \
    --write-out $'\n%{http_code}' \
    "$API_BASE/applications/$PACKAGE_ID/inappproducts/$PRODUCT_ID")" || fail "Network request failed while checking product."
  get_status="${get_response##*$'\n'}"
  get_body="${get_response%$'\n'*}"
  if [[ "$get_status" =~ ^2[0-9][0-9]$ ]]; then
    printf '%s\n' "$get_body" | jq '{product: .sku, status: .status, purchaseType: .purchaseType}'
    note "Product already exists; no change made."
    return
  fi
  if [[ "$get_status" != "404" ]]; then
    printf '%s\n' "$get_body" >&2
    fail "Could not check existing product (HTTP $get_status)."
  fi

  local payload response
  payload="$(jq -n \
    --arg sku "$PRODUCT_ID" \
    --arg price "$price_micros" \
    '{sku: $sku, status: "active", purchaseType: "managedUser", defaultPrice: {priceMicros: $price, currency: "BGN"}, listings: {"bg-BG": {title: "Фирмен лиценз", description: "Еднократен лиценз за създаване на фирмена организация в Relationship Manager."}, "en-US": {title: "Company license", description: "One-time license for creating a company organization in Relationship Manager."}}}')"
  note "Creating managed one-time product $PRODUCT_ID at $price_bgn BGN"
  response="$(api_call POST "$API_BASE/applications/$PACKAGE_ID/inappproducts?autoConvertMissingPrices=true" json "$payload")"
  printf '%s\n' "$response" | jq '{product: .sku, status: .status, purchaseType: .purchaseType, defaultPrice: .defaultPrice}'
}

random_hex() {
  od -An -N24 -tx1 /dev/urandom | tr -d ' \n'
}

generate_upload_key() {
  local secure_dir="${1:-$HOME/.local/share/relationship-manager/play-signing}"
  require_command keytool
  require_command python3
  secure_dir="$(python3 - "$secure_dir" <<'PY'
import os, sys
print(os.path.abspath(os.path.expanduser(sys.argv[1])))
PY
)"
  local keystore="$secure_dir/relationship-manager-upload.jks"
  local secrets="$secure_dir/UPLOAD_KEY_SECRETS.txt"
  local properties="$APP_DIR/play-signing.properties"
  [[ ! -e "$keystore" ]] || fail "Keystore already exists: $keystore"
  [[ ! -e "$properties" ]] || fail "play-signing.properties already exists: $properties"

  local organization country
  read -r -p "Organization name for the upload certificate [Online Imoti OOD]: " organization
  organization="${organization:-Online Imoti OOD}"
  read -r -p "Two-letter country code [BG]: " country
  country="${country:-BG}"

  local store_secret key_secret
  store_secret="$(random_hex)"
  key_secret="$(random_hex)"
  install -d -m 700 "$secure_dir"
  umask 077
  keytool -genkeypair \
    -keystore "$keystore" \
    -storetype PKCS12 \
    -storepass "$store_secret" \
    -alias relationship-manager-upload \
    -keypass "$key_secret" \
    -keyalg RSA \
    -keysize 4096 \
    -validity 10000 \
    -dname "CN=Relationship Manager, O=$organization, C=$country" \
    -noprompt

  cat > "$properties" <<EOF
storeFile=$keystore
storeSecret=$store_secret
keyAlias=relationship-manager-upload
keySecret=$key_secret
EOF
  chmod 600 "$properties"
  cat > "$secrets" <<EOF
Relationship Manager upload-key recovery information

Keystore: $keystore
Alias: relationship-manager-upload
Store password: $store_secret
Key password: $key_secret

Back up this file and the .jks file offline. Do not commit, email or upload them.
EOF
  chmod 600 "$secrets"

  note "Upload key created"
  printf 'Keystore: %s\nSecrets:  %s\nProperties: %s\n\n' "$keystore" "$secrets" "$properties"
  keytool -list -v -keystore "$keystore" -storepass "$store_secret" -alias relationship-manager-upload \
    | grep -E 'Alias name:|SHA256:' || true
}

build_play_aab() {
  local version_code="${1:-}"
  local version_name="${2:-}"
  [[ -n "$version_code" && -n "$version_name" ]] || fail "Usage: build-play-aab <version-code> <version-name>"
  require_integer "$version_code"
  [[ -f "$APP_DIR/play-signing.properties" ]] || fail "Missing $APP_DIR/play-signing.properties. Run generate-upload-key first."
  require_command bash

  note "Building signed Play AAB"
  (
    cd "$APP_DIR"
    ./gradlew --no-daemon :app:bundlePlayRelease \
      -PplayVersionCode="$version_code" \
      -PplayVersionName="$version_name"
  )

  local aab
  aab="$(find "$APP_DIR/app/build/outputs/bundle/playRelease" -maxdepth 1 -type f -name '*.aab' -print -quit)"
  [[ -n "$aab" && -f "$aab" ]] || fail "Gradle completed but no playRelease AAB was found."
  printf '\nAAB ready:\n%s\n' "$aab"
}

publish_internal() {
  local key_file="${1:-}"
  local aab_path="${2:-}"
  [[ -n "$key_file" && -n "$aab_path" ]] || fail "Usage: publish-internal <service-account-json> <aab-path>"
  require_file "$aab_path"
  require_command jq
  start_service_account_session "$key_file"

  note "Creating Google Play edit"
  local edit edit_id upload version_code release_payload
  edit="$(api_call POST "$API_BASE/applications/$PACKAGE_ID/edits" json '{}')"
  edit_id="$(printf '%s' "$edit" | jq -r '.id // empty')"
  [[ -n "$edit_id" ]] || fail "Google Play did not return an edit ID."

  note "Uploading Android App Bundle"
  upload="$(api_call POST "https://androidpublisher.googleapis.com/upload/androidpublisher/v3/applications/$PACKAGE_ID/edits/$edit_id/bundles?uploadType=media" binary "$aab_path")"
  version_code="$(printf '%s' "$upload" | jq -r '.versionCode // empty')"
  [[ -n "$version_code" ]] || fail "Google Play did not return the uploaded version code."

  release_payload="$(jq -n --arg version_code "$version_code" --arg name "Internal $version_code" '{releases: [{name: $name, status: "completed", versionCodes: [$version_code]}]}')"
  note "Assigning version $version_code to the internal testing track"
  api_call PUT "$API_BASE/applications/$PACKAGE_ID/edits/$edit_id/tracks/internal" json "$release_payload" >/dev/null

  note "Committing Google Play edit"
  api_call POST "$API_BASE/applications/$PACKAGE_ID/edits/$edit_id:commit" json '{}' >/dev/null
  printf '\nInternal-test release published successfully. Version code: %s\n' "$version_code"
}

prepare_server_env() {
  local remote_key_path="${1:-}"
  local data_dir="${2:-}"
  local output_file="${3:-$REPO_ROOT/relationship-manager-play.env}"
  [[ -n "$remote_key_path" && -n "$data_dir" ]] || fail "Usage: prepare-server-env <remote-service-account-json-path> <data-directory> [output-file]"
  [[ "$remote_key_path" = /* ]] || fail "remote-service-account-json-path must be an absolute server path."
  [[ "$data_dir" = /* ]] || fail "data-directory must be an absolute server path."
  umask 077
  cat > "$output_file" <<EOF
RELATIONSHIP_MANAGER_DATA_DIR=$data_dir
RELATIONSHIP_MANAGER_PLAY_PACKAGE_NAME=$PACKAGE_ID
RELATIONSHIP_MANAGER_PLAY_PRODUCT_IDS=$PRODUCT_ID
RELATIONSHIP_MANAGER_GOOGLE_PLAY_SERVICE_ACCOUNT_JSON_FILE=$remote_key_path
EOF
  chmod 600 "$output_file"
  printf '\nCreated server environment template:\n%s\n\n' "$output_file"
  cat <<EOF
Copy the service-account JSON to this server path with owner-only permissions:
  $remote_key_path

Then configure your PHP-FPM/Apache/container deployment to load this environment file:
  $output_file

Do not put either file below the public web root and do not commit them to Git.
EOF
}

main() {
  local command="${1:-}"
  shift || true
  case "$command" in
    create-service-account) create_service_account "$@" ;;
    verify-play-access) verify_play_access "$@" ;;
    create-company-license-product) create_company_license_product "$@" ;;
    generate-upload-key) generate_upload_key "$@" ;;
    build-play-aab) build_play_aab "$@" ;;
    publish-internal) publish_internal "$@" ;;
    prepare-server-env) prepare_server_env "$@" ;;
    -h|--help|help|"") usage ;;
    *) fail "Unknown command: $command" ;;
  esac
}

main "$@"
