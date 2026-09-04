# Configuration reference

Every property lives under the `fitconnect` prefix and binds to
[`FitConnectProperties`](../fitko-spring/src/main/java/com/gfi/ozg/fitko/spring/FitConnectProperties.java)
via Spring Boot's regular relaxed binding: `application.yml`,
`application.properties`, environment variables
(`FITCONNECT_SENDER_CLIENT_ID`, ...), a config server, whatever you already use.
[`application.yaml`](application.yaml) is a full, commented example; this page
is the property-by-property reference. IDEs also autocomplete every key below,
from `META-INF/spring-configuration-metadata.json`, generated at build time
from the same javadoc.

## Quick start

Minimal config to send only:

```yaml
fitconnect:
  environment: TEST
  receiver:
    enabled: false
  sender:
    client-id: ${FITCONNECT_SENDER_CLIENT_ID}
    client-secret: ${FITCONNECT_SENDER_CLIENT_SECRET}
```

Minimal config to receive only:

```yaml
fitconnect:
  environment: TEST
  sender:
    enabled: false
  receiver:
    client-id: ${FITCONNECT_RECEIVER_CLIENT_ID}
    client-secret: ${FITCONNECT_RECEIVER_CLIENT_SECRET}
    destinations:
      - id: 9f6bb611-df46-494a-9a98-a253f1362dc7
        signing-key: file:/etc/fitconnect/signing_key.json
        decryption-keys:
          - file:/etc/fitconnect/decryption_key.json
```

An application that does both just combines the two `sender`/`receiver`
blocks, both left `enabled: true` (the default).

## Top level

| Property | Type | Default | Notes |
|---|---|---|---|
| `fitconnect.enabled` | boolean | `true` | Master switch. `false` disables sending, receiving, and the underlying SDK client beans, regardless of the `sender`/`receiver` blocks. |
| `fitconnect.environment` | string | `TEST` | `TEST`, `STAGE`, `PROD`, or a custom environment name registered via `base-urls` below. |

## `fitconnect.sender.*`

