#!/usr/bin/env bash
# Starts the fitconnect-sender-sample jar with the parameters below.
#
# Edit the variables in the "EDIT ME" section to fit your case. Secrets
# default to environment variables so you can keep them out of this file and
# your shell history, e.g.:
#   export FITCONNECT_SENDER_CLIENT_ID="..."
#   export FITCONNECT_SENDER_CLIENT_SECRET="..."
#   ./scripts/send.sh
# but you can just as well hardcode a value below instead of the ${VAR:-...}
# fallback if you prefer.
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
JAR="$SCRIPT_DIR/../sender/target/fitconnect-sender-sample.jar"

# Load real secrets from an untracked, gitignored file if present, so they
# never need to be typed into this tracked script. See
# scripts/.env.local.sh.example for the variables it can set.
[[ -f "$SCRIPT_DIR/.env.local.sh" ]] && source "$SCRIPT_DIR/.env.local.sh"

# ---------------------------------------------------------------- EDIT ME ---

CLIENT_ID="${FITCONNECT_SENDER_CLIENT_ID:-}"
CLIENT_SECRET="${FITCONNECT_SENDER_CLIENT_SECRET:-}"

ENVIRONMENT="TEST"                              # TEST | STAGE | PROD
DESTINATION_ID="${FITCONNECT_DESTINATION_ID:-}" # Zustellpunkt-ID

SERVICE_ID="urn:de:fim:leika:leistung:99050035001000"
SERVICE_NAME="FIT-Connect Demo"
SERVICE_REGION=""                                      # optional, e.g. DE01010

CASE_ID=""              # optional: UUID to append to an existing case
REPLY_CHANNEL_EMAIL=""  # optional: ask the receiver to reply by e-mail

# Force a metadata schema version instead of auto-negotiating with the
# destination (e.g. "2.1.0"). Only needed if the destination's newest
# supported version is newer than this SDK release understands - and only
# works if the destination is also configured to accept the version you
# force. Leave empty to auto-negotiate.
METADATA_VERSION="2.1.0"

# Exactly one of DATA / DATA_FILE must be set. Must match DATA_FORMAT and the
# service's configured submissionSchemas entry (check with:
#   curl https://test.fit-connect.fitko.dev/submission-api/v2/destinations/$DESTINATION_ID
# ).
DATA_FORMAT="xml"  # json | xml
DATA='<test>Hello World</test>'
DATA_FILE=""
DATA_SCHEMA="https://fimportal.de/api/v0/leistung-steckbriefe/99050035001000/xzufi"

# One entry per attachment: "path;mimeType[;displayName]". Leave empty for none.
ATTACHMENTS=(
  # "./invoice.pdf;application/pdf"
)

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
# The dummy DATA above does not conform to the real xzufi schema, so local
# validation is skipped for this smoke test; set back to false once DATA is
# a real, schema-valid document.
SKIP_SUBMISSION_DATA_VALIDATION=true
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

args=(
  --client-id "$CLIENT_ID"
  --client-secret "$CLIENT_SECRET"
  --environment "$ENVIRONMENT"
  --destination-id "$DESTINATION_ID"
  --service-id "$SERVICE_ID"
  --service-name "$SERVICE_NAME"
  --data-schema "$DATA_SCHEMA"
)

[[ -n "$SERVICE_REGION" ]] && args+=(--service-region "$SERVICE_REGION")
[[ -n "$CASE_ID" ]] && args+=(--case-id "$CASE_ID")
[[ -n "$REPLY_CHANNEL_EMAIL" ]] && args+=(--reply-channel-email "$REPLY_CHANNEL_EMAIL")
[[ -n "$METADATA_VERSION" ]] && args+=(--metadata-version "$METADATA_VERSION")

args+=(--data-format "$DATA_FORMAT")
if [[ -n "$DATA_FILE" ]]; then
  args+=(--data-file "$DATA_FILE")
else
  args+=(--data "$DATA")
fi

for attachment in "${ATTACHMENTS[@]:-}"; do
  [[ -n "$attachment" ]] && args+=(--attachment "$attachment")
done

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
