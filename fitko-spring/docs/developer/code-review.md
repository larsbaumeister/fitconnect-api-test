# fitko-spring — Code Review

**Date:** 2026-08-28
**Reviewed version:** `com.gfi.ozg.fitko:fitko-spring:1.0.0`
**Scope:** whole module (`src/main`, `src/test`, `pom.xml`, `docs/`)
**Dependencies at review time:** Spring Boot 4.1.1, FIT-Connect Java SDK `dev.fitko.fitconnect.sdk:client:3.5.0`, Lombok 1.18.46

---

## 1. Executive summary

`fitko-spring` is a well-built Spring Boot 4 auto-configuration starter around the
FIT-Connect Java SDK. It does one job — turn `fitconnect.*` properties into an
injectable `AntragSender` plus an event-driven receiving pipeline — and does it
idiomatically: `@AutoConfiguration` classes with correct back-off conditions,
`@ConfigurationProperties` with javadoc-driven IDE metadata, a Spring
`ApplicationEvent` as the receive API, `SmartLifecycle` for the poller, and a
custom `EventListenerFactory` for per-service filtering. The test suite (45 tests,
all green on JDK 25) exercises the wiring through real `ApplicationContextRunner`
and `@SpringBootTest` contexts with the SDK mocked, and even binds the sample
`docs/application.yaml` against the real properties class to prevent doc drift.

**Overall assessment: solid, shippable, above-average for a sample.** The
architecture is sound and the code is clean and heavily commented. The findings
below are refinements, not blockers. The themes worth attention are:

- **Operational observability** — no metrics, no dead-letter/backoff strategy for
  a submission that fails on every poll (Medium).
- **At-least-once redelivery with no de-duplication** — the default `LEAVE`
  outcome re-downloads, re-decrypts and re-publishes the same submission every
  cycle; listener idempotency is required but only lightly documented (Medium).
- **A brittle coupling to SDK internals** — the positional 10-arg
  `ApplicationConfig` copy constructor (Low–Medium). (The `List<ReceivingDestination>`
  bean-type footgun this originally also named is fixed — see R3.)
- **Library-packaging gaps** — no CI, no `-sources`/`-javadoc` jars, no
  `<scm>`/`<licenses>` in the POM, stale Eclipse skeleton directories in the repo
  (Low).

---

## 2. Method

- Read every production and test class, the POM, and both docs files.
- Cross-checked the review against the SDK 3.5.0 sources (`client-3.5.0-sources.jar`):
  `ApplicationConfig`, `Environment.merge`, `SubscriberClient`,
  `SubmissionReceiver` (auto-reject path), exception hierarchy.
- Ran `mvn -o test` — **BUILD SUCCESS, 45/45 tests pass** on Temurin 25.

---

## 3. Architecture

### 3.1 What is done well