| Property | Type | Default | Required when | Notes |
|---|---|---|---|---|
| `sender.enabled` | boolean | `true` | — | `false` if this application never sends. |
| `sender.client-id` | string | — | `sender.enabled=true` | Issued by the [Self-Service-Portal](https://docs.fitko.de/fit-connect/docs/getting-started/account). |
| `sender.client-secret` | string | — | `sender.enabled=true` | |

There is no configured fallback destination for sending — every
`SubmissionToSend.builder(...).destinationId(...)` call must set one explicitly.
`SubmissionSender.send(...)` throws `IllegalStateException` immediately if it's
missing, before any network call.

## `fitconnect.receiver.*`

| Property | Type | Default | Required when | Notes |
|---|---|---|---|---|
| `receiver.enabled` | boolean | `true` | — | `false` if this application never receives. |
| `receiver.client-id` | string | — | `receiver.enabled=true`, unless every destination sets its own | Default subscriber client id, issued by the Self-Service-Portal. Used by any destination below that doesn't override it. |
| `receiver.client-secret` | string | — | same as `client-id` | |
| `receiver.destinations` | `List<Destination>` | `[]` | at least one, when `receiver.enabled=true` | Every Zustellpunkt this application polls - see below. One background poller handles the whole list. |
| `receiver.default-outcome` | `LEAVE` \| `ACCEPT` \| `REJECT` | `LEAVE` | — | What happens to a downloaded submission no `@EventListener`/`@SubmissionEventListener` explicitly resolved. `LEAVE` is the safe default: nothing is deleted server-side, so it's retried next poll. |
| `receiver.allow-insecure-public-key` | boolean | `false` | — | Accepts a self-signed destination certificate. Never enable in PROD; useful only against a local/self-hosted TEST environment. |
| `receiver.skip-submission-data-validation` | boolean | `false` | — | Skips the SDK's local JSON-Schema validation of received submission data. |
| `receiver.disable-auto-reject` | boolean | `false` | — | By default a submission that fails validation is auto-rejected with a `DataSchemaViolation`. Set `true` to leave it on the delivery service instead. |

### `fitconnect.receiver.destinations[]`

A FIT-Connect Zustellpunkt is registered with its own signing/encryption key
pair regardless of which subscriber client polls it, so each destination
carries its own keys - required, even if two destinations happen to reuse
the same key material. Internally this means one SDK `SubscriberClient` per
destination, not one shared client for the whole application.

| Property | Type | Default | Required | Notes |
|---|---|---|---|---|
| `destinations[].id` | UUID | — | always | The Zustellpunkt id to poll. |
| `destinations[].signing-key` | `Resource` | — | always | This destination's private signing key JWK. Any Spring `Resource` location (`file:`, `classpath:`, `https:`, ...) - read as bytes and parsed directly, doesn't need to be a real file on disk. |
| `destinations[].decryption-keys` | `List<Resource>` | `[]` | at least one | This destination's private decryption key JWKs. More than one supports key rollover; the incoming JWE's `kid` picks the right one automatically. |
| `destinations[].client-id` | string | falls back to `receiver.client-id` | only if this destination uses a different Self-Service-Portal registration | |
| `destinations[].client-secret` | string | falls back to `receiver.client-secret` | same as `client-id` | |
| `destinations[].callback-secret` | string | — | only if `receiver.callback.enabled=true` and this destination should receive callbacks | See "`fitconnect.receiver.callback.*`" below. |

Most setups only need one Self-Service-Portal registration polling several
destinations, so `client-id`/`client-secret` are usually left unset per
destination and just set once on `receiver.*`. Set them per destination only
when a destination was registered under a genuinely different client (e.g. a
separate legal entity's own registration).

### `fitconnect.receiver.polling.*`

| Property | Type | Default | Notes |
|---|---|---|---|
| `polling.enabled` | boolean | `true` | `false` to only fetch submissions on demand instead of automatically. |
| `polling.initial-delay` | `Duration` | `5s` | Delay after application startup before the first poll. |
| `polling.interval` | `Duration` | `30s` | Delay between the end of one poll cycle and the start of the next. |
| `polling.limit` | int | `100` | Paging limit per destination per poll cycle. |
| `polling.concurrency` | int | `8` | How many submissions from **one destination's** page are downloaded, decrypted, published and resolved in parallel. Destinations are still polled one after another. Must be ≥ 1; `1` = strictly sequential (the original behaviour). Every safeguard still applies per submission (each gets its own full `submission-timeout` and `retry-cooldown` bookkeeping), and the poll cycle still blocks until the whole page is done, so cycles never overlap and a ShedLock lock still spans the cycle. Because the SDK's `SubscriberClient` is not concurrency-safe, one client is created per unit of concurrency **per destination** (lazily, on first contention) - each increment adds one OAuth login + one schema init per destination. Size it against real payload sizes and the FIT-Connect API's rate limits. |
| `polling.submission-timeout` | `Duration` | `10s` | Max time to download/decrypt/publish/handle *one* submission before it's abandoned for this cycle and counted as a failure. Always on - bounds a hung network call or a blocking bug in a listener so it can't stall the rest of the page. Each in-flight submission runs on its own worker thread with its own full budget. Enforced via `Thread.interrupt()` on a best-effort basis: a listener stuck in an uninterruptible loop keeps its worker thread alive until it eventually returns on its own. |
| `polling.retry-cooldown` | `Duration` | unset (off) | Opt-in. When set, a submission that failed (including a `submission-timeout` timeout) is not re-fetched until this much time has passed - instead of being retried on every single cycle. Nothing is rejected; the submission still just sits on the delivery service. Unset (the default) is the original behaviour: every failure is retried next cycle, forever. |
| `polling.retry-cooldown-cache-name` | string | `fitconnect-retry-cooldown` | Only relevant when `retry-cooldown` is set. Name of the Spring `Cache` the cooldown state (one entry per currently-failing submission id, value = ISO-8601 last-failure timestamp) is kept in. If your application has a `CacheManager`, define a cache of this name there - back it with Redis (TTL ≥ `retry-cooldown`) and the cooldown is shared across replicas. With no `CacheManager`, a self-pruning in-process cache is used and this name is cosmetic. |

A `Duration` property accepts a plain suffixed value (`10s`, `5m`, `500ms`)
or ISO-8601 (`PT10S`); a bare number is interpreted as milliseconds.

**Why both exist:** `submission-timeout` bounds how long one submission may
run before it's abandoned *within* a cycle; `retry-cooldown` bounds how often
a submission that keeps failing gets retried *across* cycles. A submission
that's merely slow but eventually succeeds only ever interacts with the
timeout; a submission that's genuinely broken (corrupt payload, a listener
bug) hits the timeout or fails fast, then `retry-cooldown` (if configured)
stops it from re-consuming a worker on every subsequent cycle. Both apply
independently to each of the `polling.concurrency` submissions in flight.

The cooldown state lives in a Spring `Cache` (see
`polling.retry-cooldown-cache-name`). An entry is removed when the submission
succeeds **and** once its cooldown has elapsed, and the in-process fallback
also prunes by age - so a submission that fails once and then leaves the
delivery service without ever succeeding again does not keep an entry
around.

### `fitconnect.receiver.polling.distributed-lock.*` (optional)

By default every replica of your application polls every destination
independently. Until a submission is accepted or rejected, each replica
lists it and (with `default-outcome: LEAVE`) fully re-downloads,
re-decrypts and re-publishes it on every one of its own cycles - roughly
N times the work for N replicas. Idempotent listeners make this safe, but
it is wasteful.

Add [ShedLock](https://github.com/lukas-krecan/ShedLock) to opt into
one-replica-at-a-time polling:

1. Put `net.javacrumbs.shedlock:shedlock-core` on the classpath (it is an
   optional dependency of this starter).
2. Declare a `net.javacrumbs.shedlock.core.LockProvider` bean - you pick the
   backend (JDBC, Redis, Mongo, ...); see the ShedLock docs.

The poller then acquires one lock per poll cycle; a replica that cannot get
the lock skips that cycle and tries again next `interval`. The lock name is
derived from the configured destination-id set, so two applications polling
**different** destinations never block each other. The callback webhook
endpoint is never gated by this lock, and nothing happens when
`polling.enabled=false`.

| Property | Type | Default | Notes |
|---|---|---|---|
| `polling.distributed-lock.enabled` | boolean | `true` | `false` keeps ShedLock on the classpath but does not gate polling with it. Has no effect unless a `LockProvider` bean is present. |
| `polling.distributed-lock.lock-at-most-for` | `Duration` | `10 × interval` | Safety-net upper bound: how long the lock stays held if the replica holding it dies mid-cycle without releasing it. Too low lets a second replica start overlapping a legitimately long cycle; too high stalls polling fleet-wide after a hard crash. |
| `polling.distributed-lock.lock-at-least-for` | `Duration` | `interval` | Lower bound the lock is held for even when a cycle finishes sooner - this is what actually spaces polls out across the fleet. Setting it larger than `interval` effectively pins polling to one replica. |

### Observability of the receive pipeline (optional)

The poller only logs per-destination failures at `WARN` and keeps going, so
"polling is healthy but idle" and "polling has been failing for an hour" look
the same in the logs. Two optional integrations close that gap; both activate
only when their library is already on the classpath and contribute nothing
otherwise.

**Micrometer metrics** — active when `micrometer-core` is present (it is,
transitively, in any application using `spring-boot-starter-actuator`). All
are tagged `destination` with the Zustellpunkt id:

| Meter | Type | Meaning |
|---|---|---|
| `fitconnect.receive.poll` | timer | Poll cycles per destination, additionally tagged `outcome=success\|failure`. Count + total time. |
| `fitconnect.receive.submissions.found` | counter | Submissions listed as available by a poll. |
| `fitconnect.receive.submissions.processed` | counter | Submissions downloaded and published without error. |
| `fitconnect.receive.submissions.failed` | counter | Submissions whose download/publish threw (left on the delivery service). |

These meters are **per instance**. With several replicas, aggregate them in
your monitoring backend — add an `instance`/`pod` tag and use `sum by
(destination)` (or `max`, for the timer count). Note that with the default
`default-outcome: LEAVE` the `submissions.*` counters measure *pipeline work*
(a submission left unresolved is re-found and re-processed every cycle by
every replica), not distinct submissions. Enabling
`polling.distributed-lock.*` removes the per-replica multiplication (only one
replica polls per cycle). Fleet-wide totals are the monitoring backend's job
— aggregate the per-instance meters there (e.g. in Grafana).

**Health indicator** — active when Spring Boot Actuator's health API
(`spring-boot-health`) is present. Adds a `fitConnectReceiver` entry to
`/actuator/health`. It is a plain liveness signal for **this instance's**
poller thread — it does not judge FIT-Connect, other replicas, or whether
submissions are flowing (that is what the meters above are for):

- `UP` — the poller is running, or `polling.enabled=false` (callback-only
  mode; nothing to run). `details.polling` is `running` or `disabled`.
- `DOWN` — polling is enabled but the poller is not running: it failed to
  start, or has stopped. `details.polling` is `stopped`.

Disable it like any indicator with
`management.health.fit-connect-receiver.enabled=false`.

For "is polling actually succeeding?", alert on the meters instead — e.g.
`fitconnect.receive.poll` with `outcome=failure` climbing, or no
`outcome=success` samples for a while.

## `fitconnect.receiver.callback.*` (optional)

An alternative or complement to polling: instead of waiting for the next
poll cycle, FIT-Connect pushes an HTTP POST to a URL you register per
destination as soon as a new submission is available. Off by default.

| Property | Type | Default | Notes |
|---|---|---|---|
| `callback.enabled` | boolean | `false` | Registers the webhook endpoint. Requires `spring-boot-starter-web` on the classpath (an optional dependency of this starter - `FitConnectCallbackAutoConfiguration` simply stays off with no error if it isn't present). |
| `callback.path` | string | `/fitconnect/callback` | Base path the endpoint is mapped to; the destination id is always appended, e.g. the default value maps `POST /fitconnect/callback/<destinationId>`. |

Turning this on only *exposes* the endpoint - each destination still needs
its own `destinations[].callback-secret` (see above) before it actually
accepts callbacks (a request for a destination without one gets `404`). A
destination is still polled normally regardless of
whether it also has a callback secret set - the two delivery mechanisms are
independent, and a missed or failed callback is simply picked up on the next
poll cycle instead of being lost.

FIT-Connect's callback is a *notification*, not a delivery: the POST body
just lists which submissions are waiting (same as a poll response), fetched
and decrypted the normal way through the matching `SubscriberClient` once
it's authenticated - by the SDK's own HMAC scheme (`SubscriberClient#validateCallback`),
covering the `callback-authentication`/`callback-timestamp` headers FIT-Connect
sends with every request. Registering the endpoint's URL as the destination's
`Callback` with FIT-Connect (via `DestinationClient`, using the same secret
configured here) is a separate, one-time provisioning step outside this
starter's scope - same as `RouterClient`/`DestinationClient` generally, see
["Out of scope"](user-guide.md#out-of-scope) in the user guide.

## `fitconnect.http.*` (optional)

Unset values keep the SDK's own default (30s each).

| Property | Type | Default |
|---|---|---|
| `http.connect-timeout` | `Duration` | SDK default (30s) |
| `http.read-timeout` | `Duration` | SDK default (30s) |
| `http.write-timeout` | `Duration` | SDK default (30s) |

## `fitconnect.base-urls.*` (optional, advanced)

Endpoint overrides for `fitconnect.environment`; each only takes effect for
the environment currently selected. Leave this block out entirely for a
normal setup against FIT-Connect's real `TEST`/`STAGE`/`PROD` endpoints — it
exists mainly to point the SDK at a local stub server in tests, or a
self-hosted environment.

| Property | Type |
|---|---|
| `base-urls.auth` | string |
| `base-urls.routing` | string |
| `base-urls.submission` | `List<String>` |
| `base-urls.self-service-portal` | string |
| `base-urls.destination` | string |
