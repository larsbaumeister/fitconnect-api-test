# fitko-spring-ihk

Receives Antraege for several IHK tenants from FIT-Connect and hands each one
to a process. It is the production version of
[`fitko-spring-ihk-sample`](../fitko-spring-ihk-sample), built on the
FIT-Connect SDK directly: no `fitko-spring` dependency, no wrappers around
SDK classes, no metrics and no health checks.

## What it does

For every submission on a configured destination, one at a time:

1. `ProcessStarterRouter` picks the `ProcessStarter` implementation for its
   (tenant, Leistung) from `antrag-routing` in `application.yaml`. If nothing
   is configured, the submission is left on the delivery service.
2. `SubmissionInbox` records it in `inbox_submission` (`RECEIVED`).
3. The `ProcessStarter` gets the SDK's `ReceivedSubmission` and starts the
   process (`PROCESS_STARTED`, with process definition and instance id).
4. The submission is accepted (`ACCEPTED`). If the `ProcessStarter` threw
   `ProcessStartRejectedException`, it is rejected instead
   (`REJECTION_PENDING`, then `REJECTED`).

Every step is committed before the next one runs. If a step fails, the
submission stays on FIT-Connect and is tried again after
`polling.retry-cooldown`, continuing from the recorded status. In particular,
a submission whose process already started is only accepted, never started
again.

**No submission content is stored.** The table holds the ids, tenant,
Leistung, status, where the submission went and why it was rejected. The
`ProcessStarter` has to hand data, metadata and attachments over to the
process.

**Several replicas** can run at once, sharing one PostgreSQL database. A
submission's process is started under a `select ... for update skip locked`
row lock, so other replicas skip that submission instead of starting it a
second time. Details are in `SubmissionInbox`'s javadoc.

**Sending:** set `fitconnect.sender.client-id`/`client-secret` to get the
SDK's `SenderClient` as a bean.

## Layout

- `receive/AntragPoller` - polls the destinations (one SDK `SubscriberClient`
  per destination), one submission after another
- `receive/AntragHandler` - the steps above for one submission
- `inbox/` - the `inbox_submission` table (Liquibase changelog in
  `db/changelog`) and its state transitions
- `processstarter/` - the `ProcessStarter` extension point and the routing;
  `impl/LoggingProcessStarter` is a placeholder for real implementations

## Run

Configuration is in `src/main/resources/application.yaml`. It needs a
PostgreSQL database (`IHK_INBOX_DB_URL`, `_USERNAME`, `_PASSWORD`), the
receiver's client id and secret, and per tenant a destination id with its
signing and decryption keys.

`mvn test` needs Docker: the tests run against PostgreSQL in Testcontainers.
