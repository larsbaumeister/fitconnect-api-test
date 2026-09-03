# fitko-spring-integration-tests

End-to-end **round-trip** tests for the [`fitko-spring`](../fitko-spring)
starter: a real Spring Boot context with the **real** FIT-Connect SDK clients
(no mocks) that sends submissions to a **live FIT-Connect environment** and
asserts they come back through the background poller.

It is a **separate Maven project** on purpose. The `fitko-spring` build stays
credential-free and offline - `cd fitko-spring && mvn clean test` never sees
this code. These tests need credentials, a destination and keys that not
everyone has, so they live here and are run explicitly.

The suite also consumes `fitko-spring` exactly as a downstream service does -
through its published jar and its `AutoConfiguration.imports` - so it doubles
as a packaging/consumability check.

---

## How it runs

| Command | Effect |
|---|---|
| `cd fitko-spring && mvn clean test` | The library. **Cannot see this project.** Unchanged. |
| `cd fitko-spring-integration-tests && mvn test` | Nothing - there are no `*Test.java` here. |
| `cd fitko-spring-integration-tests && mvn verify` | Runs the `*IT.java` suite via failsafe. Round-trip tests **self-skip** without credentials; `StarterConsumabilityIT` still runs. |
| `mvn verify -DskipITs=true` | Skip failsafe entirely. |
| `mvn verify -Dit.test=OutcomeRoundTripIT` | One class. |

`mvn package` / `mvn install` do **not** reach the `verify` phase with a bare
invocation, so they never run these tests either.

### Prerequisite

`fitko-spring` must be in your local Maven repo:

```bash
cd ../fitko-spring && mvn install
```

---

## Credentials

Everything is read from the environment. Nothing is committed.

### Required for the round-trip tests

| Variable | Meaning |
|---|---|
| `FITCONNECT_SENDER_CLIENT_ID` / `_SECRET` | A sender client from the Self-Service-Portal |
| `FITCONNECT_RECEIVER_CLIENT_ID` / `_SECRET` | A subscriber client from the Self-Service-Portal |
| `FITCONNECT_IT_DESTINATION_ID` | A TEST Zustellpunkt the suite may send to **and** poll |
| `FITCONNECT_IT_SIGNING_KEY` | That destination's private signing key - a Spring resource location (`file:…`, `classpath:…`) **or** an inline JWK JSON string |
| `FITCONNECT_IT_DECRYPTION_KEY` | That destination's private decryption key - same forms |

Without these, the round-trip classes abort in `@BeforeAll` (JUnit reports the
class as skipped / `Tests run: 0`) and `mvn verify` stays green.

### Optional

| Variable | Default | Used by |
|---|---|---|
| `FITCONNECT_ENVIRONMENT` | `TEST` | all - `STAGE` / a custom name also work |
| `FITCONNECT_IT_STRICT` | `false` | `true` makes a missing required variable a **failure** instead of a skip (for an unattended run that must not silently do nothing) |
| `FITCONNECT_IT_SERVICE_ID` + `FITCONNECT_IT_DATA_SCHEMA` | Gewerbeanmeldung + its XZuFi schema | The service id + XML schema URI to send with. The SDK checks both against the destination on send, so override them together for a destination that isn't registered for the default service. |
| `FITCONNECT_IT_SERVICE_REGION` | – | `PlainRoundTripIT.serviceRegionRoundTripsWhenSet` - a region code (`DE…`) the destination's service is registered for. The destination rejects a region it doesn't serve, so this test only runs when set. |
| `FITCONNECT_IT_EMAIL_REPLY_CHANNEL` | `false` | `PlainRoundTripIT.anEmailReplyChannelSurvives` - set `true` only if the destination accepts an e-mail reply channel (many reject one with `unsupported-reply-channel`). |
| `FITCONNECT_IT_JSON_SERVICE_ID` + `FITCONNECT_IT_JSON_DATA_SCHEMA` | – | `PlainRoundTripIT.jsonPayloadRoundTrips` - a JSON service + schema the destination accepts. |
| `FITCONNECT_IT_VALID_PAYLOAD` | – | `SchemaValidationRoundTripIT` - a resource holding a schema-valid instance document |
| `FITCONNECT_IT_DESTINATION2_ID` + `FITCONNECT_IT_DESTINATION2_SIGNING_KEY` + `FITCONNECT_IT_DESTINATION2_DECRYPTION_KEY` | – | `MultiDestinationRoundTripIT` - a second Zustellpunkt with its own key pair |

Setting `FITCONNECT_IT_SERVICE_ID` **and** `FITCONNECT_IT_DATA_SCHEMA` also turns
on `SchemaValidationRoundTripIT` / `...OptOutRoundTripIT` (which additionally
want `FITCONNECT_IT_VALID_PAYLOAD`).

### Getting the fixtures

- A TEST account: <https://docs.fitko.de/fit-connect/docs/getting-started/account>
- Register a destination and generate its key pair:
  <https://docs.fitko.de/fit-connect/docs/receiving/certificate>
