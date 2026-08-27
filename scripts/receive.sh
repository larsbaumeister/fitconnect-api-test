#!/usr/bin/env bash
# Starts the fitconnect-receiver-sample jar with the parameters below.
#
# Edit the variables in the "EDIT ME" section to fit your case. Secrets
# default to environment variables so you can keep them out of this file and
# your shell history, e.g.:
#   export FITCONNECT_RECEIVER_CLIENT_ID="..."
#   export FITCONNECT_RECEIVER_CLIENT_SECRET="..."
#   ./scripts/receive.sh
# but you can just as well hardcode a value below instead of the ${VAR:-...}
# fallback if you prefer.
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
JAR="$SCRIPT_DIR/../receiver/target/fitconnect-receiver-sample.jar"

# Load real secrets from an untracked, gitignored file if present, so they
# never need to be typed into this tracked script. See
# scripts/.env.local.sh.example for the variables it can set.
[[ -f "$SCRIPT_DIR/.env.local.sh" ]] && source "$SCRIPT_DIR/.env.local.sh"

# ---------------------------------------------------------------- EDIT ME ---

CLIENT_ID="${FITCONNECT_RECEIVER_CLIENT_ID:-}"
CLIENT_SECRET="${FITCONNECT_RECEIVER_CLIENT_SECRET:-}"

ENVIRONMENT="TEST"                              # TEST | STAGE | PROD
DESTINATION_ID="${FITCONNECT_DESTINATION_ID:-}" # Zustellpunkt-ID

SIGNING_KEY="$SCRIPT_DIR/../keys/signing_key.json"

# One JWK path per decryption key; more than one supports key rollover.
DECRYPTION_KEYS=(
  "$SCRIPT_DIR/../keys/decryption_key.json"
)

SUBMISSION_ID=""    # optional: fetch only this one instead of listing all
OFFSET=0
LIMIT=100
OUTPUT_DIR="./fitconnect-received"

# After downloading, either accept or reject every submission, or leave both
# false to only download (safe default, nothing is deleted server-side).
ACCEPT=false
REJECT=false
REJECT_PROBLEM="TechnicalError"  # TechnicalError | DataSchemaViolation

# "uri=path" pairs to validate against a local schema file instead of
# fetching it over HTTP. Leave empty to always fetch schemas over HTTP.
LOCAL_SCHEMAS=(
  # "https://schema.test.dev/submission-schema.json=./schemas/submission-schema.json"
)

# Endpoint overrides; leave empty to use the defaults for $ENVIRONMENT.
AUTH_BASE_URL=""
ROUTING_BASE_URL=""
SUBMISSION_BASE_URLS=()
SELF_SERVICE_PORTAL_BASE_URL=""
DESTINATION_BASE_URL=""

ALLOW_INSECURE_PUBLIC_KEY=false
SKIP_SUBMISSION_DATA_VALIDATION=false
DISABLE_AUTO_REJECT=false

CONNECT_TIMEOUT=""  # seconds, default 30
READ_TIMEOUT=""     # seconds, default 30
WRITE_TIMEOUT=""    # seconds, default 30

# ------------------------------------------------------------------------- #

if [[ ! -f "$JAR" ]]; then
  echo "Jar not found at $JAR - build it first with: (cd \"$SCRIPT_DIR/..\" && mvn package)" >&2
  exit 1
fi

if [[ -z "$CLIENT_ID" || -z "$CLIENT_SECRET" || -z "$DESTINATION_ID" ]]; then
  echo "CLIENT_ID/CLIENT_SECRET/DESTINATION_ID are not set - copy" \
       "scripts/.env.local.sh.example to scripts/.env.local.sh and fill it in," \
       "or edit the EDIT ME section of this script directly." >&2
  exit 1
fi

if [[ "$ACCEPT" == true && "$REJECT" == true ]]; then
  echo "ACCEPT and REJECT are mutually exclusive - set at most one to true." >&2
  exit 1
fi

args=(
  --client-id "$CLIENT_ID"
  --client-secret "$CLIENT_SECRET"
  --environment "$ENVIRONMENT"
  --destination-id "$DESTINATION_ID"
  --signing-key "$SIGNING_KEY"
  --offset "$OFFSET"
  --limit "$LIMIT"
  --output-dir "$OUTPUT_DIR"
)

for key in "${DECRYPTION_KEYS[@]:-}"; do
  [[ -n "$key" ]] && args+=(--decryption-key "$key")
done

[[ -n "$SUBMISSION_ID" ]] && args+=(--submission-id "$SUBMISSION_ID")

[[ "$ACCEPT" == true ]] && args+=(--accept)
if [[ "$REJECT" == true ]]; then
  args+=(--reject --reject-problem "$REJECT_PROBLEM")
fi

for mapping in "${LOCAL_SCHEMAS[@]:-}"; do
  [[ -n "$mapping" ]] && args+=(--local-schema "$mapping")
done

[[ -n "$AUTH_BASE_URL" ]] && args+=(--auth-base-url "$AUTH_BASE_URL")
[[ -n "$ROUTING_BASE_URL" ]] && args+=(--routing-base-url "$ROUTING_BASE_URL")
for url in "${SUBMISSION_BASE_URLS[@]:-}"; do
  [[ -n "$url" ]] && args+=(--submission-base-url "$url")
done
[[ -n "$SELF_SERVICE_PORTAL_BASE_URL" ]] && args+=(--self-service-portal-base-url "$SELF_SERVICE_PORTAL_BASE_URL")
[[ -n "$DESTINATION_BASE_URL" ]] && args+=(--destination-base-url "$DESTINATION_BASE_URL")

[[ "$ALLOW_INSECURE_PUBLIC_KEY" == true ]] && args+=(--allow-insecure-public-key)
[[ "$SKIP_SUBMISSION_DATA_VALIDATION" == true ]] && args+=(--skip-submission-data-validation)
[[ "$DISABLE_AUTO_REJECT" == true ]] && args+=(--disable-auto-reject)

[[ -n "$CONNECT_TIMEOUT" ]] && args+=(--connect-timeout "$CONNECT_TIMEOUT")
[[ -n "$READ_TIMEOUT" ]] && args+=(--read-timeout "$READ_TIMEOUT")
[[ -n "$WRITE_TIMEOUT" ]] && args+=(--write-timeout "$WRITE_TIMEOUT")

exec java -jar "$JAR" "${args[@]}"