| Area | Notes |
|---|---|
| **Package layout** | `autoconfigure` (wiring) / `config` (SDK translation) / `send` / `receive` / `receive.callback` — clear seams, no cyclic dependencies, no app-specific logic leaking in. |
| **Auto-configuration chain** | `FitConnectAutoConfiguration` owns the single `ApplicationConfig` bean; sender/receiver/callback configs are `@ConditionalOnBean(ApplicationConfig.class)` (+`@AutoConfiguration(after=…)`), so disabling the core cleanly disables everything downstream. The rationale is spelled out in each class's javadoc. |
| **Receive API = Spring events** | `AntragReceivedEvent` + `@EventListener` is the right idiom: consumers get `@Async`, `@Order`, `@TransactionalEventListener`, narrower parameter types, return-value republishing for free. |
| **`@AntragEventListener`** | A meta-annotated `@EventListener` with a custom `EventListenerFactory` ordered ahead of `DefaultEventListenerFactory`. The comment at `AntragEventListenerFactory.java:59` about having to filter in `onApplicationEvent` (not the private `shouldHandle(event,args)` overload) documents a real Spring gotcha and shows the mechanism was understood, not cargo-culted. |
| **One `SubscriberClient` per destination** | Correct and verified against the SDK: `SubscriberConfig` bakes one key set into each client instance. `ApplicationConfigFactory` builds the shared config once and clones it per destination with its own `SubscriberConfig`. |
| **Testing seam** | `SubscriberClientFactory` (`@FunctionalInterface`, default bean `ClientFactory::createSubscriberClient`) lets tests hand out a distinct mock per destination — used well in `ReceivingIntegrationTest`. |
| **Poller lifecycle** | `AntragPollingService implements SmartLifecycle`, dedicated single daemon thread, not the app's `TaskScheduler`. `isAutoStartup()` honours `polling.enabled`. `@PreDestroy` + `stop()` are deliberately not double-wired (comment at `FitConnectReceiverAutoConfiguration.java:100`). |
| **Fail-fast config** | Missing/invalid properties throw `FitConnectConfigurationException` at context-refresh time with a property-path message (`fitconnect.receiver.destinations[id=…].signing-key`), never at request time. |
| **Callback security** | Delegated entirely to the SDK's `validateCallback` HMAC scheme; unknown/secret-less destinations return 404; a submission listed for a different destination than the path variable is ignored (`FitConnectCallbackController.java:97`). |

### 3.2 Architectural limitations (by design, but worth stating)

1. **Polling is fully sequential across destinations and submissions.**
   `AntragPollingService.poll()` (`:113`) loops destinations one by one on a
   single thread, and within a destination processes each submission (download +
   decrypt + publish + listener execution) inline before fetching the next. One
   slow destination or one slow listener stalls the whole cycle. Fine for a
   handful of destinations and cheap listeners; a scaling ceiling otherwise.
   There is no knob for parallelism. *Recommendation:* document the expectation
   explicitly, and consider an opt-in `polling.concurrency` later.

