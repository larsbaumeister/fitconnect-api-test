# TODO

- [done] remove the `ConcurrentMap` state from the components and use Spring
  Cache abstraction instead.
  → Only `lastFailureBySubmission` (retry-cooldown) qualified; it also had a
    slow leak. Moved onto the Spring `Cache` abstraction via
    `RetryCooldownStore` / `CacheRetryCooldownStore`: a consumer `CacheManager`
    (Redis, shared across replicas) when present, self-pruning in-process
    fallback otherwise. `lastSuccessfulPollByDestination` stays a plain map on
    purpose (bounded, per-instance health liveness).

- [docs only] evaluate if we can support both Spring Reactive Webflux and
  normal Spring Web if someone does use reactive spring, they should not be
  forced to have a tomcat running.
  → Already true: `spring-boot-starter-web` is optional and not propagated,
    the SDK is blocking OkHttp (no container). Only the callback webhook is
    servlet-only. Documented in guide.md / configuration.md / architecture.md.

- [done] when polling is enabled, we have distribued scheduling problems when
  the lib runs with multiple replicas. not really a hard problem, but we poll
  more often then we need to. evaluate the use of shedlock
  → Added opt-in ShedLock coordination: `PollCycleGate` (DIRECT by default) /
    `ShedLockPollCycleGate`, wired by `FitConnectPollLockAutoConfiguration`
    only when `shedlock-core` + a `LockProvider` bean are present. One lock
    per poll cycle, keyed by the destination-id set. New
    `fitconnect.receiver.polling.distributed-lock.*` properties.
