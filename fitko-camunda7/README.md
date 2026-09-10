# fitko-camunda7

A **Camunda Platform 7 process application** (WAR, shared engine on **WildFly**)
that polls the FIT-Connect delivery service for new submissions on a
self-triggering timer and handles each submission in its own process instance,
routed by a **DMN decision table**.

Plain Jakarta EE / CDI — **no Spring**. Sibling to
[`fitko-spring/`](../fitko-spring) (Spring Boot auto-configuration for the same
SDK). Standalone Maven build, no parent pom:

```bash
cd fitko-camunda7 && mvn package      # -> target/fitko-fitconnect-camunda7.war
```

## What it does

Two process definitions:

### `fitconnect-poll` — the dispatcher (one instance per interval)

```
 (timer start, cycle = ${pollScheduleConfig.pollCycle})
        │
        ▼
 ┌──────────────────────┐  PollSubmissionsDelegate
 │ Poll new submissions │  getAvailableSubmissionsForDestination(...)
 └──────────────────────┘  → submissionRefs (List<String>), submissionCount
        │
        ▼  new submissions?  ──none──▶ (end)
        │ yes
        ▼
 ┌───────────────────────────┐  multi-instance call activity, one child
 │  Handle submission        │  `fitconnect-submission` instance per id
 │  (call activity)          │  (business key = submission id)
 └───────────────────────────┘
        │
        ▼
      (end)
```

The dispatcher "triggers itself": the timer **start event** has a
`<timeCycle>` so the engine schedules a recurring job and starts a fresh
instance every `fitconnect.poll.cycle`. `PollSubmissions` only **lists** ids;
nothing is downloaded here.

### `fitconnect-submission` — one instance per submission

Fully automated, **no user tasks**. A fixed order:

```
 start
   │
   ▼ LoadSubmission (asyncBefore)   LoadSubmissionDelegate
   │   requestSubmission(id) → decrypt ALL data; publish PROCESS INSTANCE
   │   variables submissionId, caseId, serviceIdentifier, serviceName, region,
   │   dataMimeType, attachmentCount  (+ transient receivedSubmission)
   ▼ PersistSubmission              PersistSubmissionDelegate
   │   hand data + attachments to the Fachverfahren, durably.
   │   ── if this THROWS, the flow stops here: the submission is NOT accepted,
   │      stays on the delivery service, and a later poll cycle retries it.
   ▼ AcceptSubmission               AcceptSubmissionDelegate
   │   accept-submission event → the delivery service deletes the submission
   ▼ LookupCase                     CaseLookupDelegate
   │   history query for other fitconnect-submission instances with the same
   │   caseId → caseKnown (boolean), priorCaseInstanceCount
   ▼ DecideNextStep                 DMN `next-step-routing`
   │   inputs: serviceIdentifier (Leika key), caseKnown
   │   outputs: nextStep, nextStepTarget
   ▼ ⟨nextStep⟩
     ├─ FORWARD ─▶ Forward to Fachverfahren ─▶ end   (ForwardSubmissionDelegate:
     │             hand to the internal system / queue named by nextStepTarget)
     └─ else    ─▶ Archive submission ─▶ end          (ArchiveSubmissionDelegate:
                   record only, nothing further)
```

So: **fetch all data → persist → (only if that worked) accept → then decide
what to do next**, driven by the service. Nothing waits for a human.

Because each submission is its own **process instance** carrying `caseId` as a
process variable, you can find prior work for a case with
`runtimeService`/`historyService`
`.create...Query().processDefinitionKey("fitconnect-submission").variableValueEquals("caseId", id)`
— which is exactly what `CaseLookupDelegate` does.

**No de-duplication guard.** It's a scheduled process, poll cycles don't
overlap, `Load → Persist → Accept` is quick, and an accepted submission is
deleted on the delivery service. A submission whose persist keeps failing is
simply re-listed and retried next cycle (and shows up as a Camunda incident).

### `next-step-routing.dmn`

`FIRST`-hit table, keyed on the service. Starter rules (edit to taste):

| # | serviceIdentifier | caseKnown | → nextStep / nextStepTarget |
| - | --- | --- | --- |
| 1 | `…leistung:99000000000001` | – | FORWARD / `bauantrag-fachverfahren` |
| 2 | `…leistung:99000000000002` | – | FORWARD / `gewerbe-fachverfahren` |
| 3 | – | `true` | FORWARD / `sachbearbeitung-followup` |
| 4 | otherwise | – | ARCHIVE / *(none)* |

