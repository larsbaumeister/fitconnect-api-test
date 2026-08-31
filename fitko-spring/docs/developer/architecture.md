# Architecture

For humans and agents working on `fitko-spring` itself. For usage docs, see
[../user/guide.md](../user/guide.md).

## Goal

Turn `fitconnect.*` Spring properties into a working FIT-Connect SDK setup:
an injectable `AntragSender` bean, and a Spring `ApplicationEvent` per
submission a background poller (or webhook) downloads. No app code should
ever touch the SDK's `ClientFactory`/`ApplicationConfig` directly.

## Non-goals

Sending replies, the FIT-Connect reply channel, reply pickup via callback,
large/chunked attachments, destination/routing lookup and provisioning, virus
scanning. See [user guide § Out of scope](../user/guide.md#out-of-scope) for
why and where each is handled instead.

## Package layout

| Package | Responsibility |
|---|---|
| `spring` (root) | `FitConnectProperties` — the whole `fitconnect.*` config tree. |
| `spring.autoconfigure` | `@AutoConfiguration` classes. Wiring only, no logic. |
| `spring.config` | `ApplicationConfigFactory`/`MetadataVersions` — properties → SDK types. |
| `spring.send` | Public sending API: `AntragSender`, `AntragToSend`, `AttachmentToSend`, `DataSetToSend`. |
| `spring.receive` | Event API, poller, processor, metrics, health indicator. |
| `spring.receive.callback` | The webhook controller. |

No cyclic dependencies; `send` and `receive` don't depend on each other, only
on `config`/root.

## Auto-configuration chain

One `ApplicationConfig` bean (`FitConnectAutoConfiguration`), gated on
`fitconnect.enabled`. Everything else is `@ConditionalOnBean(ApplicationConfig.class)`
+ `@AutoConfiguration(after = ...)`, so disabling the core cleanly disables
everything downstream — `fitconnect.enabled` is interpreted in exactly one
place.

```
FitConnectAutoConfiguration (core)
 ├─ FitConnectSenderAutoConfiguration        (fitconnect.sender.enabled)
 └─ FitConnectReceiverAutoConfiguration      (fitconnect.receiver.enabled)
     ├─ FitConnectReceiveMetricsAutoConfiguration  (before receiver; needs Micrometer on classpath)
     ├─ FitConnectReceiveHealthAutoConfiguration    (after receiver; needs Actuator health API)
     └─ FitConnectCallbackAutoConfiguration         (after receiver; needs spring-boot-starter-web + callback.enabled=true)
```

Every optional integration (Micrometer, Actuator health, `spring-boot-starter-web`)
is `@ConditionalOnClass`-gated and marked `optional=true` in the POM: a
consumer only pays for what's already on their classpath, and gets no error,
just no bean, when it isn't.

## Key design decisions

- **One `SubscriberClient` per destination.** The SDK bakes one client-id/key
  set into each client instance (verified against `ClientFactory.createSubscriberClient`),
  so `ApplicationConfigFactory` builds one shared `ApplicationConfig`, then
  clones it per destination with its own `SubscriberConfig`
  (`withSubscriberConfig`). That clone goes through the SDK's 10-arg
  `ApplicationConfig` constructor — there's no `toBuilder()`/`withX` on it;
  reordering two same-typed fields there would break this silently, so treat
  it as fragile and pin the verified SDK version in that comment when you
  touch it.
- **Receive API = Spring events.** `AntragReceivedEvent` + `@EventListener`
  gives consumers `@Async`/`@Order`/`@TransactionalEventListener` for free.
  `@AntragEventListener` is a meta-`@EventListener` plus a custom
  `EventListenerFactory` for per-service filtering — it must filter in
  `onApplicationEvent`, not the private `shouldHandle(event, args)` overload;
  overriding the public `shouldHandle(ApplicationEvent)` silently does
  nothing (see `AntragEventListenerFactory` javadoc).
- **Poller = `SmartLifecycle` on its own daemon thread**, not the app's
  `TaskScheduler`. `isAutoStartup()` follows `polling.enabled`. `@PreDestroy`
  and `stop()` are deliberately not both wired to avoid a double-stop.
- **Fail-fast config.** A missing/invalid property throws
  `FitConnectConfigurationException` at context-refresh time, with a
  property-path message — never mid-request.

## Delivery semantics (read before touching receive-side code)

At-least-once, no de-duplication. With the default `default-outcome: LEAVE`,
an unresolved submission is fully re-downloaded, re-decrypted, re-validated,
and re-published on every poll cycle — **listeners must be idempotent**, this
is a hard requirement, not a suggestion.

Auto-reject is on by default (`disable-auto-reject=false`): a
validation/malware/decryption failure inside `requestSubmission` rejects the
submission **server-side, inside the SDK call, before it throws**. So
`SubmissionProcessor`'s catch-block log line ("stays on the delivery service")
can be wrong — the submission may already be gone. Known gap, not yet fixed;
see `code-review.md` M4 before changing that code path.

## Known limitations

- Polling is sequential and single-threaded, across both destinations and
  submissions within one — one slow listener stalls the whole cycle. No
  parallelism knob.
- No retry ceiling, backoff, or dead-letter path for a submission that fails
  every cycle — it's retried forever, same stack trace.
- One page per destination per cycle (`polling.limit`, default 100); a
  backlog beyond that drains at `limit`/`interval`.
- `List<ReceivingDestination>` is published as a bean type — a consumer
  declaring their own single `ReceivingDestination` bean would flip Spring's
  autowiring from "the configured list" to "matching beans" and silently drop
  it. Name-based injection mitigates but doesn't eliminate this.
- `base-urls`/boolean overrides (`allow-insecure-public-key`, etc.) can only
  force a custom environment's default to `true`, never back to `false`
  (`Environment.merge` treats `null` as "fall through").

Full list with severities and file:line references: [`code-review.md`](code-review.md)
(a point-in-time review — check it before re-reporting a finding it already
covers, then note there if something changed).

## Extension points

Every bean is `@ConditionalOnMissingBean` — declare your own of the same type
to replace it: `SenderClient`, `AntragSender`, `SubscriberClientFactory`,
`List<ReceivingDestination>`, `SubmissionProcessor`, `AntragPollingService`,
`ReceivePipelineMetrics`, `FitConnectReceiverHealthIndicator`,
`fitConnectCallbackObjectMapper`.

## Working on this repo

- Standalone Maven module, no `<parent>`: `cd fitko-spring && mvn package`
  builds and runs the full suite on its own. JDK 17+ to build; CI-verified on
  Temurin 25.
- Tests mock the SDK's `SenderClient`/`SubscriberClient` — no network, no
  real key material (`TestJwkKeys` mints throwaway JWKs via the SDK's own
  `TestKeyBuilder`).
- `SampleApplicationYamlTest` binds [`docs/user/application.yaml`](../user/application.yaml)
  against the real `FitConnectProperties`, so a renamed/typo'd property in
  the sample fails the build instead of surfacing as a user's "unknown
  property" surprise. Run tests from the module root (it reads that path
  relative to the working directory).
- Auto-config conditionals: `ApplicationContextRunner`
  (`FitConnectAutoConfigurationTest`). Full wiring: `@SpringBootTest` with
  mocked clients. Callback endpoint: real `MockMvc` HTTP dispatch.

## Notes for agents / new contributors

- `FitConnectProperties`' field javadoc is the single source of truth for
  config docs (`spring-boot-configuration-processor` turns it into IDE
  metadata). Change it first, then mirror into
  [`../user/configuration.md`](../user/configuration.md) and, if the sample
  is affected, [`../user/application.yaml`](../user/application.yaml).
- Respect the package boundaries above — `receive` and `send` don't import
  each other.
- Before touching `ApplicationConfigFactory`, read its class javadoc: it
  documents two SDK-source-verified assumptions (`Environment.merge`'s
  null-means-fall-through semantics; one key set per `SubscriberClient`) the
  rest of the receive side relies on.
- `code-review.md` in this directory lists known gaps with severities — skim
  it before filing a new finding that might already be tracked there.
