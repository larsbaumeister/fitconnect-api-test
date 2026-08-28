# Configuration reference

Every property lives under the `fitconnect` prefix and binds to
[`FitConnectProperties`](../src/main/java/com/gfi/ozg/fitko/spring/FitConnectProperties.java)
via Spring Boot's regular relaxed binding — `application.yml`,
`application.properties`, environment variables
(`FITCONNECT_SENDER_CLIENT_ID`, ...), a config server, whatever your
application already uses. [`application.yaml`](application.yaml) in this
directory is a full, commented example; this page is the property-by-property
reference. IDEs also autocomplete every key below directly from
`META-INF/spring-configuration-metadata.json`, generated at build time from
the same javadoc.

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
`AntragToSend.builder(...).destinationId(...)` call must set one explicitly.
`AntragSender.send(...)` throws `IllegalStateException` immediately if it's
missing, before any network call.

## `fitconnect.receiver.*`

| Property | Type | Default | Required when | Notes |
|---|---|---|---|---|
| `receiver.enabled` | boolean | `true` | — | `false` if this application never receives. |
| `receiver.client-id` | string | — | `receiver.enabled=true`, unless every destination sets its own | Default subscriber client id, issued by the Self-Service-Portal. Used by any destination below that doesn't override it. |
| `receiver.client-secret` | string | — | same as `client-id` | |
| `receiver.destinations` | `List<Destination>` | `[]` | at least one, when `receiver.enabled=true` | Every Zustellpunkt this application polls - see below. One background poller handles the whole list. |
| `receiver.default-outcome` | `LEAVE` \| `ACCEPT` \| `REJECT` | `LEAVE` | — | What happens to a downloaded submission no `@EventListener`/`@AntragEventListener` explicitly resolved. `LEAVE` is the safe default: nothing is deleted server-side, so it's retried next poll. |
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

A `Duration` property accepts a plain suffixed value (`10s`, `5m`, `500ms`)
or ISO-8601 (`PT10S`); a bare number is interpreted as milliseconds.

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
