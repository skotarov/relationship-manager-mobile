#!/usr/bin/env bash
# Relationship Manager Play release helper.
# Run with: bash tools/play/playctl.sh <command> [arguments]
# No passwords, service-account JSON files, AABs or generated signing properties are committed.

set -Eeuo pipefail
IFS=$'\n\t'

readonly PACKAGE_ID="${RELATIONSHIP_MANAGER_PLAY_PACKAGE_ID:-com.onlineimoti.relationshipmanager}"
readonly PRODUCT_ID="${RELATIONSHIP_MANAGER_PLAY_PRODUCT_ID:-rm_company_license}"
readonly API_BASE="https://androidpublisher.googleapis.com/androidpublisher/v3"
readonly SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
readonly REPO_ROOT="$(cd "$SCRIPT_DIR/../../../.." && pwd)"
readonly APP_DIR="$REPO_ROOT/mobile/calllog-android"

TEMP_GCLOUD_CONFIG=""
ACCESS_TOKEN=""

# shellcheck source=tools/play/lib/common.sh
source "$SCRIPT_DIR/lib/common.sh"
# shellcheck source=tools/play/lib/product.sh
source "$SCRIPT_DIR/lib/product.sh"
# shellcheck source=tools/play/lib/release.sh
source "$SCRIPT_DIR/lib/release.sh"

trap cleanup EXIT

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
