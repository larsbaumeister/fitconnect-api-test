# FIT-Connect Java SDK Samples

Two standalone, framework-free command-line clients demonstrating the
[FIT-Connect Java SDK](https://docs.fitko.de/fit-connect/docs/sdks/java-sdk/overview):

- **`fitconnect-sender-sample`** — an "Onlinedienst": builds one submission
  from CLI-supplied data/attachments and sends it to a FIT-Connect
  destination.
- **`fitconnect-receiver-sample`** — a "Verwaltungssystem": polls a
  destination for submissions, decrypts and saves them to disk, and
  optionally accepts or rejects them.

No application framework, DI container, or CLI-parsing library is used —
only the FIT-Connect SDK, plain JDK, and a small logging binding
(`slf4j-simple`) so the SDK's own log output is visible. Every credential,
key path, and endpoint is passed in as a command line argument; nothing is
read from a config file.

## Project layout

```
java-samples/
├── pom.xml                     # reactor + shared dependency/plugin versions
├── common/                     # shared CLI-argument parsing & YAML config helpers
├── sender/                     # the sending sample (SenderApp)
└── receiver/                   # the receiving sample (ReceiverApp)
```

`common` exists because both apps parse the same set of
environment/HTTP/schema override options and build the same shape of YAML
config for `ApplicationConfigLoader`; everything sender- or receiver-specific
(building a `SendableSubmission`, persisting a `ReceivedSubmission`, ...)
lives in its own module.

Internally, each app turns its parsed CLI options into the small YAML
document the SDK already knows how to load
(`ApplicationConfigLoader.loadConfigFromYamlString`), rather than
re-implementing the SDK's own environment-default and validation logic with
a Java config builder.

## Prerequisites

- JDK 11 or newer
- Maven 3.6+
- A sender and/or subscriber client (`clientId` / `clientSecret`) registered
  in the FIT-Connect [Self-Service-Portal](https://docs.fitko.de/fit-connect/docs/getting-started/account)
- For the receiver: a signing key and at least one decryption key as JWKs
  (see [Zertifikate](https://docs.fitko.de/fit-connect/docs/receiving/certificate))

## Build

```bash
cd java-samples
mvn package
```

This produces one executable "uber jar" per app:

```
sender/target/fitconnect-sender-sample.jar
receiver/target/fitconnect-receiver-sample.jar
```

## Sending a submission

```bash
java -jar sender/target/fitconnect-sender-sample.jar \
  --client-id "<sender-client-id>" \
  --client-secret "<sender-client-secret>" \
  --environment TEST \
  --destination-id "d2d43892-9d9c-4630-980a-5af341179b14" \
  --service-id "urn:de:fim:leika:leistung:99900000000000" \
  --service-name "FIT-Connect Demo" \
  --data '{"message":"Hello World"}' \
  --data-schema "https://schema.test.dev/submission-schema.json" \
  --attachment "./invoice.pdf;application/pdf"
```

Run with `--help` for the full option list. Highlights:

- `--data <json>` or `--data-file <path>` — pick exactly one.
- `--data-format <json|xml>` — format of `--data`/`--data-file` (default: `json`);
  match whatever the destination's configured service schema expects.
- `--attachment path;mimeType[;displayName]` — repeatable.
- `--case-id <uuid>` — append the submission to an existing case (e.g. a
  reply to a prior BiDiKo exchange).
- `--reply-channel-email <email>` — ask the receiver to reply by e-mail.
  (FIT-Connect as a reply channel needs an ephemeral key pair from
  `ReplyChannelKeyGenerator` and is intentionally out of scope for this
  sample — see [Bidirektionale Kommunikation](https://docs.fitko.de/fit-connect/docs/sdks/java-sdk/sender#bidirektionale-kommunikation-bidiko)
  if you want to extend it.)
- `--reply-channel-elster-account-id <id>` (+ optional
  `--reply-channel-elster-delivery-ticket`/`--reply-channel-elster-reference`)
  — ask the receiver to reply via ELSTER to this account.
- `--reply-channel-bundid-mailbox <uuid>` — ask the receiver to reply to a
  citizen's BundID/DeutschlandID Postfach; the UUID is that citizen's
  Postkorb-Handle (they find it in their BundID account under "Zugänge &
  Daten" → "Betroffenenauskunft"). The three `--reply-channel-*` flag groups
  are mutually exclusive — FIT-Connect's metadata schema allows at most one
  reply channel per submission.
- `--id-bund-de-application-id <uuid>` — the application id from a BundID
  login, used later to correlate a Statusmeldung sent to the BundID
  Statusmonitor (see [FIT-Connect und das ZBP](https://docs.fitko.de/fit-connect/docs/zbp/zbp)).
- `--data-set schemaUri;mimeType;content` / `--data-set-file schemaUri;mimeType;path`
  — attach a generic `dataSet` (metadata v2.x+ only; repeatable). This is
  FIT-Connect's schema-agnostic slot for information it has no dedicated
  field for. FIT-Connect only transports `content` (the sample computes
  the required sha512 integrity hash for you), it never interprets it. (On
  metadata v1.x, the equivalent field is `authenticationInformation`,
  settable via the SDK's `setAuthenticationInformation`, not currently
  exposed as a CLI flag here.)
  - **This is also where a trust level/Vertrauensniveau travels.**
    FIT-Connect has no dedicated field for it, but for exactly this case
    there is a standard schema both sides can rely on instead of inventing
    one: [Governikus `IdentificationReport`](https://github.com/Governikus/IdentificationReport)
    (`schemaUri`: `https://raw.githubusercontent.com/Governikus/IdentificationReport/2.0.0/schema/identification-report.json`,
    `mimeType`: `application/json`). Its `levelOfAssurance` field is exactly
    the eIDAS/BSI trust level (`http://eidas.europa.eu/LoA/{low,substantial,high}`
    or `http://bsi.bund.de/eID/LoA/{normal,substanziell,hoch}` for the
    German eID scheme), alongside `trustFramework` (e.g. `"eid"`, `"eidas"`),
    `idStatus`, and a `subjectRef` identity reference. Verified end-to-end
    with this sample: `--data-set
    "https://raw.githubusercontent.com/Governikus/IdentificationReport/2.0.0/schema/identification-report.json;application/json;{\"reportId\":\"...\",\"levelOfAssurance\":\"http://bsi.bund.de/eID/LoA/hoch\",...}"`.
    Whether the *sender* you actually receive from populates this is up to
    them — nothing in FIT-Connect requires it.
- `--local-schema uri=path` — validate against a local schema file instead of
  fetching it over HTTP; repeatable.
- `--metadata-version <x.y.z>` — force a metadata schema version instead of
  letting the SDK auto-negotiate with the destination. Useful if a
  destination's *newest* supported metadata version is newer than what your
  SDK release understands (check with `GET /v2/destinations/{id}` — see
  [Zustellpunkt-Informationen](https://docs.fitko.de/fit-connect/docs/sending/get-destination));
  the destination must also be configured to accept the version you force.
- `--auth-base-url`, `--routing-base-url`, `--submission-base-url`,
  `--self-service-portal-base-url`, `--destination-base-url` — override any
  of the environment's endpoints (e.g. to point at a self-hosted or custom
  environment); each only takes effect for the `--environment` you selected.
- `--allow-insecure-public-key`, `--skip-submission-data-validation`,
  `--disable-auto-reject` — same environment-level switches described in the
  [SDK docs](https://docs.fitko.de/fit-connect/docs/sdks/java-sdk/overview#uebersicht-optionaler-properties).

On success the app prints the new `submissionId`, `caseId`, `destinationId`,
and the current status fetched via `SenderClient.getSubmissionStatus`.

## Receiving submissions

```bash
java -jar receiver/target/fitconnect-receiver-sample.jar \
  --client-id "<subscriber-client-id>" \
  --client-secret "<subscriber-client-secret>" \
  --environment TEST \
  --destination-id "d2d43892-9d9c-4630-980a-5af341179b14" \
  --signing-key "./keys/signing_key.json" \
  --decryption-key "./keys/decryption_key.json" \
  --output-dir "./received" \
  --accept
```

Run with `--help` for the full option list. Highlights:

- With no `--submission-id`, the app lists everything currently available
  for pickup at `--destination-id` (`--offset`/`--limit` control paging) and
  processes each one; with `--submission-id`, it fetches only that one.
- Each submission is written to `<output-dir>/<submissionId>/`: the
  (decrypted) payload as `data`, a flat `metadata.properties`, any
  attachments under `attachments/`, and a `full-response.json` containing
  everything the SDK's `ReceivedSubmission` exposes (full metadata object,
  service type, region, application date, attachment details, ...) - useful
  for seeing the complete shape of what a receiver gets back, beyond the
  handful of fields `metadata.properties` picks out.
- `metadata.properties` also surfaces the applicant/user references
  FIT-Connect can actually carry, when the sender provided them: a chosen
  `replyChannelType` and its details (`elsterAccountId`, `bundIdMailboxUuid`,
  `replyChannelEmail`, ...), `idBundDeApplicationId`, and any generic
  `dataSet[i].*` entries (metadata v2.x+) or `authenticationInformation[i].*`
  entries (metadata v1.x) the sender attached. **Everything else about the
  applicant (name, address, ...) lives in the service-specific Fachdaten**
  (the `data` file) per that service's own schema - FIT-Connect's own
  metadata does not carry it. See
  [Metadaten Überblick](https://docs.fitko.de/fit-connect/docs/metadata/overview)
  and [Rückkanal](https://docs.fitko.de/fit-connect/docs/metadata/replyChannel).
- **There is no dedicated "trust level"/Vertrauensniveau field.** If you need
  the eIDAS/BundID assurance level a citizen authenticated with, the sending
  Onlinedienst has to deliberately place it in a `dataSet` (or
  `authenticationInformation` on v1.x) — see the `IdentificationReport`
  schema noted under "Sending a submission" above for the standard way to do
  this. FIT-Connect transports it as an opaque blob and does not validate or
  standardize its contents itself. Whether a given sender actually populates
  this is entirely up to that sender/service, not something FIT-Connect
  guarantees.
- `--decryption-key` is repeatable, supporting the SDK's key-rollover feature
  (the matching key is picked by `kid` automatically).
- **By default nothing is accepted or rejected** — submissions are only
  downloaded, so re-running the app without `--accept`/`--reject` is safe.
  Pass `--accept` to acknowledge and delete each downloaded submission from
  the delivery service, or `--reject --reject-problem <name>` to reject it
  instead (`TechnicalError` or `DataSchemaViolation`; extend
  `ProblemFactory` for more specific rejection reasons).
- The same `--auth-base-url`/.../`--disable-auto-reject`/`--local-schema`
  overrides from the sender are available here too.

## What is intentionally out of scope

To keep both samples focused and easy to read, a few SDK features are not
wired up here; see the linked docs if you need them:

- **Sending replies back** (`SubscriberClient.sendReply`) and the
  **FIT-Connect reply channel** (`ReplyChannel.ofFitConnect`) — see
  [Bidirektionale Kommunikation](https://docs.fitko.de/fit-connect/docs/sdks/java-sdk/receiver#bidirektionale-kommunikation).
- **Large/chunked attachments** (`Attachment.fromLargeAttachment`) for files
  that don't fit in memory — see
  [Übertragung großer Attachments](https://docs.fitko.de/fit-connect/docs/sdks/java-sdk/sender#übertragung-großer-attachments).
- **Destination/routing lookup** (`RouterClient`, `DestinationClient`) — both
  samples take `--destination-id` directly; see
  [Routing-Informationen](https://docs.fitko.de/fit-connect/docs/sdks/java-sdk/sender#routing-informationen)
  if you need to resolve one from a LeiKa key and region.
- **Virus scanning** — defaults to the SDK's no-op scanner; see the
  [SDK overview](https://docs.fitko.de/fit-connect/docs/sdks/java-sdk/overview#virenscanner)
  to wire up ClamAV or ICAP.

## A note on secrets on the command line

Passing `--client-secret` directly on the command line works, but keep in
mind that arguments are visible to other local users via `ps` and are easy
to leak into shell history. Prefer invoking these jars from a script that
reads secrets from environment variables you control, e.g.
`--client-secret "$FITCONNECT_CLIENT_SECRET"`, and avoid typing secrets
directly into an interactive shell.

`scripts/send.sh` and `scripts/receive.sh` follow exactly this pattern:
copy `scripts/.env.local.sh.example` to `scripts/.env.local.sh` (already
gitignored - it's never committed) and fill in your real client id/secret
and destination id. Both scripts source it automatically if it's present,
so real credentials never need to be typed into a tracked file. The two
JWK key files the receiver needs (`--signing-key`/`--decryption-key`) are
never committed either - the whole `keys/` directory is gitignored, so
adding your own JWKs there is safe.

## Verifying the setup without real credentials

Both apps fail fast and cleanly if credentials are wrong: running the
sender against the `TEST` environment with a bogus `--client-id`/
`--client-secret` reaches the real OAuth endpoint and reports
`invalid_client`; running the receiver with a nonexistent `--signing-key`/
`--decryption-key` path reports the unreadable file. Either is a quick way
to confirm the jar and your network path to FIT-Connect both work before
plugging in real credentials.
