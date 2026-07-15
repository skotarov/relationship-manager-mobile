#!/usr/bin/env bash

cleanup() {
  if [[ -n "$TEMP_GCLOUD_CONFIG" && -d "$TEMP_GCLOUD_CONFIG" ]]; then
    rm -rf "$TEMP_GCLOUD_CONFIG"
  fi
}

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