2. **At-least-once delivery, no de-duplication.** With the default
   `default-outcome: LEAVE` and a listener that doesn't call `accept()`/`reject()`,
   the same submission is returned by every poll, and each time it is fully
   re-downloaded, re-decrypted, re-validated, and re-published to all listeners.
   Consequences:
   - Listeners **must** be idempotent — this is a hard requirement, currently
     only implied by one sentence in the README ("leave both unset to reconsider
     it next poll").
   - Repeated decrypt/validate is not free (crypto + schema validation per
     cycle).
   There is no "seen submission" tracking or short-term suppression.
   *Recommendation:* call out idempotency prominently in `docs/configuration.md`,
   and consider an optional in-memory de-dupe window keyed by submission id.

3. **One page per poll cycle.** `poll()` fetches `offset 0, limit=polling.limit`
   (default 100) once per destination per cycle. If more than `limit` submissions
   are waiting *and* they are left on the server (LEAVE), the backlog only drains
   at `limit` per `interval`. Acceptable, but not obvious from the config docs.

---

## 4. Reusability across projects

**As a dependency, reusability is good.** The starter is domain-agnostic within
FIT-Connect: no hard-coded Leistungen, endpoints, or business rules; everything is
`fitconnect.*` config or builder input. Public API surface (`AntragSender`,
`AntragToSend`/`AttachmentToSend`/`DataSetToSend`, `AntragReceivedEvent`,
`ReceivedAntrag`, `DefaultOutcome`, `@AntragEventListener`) is small, immutable
where it matters, and documented. Drop it on the classpath, set properties, inject
a bean — the stated goal is met.

**Friction points for reuse:**

| # | Issue | Impact |
|---|---|---|
| R1 | **Not published as a proper library artifact.** No `-sources`/`-javadoc` jars (POM has no `maven-source-plugin`/`maven-javadoc-plugin`), no `<scm>`, `<licenses>`, `<developers>`, `<url>` — all required for Maven Central and expected by consumers doing "go to source". No CI pipeline in the module (`.gitlab-ci.yml` present only belongs to the unrelated Docusaurus checkout under the top-level `docs/`). | A consuming team can't step into sources in their IDE; can't verify the build reproducibly. |
| R2 | **Standalone POM, no `<parent>`, no BOM import for consumers.** Fine as a "clone and `mvn package`" sample, but a consuming multi-module build gets no dependency-version alignment from it (it also can't, since it pins Spring Boot 4.1.1 itself — a version skew between the starter's transitive Boot and the consumer's Boot is possible). | Version-skew risk if the consumer is on a different Boot 4.x patch. Consider documenting the supported Boot range. |
| R3 | ~~`List<ReceivingDestination>` as a bean type.~~ **Fixed (2026-08-31).** Verified experimentally (three minimal Spring contexts, not just read from source) before fixing: a raw `List<ReceivingDestination>` bean is dropped by Spring's collection-autowiring the moment *any* `ReceivingDestination`-typed bean exists anywhere in the context - even with the parameter/field name matched exactly to the list bean's name, i.e. **the original "name-based injection mitigates but does not eliminate this" claim in this row was itself wrong; name-based injection provides zero mitigation for this failure mode.** `fitConnectReceivingDestinations` now publishes `ReceivingDestinations` (`record ReceivingDestinations(List<ReceivingDestination> all)`, `com.gfi.ozg.fitko.spring.receive`), confirmed immune to the same reproduction; `AntragPollingService` and `FitConnectCallbackController` both take it instead of the raw list. Regression-tested: `ReceivingDestinationsAutowiringTest`. | Was: low probability, high confusion if hit - and silent (no error/warning at any point), so "confusion" undersold it. Now: closed. |
| R4 | **Company-scoped coordinates and package** (`com.gfi.ozg.fitko` / `com.gfi.ozg.fitko.spring`). Reasonable, but the README/H1 calls the project "fitko-spring" while the root `.project` says `fitconnect-samples` and the module lives under `java-samples/` — mixed identity. | Cosmetic; tidy up naming before any external release. |
| R5 | **Stale skeleton directories.** `java-samples/common`, `receiver`, `sender`, `spring-boot-starter` each contain only an Eclipse `.project` file (and `.project` is in `.gitignore`, yet these are on disk). They contradict the README's "single standalone project" claim and will confuse anyone browsing the repo. | Delete them. |

---

## 5. Findings

Severity: **High** = fix before release · **Medium** = should fix · **Low** = nice to fix · **Nit** = optional.

### Medium

**M1 — No observability on the receive pipeline.**
`AntragPollingService` and `SubmissionProcessor` log at debug/warn and swallow
`RuntimeException` (`AntragPollingService.java:102,122`, `SubmissionProcessor.java:50`).
There are no Micrometer metrics (submissions received, processed, failed; poll
duration; per-destination error count) and no health indicator. For a starter
meant to run unattended in several services, this is the biggest practical gap —
operators can't tell "polling is healthy but idle" from "polling has been failing
for an hour". *Recommendation:* add optional Micrometer counters/timers (guarded
by `@ConditionalOnClass(MeterRegistry.class)`) and a
`HealthIndicator` reporting last successful poll per destination.

**Resolved (2026-08-28).** Added `ReceivePipelineMetrics` (a
Micrometer-free callback surface the pipeline reports to, with a `NOOP`
default) plus `MicrometerReceivePipelineMetrics` recording
`fitconnect.receive.poll` (timer, `outcome=success|failure`),
`fitconnect.receive.submissions.{found,processed,failed}` (counters), all
tagged `destination`. `AntragPollingService` now also tracks the last
successful poll per destination, exposed via a new
`FitConnectReceiverHealthIndicator` (`fitConnectReceiver` health entry:
`UNKNOWN` when polling disabled, `DOWN` when a destination has had no
successful poll for `initial-delay + 3 x interval`). Wired by
`FitConnectReceiveMetricsAutoConfiguration`
(`@ConditionalOnClass(MeterRegistry.class)`) and
`FitConnectReceiveHealthAutoConfiguration`
(`@ConditionalOnClass(HealthIndicator.class)`); `micrometer-core` and
`spring-boot-health` added as `optional` deps. Documented in
`docs/configuration.md`. 13 new tests (58 total, all green).

**M2 — ~~A permanently failing submission fails forever, every cycle, with no
backoff or escape.~~ Addressed (2026-08-31).** `polling.submission-timeout`
(default 10s, always on) now bounds how long any one submission - including a
hung network call or a blocking listener bug - may stall the poller before
being abandoned for the cycle, and the opt-in `polling.retry-cooldown` (unset
by default, matching the old behaviour) skips re-fetching a submission that
failed until the configured time has passed instead of retrying it every
single cycle. See `AntragPollingService.processWithSafeguards`/
`processWithTimeout` and the two properties' javadoc on
`FitConnectProperties.Polling`.
Still true, not addressed: there is no exponential backoff (the cooldown is a
fixed delay), no true dead-letter path, and no way to auto-`reject()` a
chronically-failing submission - `retry-cooldown` only spaces out retries, it
never gives up on one. `default-outcome: REJECT` remains all-or-nothing and
applies to *unresolved* (not *failed*) submissions.

**M3 — Listener idempotency requirement is under-documented.** See §4 point 2.
This is a correctness trap for consumers, not just a doc nicety — promote it to a
prominent note in `docs/configuration.md` and the README receiving section.

**M4 — "it stays on the delivery service" log message can be wrong.**
`SubmissionProcessor.process` (`:50-51`) catches every `RuntimeException` from
`client.requestSubmission(...)` and logs *"Failed to process submission {}, it
stays on the delivery service"*. But with the SDK's **auto-reject enabled by
default** (`disable-auto-reject=false`), a validation/malware/decryption failure
inside `receiveSubmission` causes the SDK to **reject the submission server-side**
*before* throwing (verified in `SubmissionReceiver.evaluateValidationResult` →
`rejectSubmissionWithProblem`, gated by `config.isAutoRejectEnabled()`). In that
case the submission is *gone*, not "left". The log line will mislead anyone
debugging a delivery. *Recommendation:* soften the message ("processing failed;
if auto-reject is enabled the SDK may already have rejected it server-side") or
distinguish the two cases.

### Low

**L1 — Brittle positional copy of `ApplicationConfig`.**
`ApplicationConfigFactory.withSubscriberConfig` (`:89-101`) reconstructs the SDK's
`ApplicationConfig` via its 10-arg `@AllArgsConstructor` because the SDK's
`@Builder` has no `toBuilder=true` and there is no `withSubscriberConfig`. Adding
a field to `ApplicationConfig` breaks this at compile time (acceptable), but
reordering two same-typed fields would break it silently, and this couples to the
SDK's `@Builder.Default` internals. A builder-style reconstruction
(`ApplicationConfig.builder().senderConfig(base.getSenderConfig())...build()`) is
marginally safer because unknown new fields fall back to their defaults. Either
way, an SDK feature request for a proper `withSubscriberConfig`/`toBuilder` is the
real fix — worth filing upstream. The existing comment acknowledges the hack; add
a pinned SDK version to it so a future reader knows what it was verified against.

**L2 — `base-urls` / boolean-override asymmetry is a real limitation, not just a comment.**
`ApplicationConfigFactory.toEnvironmentOverride` (`:208-214`) can only ever force
`allowInsecurePublicKey` / `skipSubmissionDataValidation` / auto-reject to their
non-default value, never back to the default, because `Environment.merge` treats
`null` as "fall through". So against a **custom environment** whose defaults have
these set unexpectedly, the starter cannot correct them. Low impact (custom
environments are rare), but it should be stated in `docs/configuration.md`, not
only in a code comment.

**L3 — `MetadataVersions.resolve` throws the wrong exception type, at the wrong layer.**
`MetadataVersions.resolve` (`:18`) throws `IllegalArgumentException` for an
unknown version string. It is called from `DefaultAntragSender.buildSubmission`
per request, so a bad `AntragToSend.metadataVersion` surfaces as a raw
`IllegalArgumentException` out of `AntragSender.send` — which the `AntragSender`
javadoc doesn't list (it documents only `AntragSendException` and
`IllegalStateException`). Either validate `metadataVersion` in
`AntragToSend.Builder.build()` (fail before the send) or wrap it in
`AntragSendException`, and update the interface contract.

**L4 — `DefaultAntragSender` only wraps `FitConnectSenderException`.**
`DefaultAntragSender.send` (`:43`) catches `FitConnectSenderException` and wraps
it as `AntragSendException`. Any other unchecked SDK exception
(`FitConnectInitialisationException`, an NPE from a malformed builder step, etc.)
propagates raw. Probably fine, but the interface javadoc implies `AntragSendException`
is *the* failure mode. Consider catching `RuntimeException` at the send boundary,
or documenting that non-`FitConnectSenderException` errors pass through.

**L5 — Duplicated default for the callback path.**
The default `/fitconnect/callback` is written twice: as the
`@ConfigurationProperties` default (`FitConnectProperties.java:222`) and as the
`@PostMapping` placeholder default (`FitConnectCallbackController.java:63`,
`${fitconnect.receiver.callback.path:/fitconnect/callback}`). Change one and the
other silently disagrees. Drop the literal fallback in the mapping (the property
always has a value) or reference a shared constant.

**L6 — `fitConnectCallbackObjectMapper` bean is broadly typed.**
`FitConnectCallbackAutoConfiguration` (`:47`) publishes a bean of type
`com.fasterxml.jackson.databind.ObjectMapper` with `@ConditionalOnMissingBean`.
The intent (a private Jackson-2 mapper, since Boot 4 auto-config is Jackson 3) is
sound and well-commented, but `@ConditionalOnMissingBean` on the bare type means
a consumer that (unusually) already exposes a Jackson-2 `ObjectMapper` bean would
suppress this one and get theirs injected. Consider a `@Qualifier` /
dedicated wrapper type, or at least `@ConditionalOnMissingBean(name=...)`.

**L7 — A throwing (synchronous) listener blocks later listeners for that event.**
In `SubmissionProcessor.process`, `publishEvent` runs listeners in order on the
poll thread; the first one to throw aborts the rest for that `AntragReceivedEvent`
and the submission is treated as "leave & retry". This is defensible but
surprising — worth a line in the `AntragReceivedEvent` javadoc (which already
notes synchronous execution).

### Nits

- **N1** — `DataSetToSend.sha512Hex` and its test copy hand-roll hex with
  `Character.forDigit`. `java.util.HexFormat.of().formatHex(...)` (Java 17+) is
  one line. Keeping a copy in the test for independence is fine.
- **N2** — `MetadataVersions` is `public` but used only by `DefaultAntragSender`
  in the same-ish area; could be package-private (move to `send`) unless it's
  intentionally part of the public surface.
- **N3** — `AntragToSend.toString()` is hand-maintained; a `@ToString(of={...})`
  or a comment "keep in sync" would prevent it silently going stale.
- **N4** — `FitConnectProperties.Polling` is a top-level nested class while
  `Callback`/`Destination` are nested under `Receiver`. `Polling` is only used by
  `Receiver` — nesting it there too would be more consistent.
- **N5** — `pom.xml`: `maven-compiler-plugin` is pinned (good) but
  `maven-surefire-plugin` is not; pin it for reproducible test runs.
- **N6** — `AntragPollingService.destinationIds()` uses
  `Collectors.toList()`; the codebase elsewhere is on Java 17 — `.toList()` is
  the modern form (also `DefaultAntragSender:83`).
- **N7** — `ReceivedAntrag.reject(List)` and `reject(Problem...)` are both
  public; the varargs one delegating is nice, but a zero-arg `reject()` call
  compiles and rejects with an empty problem list. Consider
  `Assert.notEmpty(problems, ...)`.

---

## 6. Code smells (summary)

| Smell | Location | Severity |
|---|---|---|
| Positional 10-arg copy of an SDK value object | `ApplicationConfigFactory.java:89` | Low |
| `List<T>` published as a bean type (Spring autowiring ambiguity) | `FitConnectReceiverAutoConfiguration.java:63` | Low–Medium |
| ~~Broad `catch (RuntimeException)` + log-and-swallow with no metric/backoff~~ — metrics existed already; backoff addressed by M2 (2026-08-31) | `AntragPollingService.java`, `SubmissionProcessor.java` | ~~Medium~~ |
| Inconsistent exception typing (`IllegalArgumentException` vs `FitConnectConfigurationException` vs `AntragSendException`) | `MetadataVersions.java:18`, `DefaultAntragSender.java:43` | Low |
| Duplicated literal default (`/fitconnect/callback`) | properties vs `@PostMapping` | Low |
| Hand-rolled hex encoding | `DataSetToSend.java:40` | Nit |
| Stale Eclipse skeleton dirs tracked in repo | `java-samples/{common,receiver,sender,spring-boot-starter}` | Low (hygiene) |

No god classes, no deep inheritance, no primitive-obsession of note, no obvious
duplication beyond the two items above, no dead code in `src/main`. Method and
class sizes are reasonable; the longest method (`DefaultAntragSender.buildSubmission`)
is a linear builder pipeline and reads fine.

---

## 7. Tests

**Strengths**

- Auto-config conditionals tested in isolation with `ApplicationContextRunner`
  (`FitConnectAutoConfigurationTest`): disabled-entirely, sender-only,
  receiver-only, fail-fast on missing destination, fail-fast on missing property.
- Full-context `@SpringBootTest` for send, receive, and callback, with the SDK
  clients mocked — no network, no key material on disk beyond throwaway JWKs
  minted via the SDK's own `TestKeyBuilder` (`TestJwkKeys`).
- `ReceivingIntegrationTest` exercises the real "one client per destination"
  wiring with two destinations, different keys, and asserts cross-talk cannot
  happen and that a failure on one destination doesn't stop the other.
- `SampleApplicationYamlTest` binds `docs/application.yaml` against the real
  `FitConnectProperties` — excellent guard against documentation drift.
- `FitConnectCallbackControllerTest` uses real `MockMvc` HTTP dispatch and covers
  valid HMAC, invalid HMAC → 401, unknown destination → 404, and
  wrong-destination submission ignored.
- The static-mock-not-`@MockitoBean` pattern is correctly chosen and explained
  (the factory is called during context startup, before `@BeforeEach`).

**Gaps**

| # | Missing coverage |
|---|---|
| T1 | `default-outcome=ACCEPT`/`REJECT` driven **from properties** through `SubmissionProcessor` — `ReceivedAntragTest` unit-tests `applyIfUnresolved`, and `ReceivingIntegrationTest` covers listener-driven accept/reject, but no test wires `fitconnect.receiver.default-outcome=ACCEPT` and asserts an unresolved submission is accepted. |
| T2 | `AntragPollingService` `SmartLifecycle` — `start()`/`stop()`/`isRunning()`, and `isAutoStartup()==false` when `polling.enabled=false` (currently every integration test disables polling and calls `poll()` directly, so the scheduled path and lifecycle are never run). |
| T3 | `fitconnect.http.*` → `HttpConfig` mapping (seconds truncation, all-unset → `null`). |
| T4 | `Environment` overrides other than `base-urls.auth`: `base-urls.submission` (list), `allow-insecure-public-key`, `skip-submission-data-validation`, `disable-auto-reject` → `enableAutoReject=false`. |
| T5 | Callback: malformed JSON body → 400 (`parseBody` returning `null`); valid HMAC + empty/absent `submissions` → 200 with nothing processed. |
| T6 | `MetadataVersions.resolve` — happy path and unknown-version message. |
| T7 | `@ConditionalOnMissingBean` override points — a consumer-supplied `AntragSender` / `SubmissionProcessor` bean wins. |
| T8 | `pollSafely` swallow path — `poll()` throwing does not kill the scheduled task. |
| T9 | `AntragToSend.Builder` validation (`Assert.hasText` etc.) and `AttachmentToSend.of` `UncheckedIOException` on an unreadable resource. |

No test uses an assertion library inconsistently; AssertJ + Mockito throughout.
Consider adding `jacoco` to make these gaps visible as a number.

---

## 8. Security notes

- **Secrets in config.** `client-secret` / `callback-secret` are plain `String`
  properties. That's the Spring norm and hard to avoid, but the class javadoc
  could point users at `spring-boot-starter-config-server`/Vault/env indirection
  and note that `toString()` on the properties classes (Lombok `@Getter/@Setter`,
  no `@ToString`) does *not* dump them — good that it doesn't.
- **`AntragToSend.toString()`** deliberately prints only `destinationId`,
  `serviceId`, `caseId` — no payload. Good.
- **`allow-insecure-public-key`** is clearly marked "never in PROD" in three
  places. Good. Consider a startup `WARN` log when it is enabled.
- **Callback endpoint** relies entirely on the SDK's HMAC + 5-minute replay
  window; the controller correctly returns 401/404 without leaking which
  condition failed beyond a debug log. It also correctly refuses to process a
  submission whose `destinationId` doesn't match the path. No obvious SSRF/DoS
  beyond "an authenticated caller can make you fetch submissions", which is the
  point.
- **`readJwk`** reads the whole resource into a `String` — fine for JWK files;
  no size guard, but these are operator-supplied local resources, not
  attacker-controlled.
- No injection surface (no SQL, no templating, no shell-out, no reflection on
  user input).

---

## 9. Prioritised recommendations

**Before promoting this to a reusable internal library:**

1. **(M1)** Add optional Micrometer metrics + a `HealthIndicator` for the poller.
2. **(M3)** Document the listener-idempotency / at-least-once contract prominently.
3. **(R1)** Add `maven-source-plugin` + `maven-javadoc-plugin`, `<scm>`,
   `<licenses>`, `<url>`; add a CI job that runs `mvn verify`.
4. **(R5)** Delete the empty `common/`, `receiver/`, `sender/`,
   `spring-boot-starter/` skeleton directories; reconcile the project name.
5. **(M2)** Decide and document the "submission fails on every poll" behaviour;
   at minimum add a `WARN`-with-count so it's visible.

**Worth doing, not urgent:**

6. **(M4)** Fix the misleading "stays on the delivery service" log.
7. ~~**(R3)** Replace the `List<ReceivingDestination>` bean with a holder type.~~ Done.
8. **(L1)** File an upstream SDK request for `ApplicationConfig.toBuilder()` /
   `withSubscriberConfig`; pin the verified SDK version in the workaround comment.
9. **(T1–T3, T8)** Close the highest-value test gaps (default-outcome from
   properties, lifecycle, HTTP config, `pollSafely`).
10. **(L3–L5)** Exception-typing consistency and the duplicated callback-path
    default.

**Cosmetic:** N1–N7.

---

## 10. Verdict

The design is right, the Spring Boot integration is idiomatic and correct, and
the tests give real confidence in the wiring. It is already usable as a shared
dependency. The work remaining is mostly *operational hardening* (metrics,
failure semantics) and *library packaging* (CI, source jars, POM metadata,
repo cleanup) rather than anything structural.
