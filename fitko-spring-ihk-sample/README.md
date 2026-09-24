# fitko-spring-ihk-sample

Not a general-purpose example (that's [`fitko-spring-sample/`](../fitko-spring-sample)).
This one is a **coverage test**: a consumer built against a concrete
multi-tenant requirement, to see how much of it `fitko-spring` already
covers and where a consumer still has to build something itself.

**The requirement it's built against:** several dozen regional tenants
(numbered - this sample configures two of the eventual set, `101-aachen` and
`133-hannover`) each receiving Antraege, where the same Leistung (identified
by its LeiKa-Schluessel URN, e.g.
`urn:de:fim:leika:leistung:99050035001000`) may need to start a **different**
downstream Camunda process per tenant, because each region can run its own
Fachverfahren for a nationally standardized Leistung. Which library actually
starts that process is a separate, already-existing internal library -
that's the `ProcessStarter` extension point below, deliberately a no-op
here.

`fitko-spring` itself stays generic - nothing IHK-specific went into it.
Everything specific to this requirement lives in this project instead.

## What's here

- `com.example.ihk.routing.AntragRoutingProperties` / `AntragProcessResolver`
  - the routing decision: `antrag-routing.process-by-tenant.<tenant>.<leikaSchluessel>`
    config, with a global `default-process-key` fallback. A pure config
    lookup - no DMN engine, no per-submission code branch to maintain per
    Leistung.
- `com.example.ihk.routing.TenantDirectory` - which tenant a destination
  (identified only by UUID on the receive event) belongs to.
- `com.example.ihk.routing.ProcessStarter` / `NoopProcessStarter` - the
  extension point that would call this organization's own process-starting
  library. Ships as a no-op that only logs; see `ProcessStarterConfiguration`.
- `com.example.ihk.routing.AntragRoutingListener` - ties the three above
  together on every `SubmissionReceivedEvent`.
- `src/main/resources/application.yaml` - the two demo tenants and their
  per-tenant process mappings.

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
   concerns). `ProcessStarter` is this project's own extension point for
   that, following the exact same `@ConditionalOnMissingBean` convention
   `fitko-spring` uses throughout its own beans.

5. **Routing decision itself required zero fitko-spring changes** -
   `AntragProcessResolver` is ~20 lines against `IncomingSubmission.getServiceType().getIdentifier()`
   (already exposed) plus `TenantDirectory`. The per-tenant override (same
   Leistung, different process for `101-aachen` vs. `133-hannover`) is just
   one more map level in this project's own `@ConfigurationProperties`
   class.

6. **Spring Boot gotcha hit while building this (not a fitko-spring issue,
   but worth remembering for anyone extending it the same way):**
   `@ConditionalOnMissingBean` directly on a `@Component`-scanned class is
   evaluated too early to reliably see other beans and silently produced
   zero `ProcessStarter` beans. Moved to a `@Bean` method inside a
   `@Configuration` class (`ProcessStarterConfiguration`) - the same pattern
   `fitko-spring`'s own `FitConnect*AutoConfiguration` classes use
   everywhere.

## Also removed

`fitko-camunda7` (the old plain-CDI/WildFly Camunda 7 sample) was removed
from this repository - superseded, not used going forward.