- The default service/schema is the Gewerbeanmeldung LeiKa key
  (`urn:de:fim:leika:leistung:99050035001000`) + its XZuFi schema. The
  destination must be registered for whatever `FITCONNECT_IT_SERVICE_ID` /
  `FITCONNECT_IT_DATA_SCHEMA` resolve to - the SDK enforces this on send.
- On first run the suite rejects any pre-existing submissions on the
  destination it cannot decrypt (old key material) so they stop clogging the
  poll cycle.

Example local run:

```bash
export FITCONNECT_SENDER_CLIENT_ID=...   FITCONNECT_SENDER_CLIENT_SECRET=...
export FITCONNECT_RECEIVER_CLIENT_ID=... FITCONNECT_RECEIVER_CLIENT_SECRET=...
export FITCONNECT_IT_DESTINATION_ID=9f6bb611-df46-494a-9a98-a253f1362dc7
export FITCONNECT_IT_SIGNING_KEY=file:./it-keys/signing.json
export FITCONNECT_IT_DECRYPTION_KEY=file:./it-keys/decryption.json
mvn verify
```

(`it-keys/` is git-ignored.)

---

## What the suite covers

| Class | What it proves |
|---|---|
| `StarterConsumabilityIT` *(no credentials)* | The starter auto-configures from `fitconnect.*` alone in a clean external project; the expected beans exist; the jar ships its `AutoConfiguration.imports` and configuration metadata. |
| `PlainRoundTripIT` | XML and JSON payloads round-trip byte-for-byte; service id / mime type / region / case id preserved; a requested reply channel is carried; `accept()` removes the submission. |
| `AttachmentRoundTripIT` | One, several (mixed mime types), and a few-MB attachment round-trip - bytes, filename, mime type intact. |
| `MetadataIdentityRoundTripIT` | An `IdentificationReport` metadata-v2 `dataSet` (with `levelOfAssurance` / `subjectRef` and an auto-computed sha512 hash) round-trips; a requested metadata version is honoured; multiple dataSets all arrive. |
| `OutcomeRoundTripIT` | `accept()` → sender sees `ACCEPTED`, no redelivery; `reject(DataSchemaViolation)` → `REJECTED` with the reason visible to the sender; an unresolved submission is redelivered every cycle until resolved. |
| `DefaultOutcomeRoundTripIT` | `default-outcome=ACCEPT` auto-resolves a submission no listener touched. |
| `RoutingFilteringRoundTripIT` | `@SubmissionEventListener(serviceIds=…)` only fires for its service; an unfiltered `@EventListener` sees everything; `@Order` is respected (audit before resolve). |
| `MultiDestinationRoundTripIT` *(needs 2nd destination)* | One poller, several destinations, each through its own client/keys - a submission for one destination never surfaces as another's; a destination with wrong keys doesn't stop the healthy ones. |
| `SchemaValidationRoundTripIT` *(needs real service+schema)* | With validation + auto-reject **on**, a schema-invalid submission is auto-rejected server-side and never delivered; a schema-valid one round-trips. |
| `SchemaValidationOptOutRoundTripIT` *(needs real service+schema)* | With `skip-submission-data-validation` + `disable-auto-reject` **on**, the same invalid submission **is** delivered and the listener rejects it itself. |
| `PollingSafeguardsRoundTripIT` | `submission-timeout` abandons a stuck submission for the cycle without losing it; `retry-cooldown` throttles a repeatedly-failing submission; `polling.limit` pages a backlog across cycles. |
| `ObservabilityRoundTripIT` | `fitconnect.receive.*` Micrometer meters move on a round trip; the `fitConnectReceiver` health indicator is UP while the poller runs, DOWN once stopped. |
| `SenderErrorIT` / `SenderAuthErrorIT` | A send FIT-Connect can't fulfil (unknown destination, bad `client-secret`, missing destination id) surfaces as `SubmissionSendException` / `IllegalStateException`, not a partial success. |

---

## Design notes

- **Transport focus.** Most classes run with `skip-submission-data-validation`
  and `disable-auto-reject` **on** (`application.yaml`), so they don't need a
  real schema bound to the fixture destination. `SchemaValidation*IT` are the
  only classes that flip them back.
- **Real polling, not `poll()`.** A separate project can't call the
  package-private `SubmissionPollingService.poll()` the way `fitko-spring`'s
  own tests do, so the ITs drive the real background poller
  (`initial-delay: 0s`, `interval: 3s`) and use Awaitility. Round-trip
  timeouts are minutes - FIT-Connect TEST is asynchronous and has no
  availability guarantee.
- **Correlation.** Primary: the `submissionId` returned by `send()`. Every
  payload also embeds a `fitko-spring-it/<Class>/<uuid>` marker, which the
  `@AfterEach` orphan sweep uses to accept (clean up) any suite submission a
  crash left on the destination.
- **Isolation.** `default-outcome=LEAVE` and per-submission id matching mean a
  test only ever resolves its own submission; foreign submissions on the
  shared destination are never touched. No JUnit parallelism; one context per
  class, torn down after the class (`@DirtiesContext`).
- **Not wired into any CI.** These run only when someone runs them, with
  credentials in the environment. `run.sh` (git-ignored) is a convenience
  wrapper that exports them and calls `mvn verify`.
