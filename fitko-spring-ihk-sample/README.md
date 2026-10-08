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

Three packages, in the order an Antrag passes through them:

- **`com.gfi.ozg.ficon.receive`** - getting an Antrag off FIT-Connect:
  - `AntragReceiveListener` - on every `SubmissionReceivedEvent`, all
    synchronously: checks that some `ProcessStarter` is configured for it
    (otherwise it is left on the delivery service), records it in the inbox,
    starts its process, and only then accepts it - or rejects it, if the
    `ProcessStarter` says so. A re-delivered submission continues from its
    recorded status.
  - `TenantDirectory` - which tenant a destination (identified only by UUID
    on the receive event) belongs to.
- **`com.gfi.ozg.ficon.inbox`** - what happened to every Antrag, persisted
  (JPA, Liquibase changelog in `db/changelog`):
  - `InboxSubmission` - the stored submission (payload, metadata, reply
    key; no attachments), which process was started for it, and whether and
    why it was accepted or rejected.
  - `InboxStatus` - `RECEIVED` -> `PROCESS_STARTED` -> `ACCEPTED`, or
    `RECEIVED` -> `REJECTION_PENDING` -> `REJECTED`.
  - `SubmissionInbox` - the state transitions, each in its own transaction.
- **`com.gfi.ozg.ficon.processstarter`** - the extension point and how the
  right implementation is chosen for an Antrag:
  - `ProcessStarter` - one implementation per way of actually starting a
    process; several coexist as ordinary Spring beans (unlike a typical
    single-implementation `@ConditionalOnMissingBean` extension point).
    Returns a `StartedProcess` (stored on the submission) or throws
    `ProcessStartRejectedException`.
  - `ProcessStarterRoutingProperties` / `ProcessStarterResolver` - the
    routing *decision*, a pure config lookup:
    `antrag-routing.process-starter-by-tenant.<tenant>.<leikaSchluessel>` ->
    a `ProcessStarter` class name, with a global
    `default-process-starter-class` fallback. No DMN engine, no
    per-submission code branch to maintain per Leistung.
  - `ProcessStarterLookup` - resolves that fully-qualified class name to the
    matching Spring-managed bean (`ApplicationContext.getBean(Class)`, not
    raw reflection instantiation, so an implementation can still
    constructor-inject whatever it needs) and validates every class name
    referenced in config eagerly at startup, so a typo fails fast rather
    than on the first matching submission.
  - **`com.gfi.ozg.ficon.processstarter.impl`** - the implementations
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
   concerns). `com.gfi.ozg.ficon.processstarter` is this project's own
   extension point for that.

5. **Routing decision itself required zero fitko-spring changes** -
   `ProcessStarterResolver` is ~20 lines against `IncomingSubmission.getServiceType().getIdentifier()`
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
   though `AntragReceiveListener` still owns calling `accept()` (after
   `start()` returns without throwing) - see `ProcessStartRequest`'s javadoc
   for why implementations must not call `accept()`/`reject()` themselves.

9. **Every Antrag is recorded in an inbox table, and accepted only once its
   process has started.** `AntragReceiveListener` handles a submission
   synchronously: store it (`RECEIVED`), start its process
   (`PROCESS_STARTED`, with which process and when), `accept()`
   (`ACCEPTED`). Or, if the `ProcessStarter` throws
   `ProcessStartRejectedException`, record the reason and problems
   (`REJECTION_PENDING`) and `reject()` (`REJECTED`). Each step is committed
   before the next one runs. Why: any step can fail, most notably `accept()`
   on the network after the process already runs. The submission then stays
   on the delivery service, and its next delivery continues from the
   recorded status: a `PROCESS_STARTED` one is only accepted, its process is
   not started a second time. Consequences:
   - Attachments are not stored. The `ProcessStarter` gets them from the
     `IncomingSubmission` and hands them to the process itself.
   - Retries come from fitko-spring: a failed submission is offered again
     after `polling.retry-cooldown` (20m). There is no attempt limit; stuck
     submissions show up with `attempts`/`last_error` in the inbox.
   - `polling.submission-timeout` (60s here) now covers the process start
     too.
   - The start and `PROCESS_STARTED` are only atomic if the `ProcessStarter`
     joins the transaction (e.g. embedded Camunda 7 on the same DataSource).
     A remote engine still needs idempotency on `submissionId`.
   - Known gap: if `accept()`/`reject()` succeeds but recording
     `ACCEPTED`/`REJECTED` fails (database down at exactly that moment), the
     row stays `PROCESS_STARTED`/`REJECTION_PENDING` although FIT-Connect has
     resolved it. Nothing will deliver it again to fix that; it would need a
     reconciliation job against the FIT-Connect submission status.

## Also removed

`fitko-camunda7` (the old plain-CDI/WildFly Camunda 7 sample) was removed
from this repository - superseded, not used going forward.
