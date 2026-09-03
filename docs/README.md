# Documentation

All documentation for this repository lives in this directory.

| File | For | What's in it |
|---|---|---|
| [`user-guide.md`](user-guide.md) | Using `fitko-spring` | Install, configure, send, receive, callbacks, observability, testing, what's out of scope. |
| [`configuration.md`](configuration.md) | Using `fitko-spring` | Every `fitconnect.*` property: type, default, when it's required. |
| [`application.yaml`](application.yaml) | Using `fitko-spring` | A full, annotated example configuration. Also bound in a test (`SampleApplicationYamlTest`) so it can't drift from the code. |
| [`architecture.md`](architecture.md) | Working on `fitko-spring` | Goal, non-goals, package layout, auto-configuration chain, key design decisions, delivery semantics, known limitations, extension points. |
| [`identity-routing-trust.md`](identity-routing-trust.md) | Integrators | What a FIT-Connect submission does and doesn't tell you about the applicant's identity, the responsible Kammer, and the trust level — and what you have to build yourself. |

The two example projects document themselves in their own `README.md`:

- [`../fitko-spring-sample/`](../fitko-spring-sample) — a small Spring Boot app
  that integrates the starter (send + receive), meant to be copied from.
- [`../fitko-spring-integration-tests/`](../fitko-spring-integration-tests) —
  end-to-end round-trip tests against a live FIT-Connect environment.
