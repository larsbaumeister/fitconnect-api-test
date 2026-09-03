# fitko-spring

Spring Boot 4 auto-configuration for the
[FIT-Connect Java SDK](https://docs.fitko.de/fit-connect/docs/sdks/java-sdk/overview):
add it as a dependency, set `fitconnect.*` properties, and get an injectable
`SubmissionSender` bean for sending plus a Spring application event for every
submission a background poller downloads — no manual
`ClientFactory`/`ApplicationConfig` wiring needed.

A single standalone Maven project under [`fitko-spring/`](fitko-spring)
(own `pom.xml`, no `<parent>`, no shared code with anything else) —
`cd fitko-spring && mvn package` builds and tests it on its own.

## Docs

- **[User guide](fitko-spring/docs/user/guide.md)** — install, configure,
  send, receive. Start here to use the library.
- **[Configuration reference](fitko-spring/docs/user/configuration.md)** —
  every `fitconnect.*` property, defaults, when it's required.
- **[Architecture](fitko-spring/docs/developer/architecture.md)** — goal,
  design decisions, known limitations. Start here to work on the library.

## Example application

- **[`fitko-spring-sample/`](fitko-spring-sample)** — a small, complete
  Spring Boot app that integrates the starter: a REST endpoint that sends a
  submission via an injected `SubmissionSender`, `@SubmissionEventListener` handlers for
  the receive side, an annotated `application.yaml`, and the three
  integration-test patterns. Copy from here to bootstrap your own service.
  `cd fitko-spring && mvn install` first, then `cd fitko-spring-sample && mvn test`.

## End-to-end tests

- **[`fitko-spring-integration-tests/`](fitko-spring-integration-tests)** — a
  separate project that runs `fitko-spring` against a **live FIT-Connect
  environment**: sends submissions and asserts they round-trip back through the
  poller. Kept out of the library build on purpose — it needs credentials and
  a destination not everyone has. `cd fitko-spring && mvn install` first, then
  `cd fitko-spring-integration-tests && mvn verify` (see its README for the
  environment variables; runs green and self-skips without them).

## Prerequisites

- JDK 17 or newer (Spring Boot 4's baseline)
- Maven 3.6+
- A sender and/or subscriber client (`clientId`/`clientSecret`) registered
  in the FIT-Connect [Self-Service-Portal](https://docs.fitko.de/fit-connect/docs/getting-started/account)
- For receiving: a signing key and at least one decryption key as JWKs
  (see [Zertifikate](https://docs.fitko.de/fit-connect/docs/receiving/certificate))

## Build

```bash
cd fitko-spring && mvn package    # builds and runs the full test suite
```
