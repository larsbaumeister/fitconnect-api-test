# Architecture

For humans and agents working on `fitko-spring` itself. For usage docs, see
[user-guide.md](user-guide.md).

## Goal

Turn `fitconnect.*` Spring properties into a working FIT-Connect SDK setup:
an injectable `SubmissionSender` bean, and a Spring `ApplicationEvent` per
submission a background poller (or webhook) downloads. No app code should
ever touch the SDK's `ClientFactory`/`ApplicationConfig` directly.

## Non-goals

Sending replies, the FIT-Connect reply channel, reply pickup via callback,
large/chunked attachments, destination/routing lookup and provisioning, virus
scanning. See [user guide § Out of scope](user-guide.md#out-of-scope) for
why and where each is handled instead.

## Package layout

| Package | Responsibility |
|---|---|
| `spring` (root) | `FitConnectProperties` — the whole `fitconnect.*` config tree. |
| `spring.autoconfigure` | `@AutoConfiguration` classes. Wiring only, no logic. |
| `spring.config` | `ApplicationConfigFactory`/`MetadataVersions` — properties → SDK types. |
| `spring.send` | Public sending API: `SubmissionSender`, `SubmissionToSend`, `AttachmentToSend`, `DataSetToSend`. |
| `spring.receive` | Core receive flow: the event API (`SubmissionReceivedEvent`, `@SubmissionEventListener`, `IncomingSubmission`), the poller (`SubmissionPollingService`, `PollCycleGate`, `ShedLockPollCycleGate`), and `SubmissionProcessor`/`DefaultOutcome`. |
| `spring.receive.destination` | `ReceivingDestination`(`s`) + `SubscriberClientFactory` — which Zustellpunkte this app receives on, and the SDK-client seam. |
| `spring.receive.metrics` | `ReceivePipelineMetrics` and its impls (Micrometer, Redis fleet, composite). Optional, Micrometer-gated. |
| `spring.receive.cooldown` | `RetryCooldownStore` + the `Cache`-backed / in-process-fallback impls for `polling.retry-cooldown`. |
| `spring.receive.health` | `FitConnectReceiverHealthIndicator` — the `fitConnectReceiver` Actuator contributor. |
| `spring.receive.callback` | The webhook controller. |

No cyclic dependencies; `send` and `receive` don't depend on each other, only
on `config`/root. Within `receive`, the sub-packages (`destination`, `metrics`,
`cooldown`, `health`, `callback`) are the pluggable concerns each auto-config
wires in; the root depends on them, never the reverse.

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
     ├─ FitConnectReceiveSharedMetricsAutoConfiguration (before receiver; needs Micrometer + Spring Data Redis + shared-metrics.enabled=true)
     ├─ FitConnectPollLockAutoConfiguration         (before receiver; needs shedlock-core + a LockProvider bean)
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
- **Receive API = Spring events.** `SubmissionReceivedEvent` + `@EventListener`
  gives consumers `@Async`/`@Order`/`@TransactionalEventListener` for free.
  `@SubmissionEventListener` is a meta-`@EventListener` plus a custom
  `EventListenerFactory` for per-service filtering — it must filter in
  `onApplicationEvent`, not the private `shouldHandle(event, args)` overload;
  overriding the public `shouldHandle(ApplicationEvent)` silently does
  nothing (see `SubmissionEventListenerFactory` javadoc).
- **Poller = `SmartLifecycle` on its own daemon thread**, not the app's
  `TaskScheduler`. `isAutoStartup()` follows `polling.enabled`. `@PreDestroy`
  and `stop()` are deliberately not both wired to avoid a double-stop.
- **Retry-cooldown state = a Spring `Cache`, not an in-memory map.**
  `RetryCooldownStore` (default `CacheRetryCooldownStore`) keeps one entry per
  currently-failing submission id in a `Cache` named `fitconnect-retry-cooldown`
  - a consumer's `CacheManager` (Redis, ...) if there is one, so the cooldown
    is shared across replicas; otherwise `ExpiringConcurrentMapCache`, a
    self-pruning in-process fallback (no `@EnableCaching`, no extra
    dependency). The "has the cooldown elapsed?" check is still an explicit
    `lastFailure + cooldown` comparison in code, so correctness never depends
    on the cache honouring a TTL. With `polling.retry-cooldown` unset the
    poller gets `RetryCooldownStore.NONE` and this is all inert.
- **Multi-replica poll coordination = programmatic ShedLock behind
  `PollCycleGate`.** `PollCycleGate.DIRECT` (run the cycle immediately) unless
  `FitConnectPollLockAutoConfiguration` is active - `@ConditionalOnClass(LockProvider)`
  plus a consumer `LockProvider` bean - in which case `ShedLockPollCycleGate`
  holds one lock per poll cycle so only one replica polls at a time. The
  poller depends only on the `PollCycleGate` interface, never on
  `net.javacrumbs.shedlock`. Callbacks are never gated.
- **Metrics fan-out.** `ReceivePipelineMetrics` can have several
  implementations wired at once - the per-instance Micrometer meters and,
  opt-in, `RedisReceivePipelineMetrics` (shared Redis counters +
  `fitconnect.receive.fleet.*` gauges, from
  `FitConnectReceiveSharedMetricsAutoConfiguration`, gated on
  `shared-metrics.enabled=true` and Spring Data Redis). `resolveMetrics(...)`
  in the receiver auto-config collects every non-`NOOP` bean and wraps them
  in `CompositeReceivePipelineMetrics`; one impl throwing never aborts the
  poll cycle or the other impls.
- **Fail-fast config.** A missing/invalid property throws
  `FitConnectConfigurationException` at context-refresh time, with a
  property-path message — never mid-request.
- **`ReceivingDestinations`, not `List<ReceivingDestination>`, as the bean
  type.** A raw `List<T>` bean is a Spring footgun: `List<T>` injection points
  are special-cased to mean "collect every bean of type `T`", and that branch
  runs unconditionally, never considering a bean whose own type happens to be
  `List<T>`. So the moment any `ReceivingDestination`-typed bean exists
  anywhere in the context — a consumer's own, unrelated-looking `@Bean` — every
  injection point would silently see only that stray bean instead of the
  configured destinations, no error or warning ever (name-based injection does
  **not** help - verified experimentally, see `ReceivingDestinations`'
  javadoc). `SubmissionPollingService` and `FitConnectCallbackController` both
  take `ReceivingDestinations` for exactly this reason - don't revert either
  back to a raw `List<ReceivingDestination>` parameter.

## Delivery semantics (read before touching receive-side code)

At-least-once, no de-duplication. With the default `default-outcome: LEAVE`,
an unresolved submission is fully re-downloaded, re-decrypted, re-validated,
and re-published on every poll cycle — **listeners must be idempotent**, this
is a hard requirement, not a suggestion. The optional ShedLock gate (see
"Key design decisions") cuts the cross-replica ~N× re-download when running
several replicas, but does not change the at-least-once / no-dedup contract.

Auto-reject is on by default (`disable-auto-reject=false`): a
validation/malware/decryption failure inside `requestSubmission` rejects the
submission **server-side, inside the SDK call, before it throws**. So
`SubmissionProcessor`'s catch-block log line ("stays on the delivery service")
can be wrong — the submission may already be gone. Known gap, not yet fixed:
soften or split that message before relying on it when debugging a delivery.

## Known limitations

- Polling is sequential and single-threaded across destinations and across
  submissions within one — no parallelism knob. Two safeguards bound the
  damage one bad submission can do without changing that model:
  `polling.submission-timeout` (default 10s, always on) abandons a
  submission that stalls the cycle instead of hanging it indefinitely, and
  `polling.retry-cooldown` (unset by default) — when configured — stops a
  submission that fails every cycle from being retried every single cycle.
  See `polling.submission-timeout`/`polling.retry-cooldown` in
  [configuration.md](configuration.md). There is still no
  way to run independent submissions concurrently within a cycle.
- One page per destination per cycle (`polling.limit`, default 100); a
  backlog beyond that drains at `limit`/`interval`.
- Multi-replica polling amplifies redelivery (~N× re-download for N
  replicas). Mitigable, opt-in, via `polling.distributed-lock.*` (ShedLock +
  a `LockProvider` bean) — but best-effort: a `LockProvider` outage falls
  back to every replica polling, and idempotent listeners are still
  required. It coordinates whole cycles, not individual submissions.
- `base-urls`/boolean overrides (`allow-insecure-public-key`, etc.) can only
  force a custom environment's default to `true`, never back to `false`
  (`Environment.merge` treats `null` as "fall through").
- The callback webhook endpoint is servlet-only by design
  (`@ConditionalOnWebApplication(type = SERVLET)`, blocking
  `SubmissionProcessor.process` inline). Sending, polling-based receiving,
  metrics and health have no web dependency at all and run on WebFlux or no
  web layer; a reactive callback endpoint is possible but out of scope.
- `SubmissionProcessor.process` catches every `RuntimeException` and logs
  *"stays on the delivery service"*, which can be wrong when the SDK
  auto-rejected server-side first (see "Delivery semantics" above).
- `ApplicationConfigFactory.withSubscriberConfig` rebuilds the SDK's
  `ApplicationConfig` through its positional 10-arg constructor — reordering
  two same-typed fields in the SDK would break it silently (see "Key design
  decisions"). An upstream `ApplicationConfig.toBuilder()`/`withSubscriberConfig`
  is the real fix.

## Packaging status

Not yet a fully published library: no CI pipeline, no `-sources`/`-javadoc`
jars, and the POM has no `<scm>`/`<licenses>`/`<url>`. It builds and installs
locally (`mvn install`) and is consumed that way by the sample and the
integration-tests project.

## Extension points

Every bean is `@ConditionalOnMissingBean` — declare your own of the same type
to replace it: `SenderClient`, `SubmissionSender`, `SubscriberClientFactory`,
`ReceivingDestinations`, `SubmissionProcessor`, `SubmissionPollingService`,
`ReceivePipelineMetrics`, `RetryCooldownStore`, `PollCycleGate`,
`FitConnectReceiverHealthIndicator`, `fitConnectCallbackObjectMapper`.
(A replacement `SubmissionPollingService` bean must accept `RetryCooldownStore`
and `PollCycleGate` in its constructor the same way the default one does, or
pass `RetryCooldownStore.NONE` / `PollCycleGate.DIRECT`.)

## Working on this repo

- Standalone Maven module, no `<parent>`: `cd fitko-spring && mvn package`
  builds and runs the full suite on its own. JDK 17+ to build; developed and
  tested on Temurin 25. No CI pipeline yet.
- Tests mock the SDK's `SenderClient`/`SubscriberClient` — no network, no
  real key material (`TestJwkKeys` mints throwaway JWKs via the SDK's own
  `TestKeyBuilder`).
- `SampleApplicationYamlTest` binds [`../docs/application.yaml`](application.yaml)
  (i.e. `java-samples/docs/application.yaml`) against the real
  `FitConnectProperties`, so a renamed/typo'd property in the sample fails
  the build instead of surfacing as a user's "unknown property" surprise.
  Run tests from the `fitko-spring` module root — it reads that path
  (`../docs/application.yaml`) relative to the working directory.
- Auto-config conditionals: `ApplicationContextRunner`
  (`FitConnectAutoConfigurationTest`). Full wiring: `@SpringBootTest` with
  mocked clients. Callback endpoint: real `MockMvc` HTTP dispatch.

## Notes for agents / new contributors

- `FitConnectProperties`' field javadoc is the single source of truth for
  config docs (`spring-boot-configuration-processor` turns it into IDE
  metadata). Change it first, then mirror into
  [`configuration.md`](configuration.md) and, if the sample
  is affected, [`application.yaml`](application.yaml).
- Respect the package boundaries above — `receive` and `send` don't import
  each other.
- Before touching `ApplicationConfigFactory`, read its class javadoc: it
  documents two SDK-source-verified assumptions (`Environment.merge`'s
  null-means-fall-through semantics; one key set per `SubscriberClient`) the
  rest of the receive side relies on.
