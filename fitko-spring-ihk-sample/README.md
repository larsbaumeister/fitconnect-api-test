# fitko-spring-ihk-sample

Not a general-purpose example (that's [`fitko-spring-sample/`](../fitko-spring-sample)).
This one is a **coverage test**: a consumer built against a concrete
multi-tenant requirement, to see how much of it `fitko-spring` already
covers and where a consumer still has to build something itself.

**The requirement it's built against:** several dozen regional tenants
(numbered - this sample configures two of the eventual set, `101-aachen` and
`133-hannover`) each receiving Antraege, where the same Leistung (identified
by its LeiKa-Schluessel URN, e.g.
`urn:de:fim:leika:leistung:99050035001000`) may need to be handled by a
**different `ProcessStarter` implementation** per tenant, because each
region can run its own Fachverfahren - possibly through entirely different
code, not just a different process definition of one shared engine - for a
nationally standardized Leistung. Actually starting a process is delegated
to this organization's own, already-existing library(ies); that's what the
`ProcessStarter` implementations in `processstarter/` stand in for here,
deliberately as logging-only stubs.

`fitko-spring` itself stays generic - nothing IHK-specific went into it.
Everything specific to this requirement lives in this project instead.

## What's here

Two packages, split by concern:

- **`com.example.ihk.routing`** - the routing *decision*: which tenant
  received an Antrag, and which `ProcessStarter` implementation (by
  fully-qualified class name) that tenant uses for its Leistung. No
  knowledge of how a process actually gets started.
  - `AntragRoutingProperties` / `AntragProcessResolver` - config lookup:
    `antrag-routing.process-starter-by-tenant.<tenant>.<leikaSchluessel>` ->
    a `ProcessStarter` class name, with a global
    `default-process-starter-class` fallback. A pure config lookup - no DMN
    engine, no per-submission code branch to maintain per Leistung.
  - `TenantDirectory` - which tenant a destination (identified only by UUID
    on the receive event) belongs to.
  - `AntragRoutingListener` - ties the above together on every
    `SubmissionReceivedEvent`, then hands off to `processstarter.ProcessStarterLookup`.
- **`com.example.ihk.processstarter`** - the extension point and its
  dispatch mechanism, entirely unaware of tenants/Leistungen:
  - `ProcessStarter` - one implementation per way of actually starting a
    process; several coexist as ordinary Spring beans (unlike a typical
    single-implementation `@ConditionalOnMissingBean` extension point).
  - `ProcessStarterLookup` - resolves a configured fully-qualified class
    name to the matching Spring-managed bean (`ApplicationContext.getBean(Class)`,
    not raw reflection instantiation, so an implementation can still
    constructor-inject whatever it needs) and validates every class name
    referenced in config eagerly at startup, so a typo fails fast rather
    than on the first matching submission.
  - **`com.example.ihk.processstarter.impl`** - the implementations
    themselves, kept separate from the interface/dispatch mechanism above:
    `NoopProcessStarter`, `LoggingProcessStarter`, two stubs this sample
    ships, wired to different (tenant, Leistung) pairs in `application.yaml`
    to prove the dispatch really picks a different class, not just a
    different value passed to one shared instance. Add your real
    implementations here (or any other package - `ProcessStarterLookup`
    doesn't care, it just needs a fully-qualified class name that resolves
    to a Spring bean), backed by your organization's own
    library/libraries.
- `src/main/resources/application.yaml` - the two demo tenants and their
  per-tenant `ProcessStarter` class mappings.

Run `mvn test` (after `cd ../fitko-spring && mvn install`, see the top-level
README). `IhkAntragRouterApplicationTests` boots the full context with both
tenants and throwaway JWKs, mocking only the SDK's network-facing
`SubscriberClient`.

## What this found

1. **Multi-tenant config: fully covered, no changes needed.** `fitconnect.receiver.tenants`
   is already a `Map<String, Tenant>` (see `fitko-spring`'s configuration.md,
   "Splitting configuration across files") - numeric-prefixed keys like
   `101-aachen` work fine, and the whole set can grow to all 79 tenants as
   one imported YAML file per region without becoming unmanageable or
   silently losing entries.

2. **Gap found and fixed generically:** the SDK's `ReceivedSubmission`
   already exposes `getDataSchemaUri()`, but `fitko-spring`'s
   `IncomingSubmission` wrapper never surfaced it - added as a plain
   delegating method (`IncomingSubmission.getDataSchemaUri()`), useful to
   any consumer that wants to route on the data schema rather than (or
   alongside) the LeiKa-Schluessel. Not used by this sample's own routing
   decision in the end (see below), but a real, generic gap independent of
   that choice.

3. **Gap found, not fixed - worked around here instead:** the receive event
   (`IncomingSubmission`) only ever carries the raw destination UUID, never
   the tenant/destination *names* chosen in `application.yaml` - those are
   configuration-time-only labels. A multi-tenant consumer that needs "which
   tenant did this come in on" has to rebuild that lookup itself.
   `TenantDirectory` does this **without any duplicate config**: `FitConnectProperties`
   is already a normal public Spring bean (`@EnableConfigurationProperties`
   on `FitConnectAutoConfiguration`), so it's just re-read directly - no
   fitko-spring change was actually necessary here, just discoverability
   (this wasn't obvious from the docs). Worth raising upstream as a nicer
   built-in convenience (e.g. exposing the destination's configured name/tenant
   on the event) if other consumers hit the same need.

4. **Process-starting is correctly out of scope for fitko-spring** (see its
   own architecture.md "Non-goals" - it already excludes routing/provisioning
   concerns). `com.example.ihk.processstarter` is this project's own
   extension point for that.

5. **Routing decision itself required zero fitko-spring changes** -
   `AntragProcessResolver` is ~20 lines against `IncomingSubmission.getServiceType().getIdentifier()`
   (already exposed) plus `TenantDirectory`. The per-tenant override (same
   Leistung, different `ProcessStarter` class for `101-aachen` vs.
   `133-hannover`) is just one more map level in this project's own
   `@ConfigurationProperties` class.

6. **Design turn: config names a `ProcessStarter` *class*, not an arbitrary
   process-key string.** The first version had one shared `ProcessStarter`
   bean (picked once via `@ConditionalOnMissingBean`, the same
   single-implementation-extension-point convention `fitko-spring` itself
   uses) receiving an arbitrary `processKey` string per Antrag. That doesn't
   fit "different tenants may run entirely different code, not just a
   different process definition of one engine" - so `antrag-routing.*` now
   maps straight to a fully-qualified `ProcessStarter` implementation class,
   and `ProcessStarterLookup` resolves it to the matching bean
   (`ApplicationContext.getBean(Class)`) per Antrag. Several `ProcessStarter`
   beans coexist on purpose now; `@ConditionalOnMissingBean` no longer
   applies (there's no single "the" default to fall back to - an unmapped
   Leistung is just left unresolved, same as before).

7. **Spring Boot gotcha hit while building the first version (not a
   fitko-spring issue, but worth remembering for anyone extending it the
   same way):** `@ConditionalOnMissingBean` directly on a `@Component`-scanned
   class is evaluated too early to reliably see other beans and silently
   produced zero beans of that type. The fix then (a `@Bean` method inside a
   `@Configuration` class, the same pattern `fitko-spring`'s own
   `FitConnect*AutoConfiguration` classes use everywhere) is moot now that
   there's no single default bean to pick anymore - noted here since the
   underlying Spring Boot gotcha is still worth knowing.

8. **`ProcessStartRequest` carries the real `IncomingSubmission`, not copied-out
   fields.** It started as `(submissionId, caseId, tenant, leikaSchluessel,
   Map<String,Object> variables)` - IDs and a grab-bag map, no actual Antrag
   content. A `ProcessStarter` will obviously need the real payload
   (`getDataAsString()`/`getDataAsBytes()`), attachments, metadata,
   applicationDate, ... - all already on `IncomingSubmission` - so rather than
   keep guessing which subset to copy into `ProcessStartRequest` field by
   field, it now just carries `(IncomingSubmission submission, String
   tenant)`. `tenant` stays a separate field because it genuinely isn't
   derivable from the submission (it comes from `TenantDirectory`); every
   other current field was. One consequence worth flagging: `IncomingSubmission.accept()`/
   `.reject()` are now reachable from inside a `ProcessStarter` too, even
   though `AntragRoutingListener` still owns calling `accept()` (after
   `start()` returns without throwing) - see `ProcessStartRequest`'s javadoc
   for why implementations must not call `accept()`/`reject()` themselves.

9. **A `ProcessStarter` can reject an Antrag by throwing
   `ProcessStartRejectedException`.** Before this, `start()` could only return
   (accepted) or throw (left on the delivery service and retried every poll
   cycle, with no end). A permanently unprocessable Antrag therefore looped
   forever, and `default-outcome` didn't help, because fitko-spring skips it
   when a listener throws. Now `AntragRoutingListener` catches
   `ProcessStartRejectedException` and calls `reject()` with its `Problem`s.
   Any other exception is still treated as transient and retried, now at most
   once per `polling.retry-cooldown` (20m) instead of every 30s.

## Also removed

`fitko-camunda7` (the old plain-CDI/WildFly Camunda 7 sample) was removed
from this repository - superseded, not used going forward.