## Build & deploy

Needs JDK 17+ and Maven 3.6+.

```bash
mvn package
```

Deploy `target/fitko-fitconnect-camunda7.war` to your **Camunda Platform 7
WildFly distribution** (shared engine), 7.20 or newer — 7.20+ is Jakarta EE 10
(`jakarta.*`, WildFly 27+), which is what this targets. Set `<camunda.version>`
in the POM to your server's engine version.

* `standalone/deployments/` drop-in, or `bin/jboss-cli.sh --command="deploy .../fitko-fitconnect-camunda7.war"`.
* Shared engine must be named `default` (see `META-INF/processes.xml`).
* Pure CDI process application: `WEB-INF/beans.xml` + `@ProcessApplication`
  `JakartaServletProcessApplication`, **no `web.xml`**. On startup the subsystem
  deploys both BPMN and the DMN to the shared engine; on undeploy the
  definitions and their data are kept (`isDeleteUponUndeploy=false`), a redeploy
  adds a new version.
* Needs history level `full` (the shared-engine default) for the `caseId`
  variable query in `CaseLookupDelegate`.

## Configuration

Each value is resolved in this order (first hit wins):

1. JVM system property with the key verbatim, e.g. `-Dfitconnect.destination-id=...`;
2. environment variable — key upper-cased, `.`/`-` → `_`, e.g. `FITCONNECT_DESTINATION_ID`;
3. external properties file named by `-Dfitconnect.camunda.config=/path/app.properties`
   (or `FITCONNECT_CAMUNDA_CONFIG`);
4. `application.properties` bundled in the WAR (template / defaults).

| Key | Meaning |
| --- | --- |
| `fitconnect.environment` | `TEST` \| `STAGE` \| `PROD` |
| `fitconnect.destination-id` | UUID of the destination (Zustellpunkt) to poll |
| `fitconnect.subscriber.client-id` / `.client-secret` | subscriber credentials |
| `fitconnect.subscriber.signing-key-path` | private signing key, a JWK JSON doc (`classpath:` / `file:` / path) |
| `fitconnect.subscriber.decryption-key-paths` | comma-separated private decryption keys (JWK JSON docs) |
| `fitconnect.poll.limit` | page size for one poll (default 50) |
| `fitconnect.poll.cycle` | timer interval: ISO-8601 repeating interval (`R/PT5M`) or cron; default `R/PT5M` |

On WildFly set the system properties in `standalone.xml`
(`<system-properties>`), or point `fitconnect.camunda.config` at a file.

## Tests

`mvn test` — no external services, no CDI container:

* `NextStepRoutingDmnTest` — the DMN table on a standalone DMN engine.
* `PollSubmissionsDelegateTest` — the poll delegate against a mocked `SubscriberClient`.
* `FitConnectPollProcessTest` — dispatcher + child process on an in-memory
  engine with the FIT-Connect delegates mocked: one child instance per
  submission, persist → accept order, every submission accepted, then
  FORWARD vs. ARCHIVE routing, and the real `CaseLookupDelegate` sending a
  follow-up for the same `caseId` to the clerk queue.

## Open points

* **Persist / forward / archive steps are stubs.** `PersistSubmissionDelegate`
  only reads the payload and logs its size; `ForwardSubmissionDelegate` and
  `ArchiveSubmissionDelegate` just log. Implement the real Fachverfahren
  handover in `PersistSubmissionDelegate` and make it idempotent (with
  `asyncBefore` on `LoadSubmission`, a failure retries the child from the start
  and re-runs persist).
* **Persist failure = incident.** If persist keeps throwing, the child job
  exhausts its retries and leaves an incident; the submission is re-listed and
  a fresh child retries it every poll cycle. Add a boundary timer / dead-letter
  if you want to stop after N attempts.
* **Case lookup source.** `CaseLookupDelegate` treats Camunda history as the
  "seen this case before" record. If the Fachverfahren is the source of truth,
  query it instead.
* **Clustered engine.** With several nodes on one database the design still
  holds (poll cycles are jobs, they don't run twice), but a poll cycle that
  overlaps a still-running one on another node could re-list the same
  submission; add a lock on the poll job if that matters.
* **Classpath.** The WAR bundles the FIT-Connect SDK and its transitive
  jackson/okhttp/bouncycastle. WildFly isolates deployment modules; if you see
  odd serialization behaviour, pin the engine's jackson with a
  `jboss-deployment-structure.xml`.
