# TODO

- remove the `ConcurrentMap` state from the components and use Spring Cache abstraction instead.
- evaluate if we can support both Spring Reactive Webflux and normal Spring Web if someone does use reactive spring, they should not be forced to have a tomcat running.
- when polling is enabled, we have distribued scheduling problems when the lib runs with multiple replicas. not really a hard problem, but we poll more often then we need to. evaluate the use of shedlock
- 
