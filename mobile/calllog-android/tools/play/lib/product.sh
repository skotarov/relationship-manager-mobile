#!/usr/bin/env bash

create_service_account() {
  local project_id="${1:-}"
  local key_output="${2:-}"
  [[ -n "$project_id" ]] || fail "Usage: create-service-account <gcp-project-id> <output-json-path>"
  [[ -n "$key_output" ]] || fail "Usage: create-service-account <gcp-project-id> <output-json-path>"
  [[ "$project_id" =~ ^[a-z][a-z0-9-]{4,28}[a-z0-9]$ ]] || fail "Invalid Google Cloud project ID."

  require_command gcloud
  require_command jq
  require_command install
  require_command python3

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
  printf '%s\n' "$response" | jq --arg package "$PACKAGE_ID" '{access: "ok", package: $package, products: (.inappproduct // [] | map(.sku))}'
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
  require_command python3
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
