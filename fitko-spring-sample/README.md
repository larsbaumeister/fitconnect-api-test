# fitko-spring-sample

A small, complete Spring Boot application that integrates the
[`fitko-spring`](../fitko-spring) starter. Copy the pieces you need into your
own service.

It plays **both** FIT-Connect roles in one process so you can see both
integration points:

| Role | FIT-Connect term | Where | What it does |
|---|---|---|---|
| **Send** | Onlinedienst | [`send/`](src/main/java/com/example/gewerbeamt/send) | `POST /api/gewerbeanmeldungen` builds an `SubmissionToSend` and hands it to the injected `SubmissionSender`. |
| **Receive** | Verwaltungssystem | [`receive/`](src/main/java/com/example/gewerbeamt/receive) | A background poller downloads submissions; `@SubmissionEventListener` methods process and `accept()` / `reject()` them. |

A real application is normally only one of the two — set
`fitconnect.sender.enabled=false` or `fitconnect.receiver.enabled=false` for
the other.

---

## What you actually have to do to integrate

1. **One dependency** ([`pom.xml`](pom.xml)):

   ```xml
   <dependency>
     <groupId>com.gfi.ozg.fitko</groupId>
     <artifactId>fitko-spring</artifactId>
     <version>1.0.0</version>
   </dependency>
   ```

   No `@EnableFitConnect`, no `@Import` — Spring Boot auto-configuration
   activates it from the `fitconnect.*` properties.

2. **Set `fitconnect.*` properties** ([`application.yaml`](src/main/resources/application.yaml)).

3. **Send:** inject `SubmissionSender`, build an `SubmissionToSend`, call `send()` —
   [`GewerbeanmeldungService`](src/main/java/com/example/gewerbeamt/send/GewerbeanmeldungService.java).

4. **Receive:** annotate a method `@SubmissionEventListener`, read the
   `IncomingSubmission`, call `accept()` —
   [`GewerbeanmeldungHandler`](src/main/java/com/example/gewerbeamt/receive/GewerbeanmeldungHandler.java).

That is the whole surface. Everything else in this repo is ordinary Spring
(a controller, an in-memory store) or explanatory comments.

---

## Prerequisites

- **JDK 17+** and **Maven 3.6+**.
- **`fitko-spring` in your local Maven repo.** It is not on Maven Central
  yet, so build it once:

  ```bash
  cd ../fitko-spring && mvn install
  ```

- **A FIT-Connect TEST account** (Self-Service-Portal):
  <https://docs.fitko.de/fit-connect/docs/getting-started/account>
  - a **sender** client (`clientId` / `clientSecret`) — for sending
  - a **subscriber** client (`clientId` / `clientSecret`) — for receiving
  - a **destination** (Zustellpunkt) with a **signing key** and a
    **decryption key** as JWK files — for receiving
    (<https://docs.fitko.de/fit-connect/docs/receiving/certificate>)

You can explore the code and run the test suite (below) without any of the
FIT-Connect account material.

---

## Configure

The app reads everything from environment variables so nothing sensitive is
committed. Required (no default) — the app fails fast at startup naming any
that is missing:

```bash
export FITCONNECT_SENDER_CLIENT_ID=...
export FITCONNECT_SENDER_CLIENT_SECRET=...

export FITCONNECT_RECEIVER_CLIENT_ID=...
export FITCONNECT_RECEIVER_CLIENT_SECRET=...
export FITCONNECT_DESTINATION_ID=9f6bb611-df46-494a-9a98-a253f1362dc7
```

Optional (have defaults — see [`application.yaml`](src/main/resources/application.yaml)):

```bash
export FITCONNECT_SIGNING_KEY=file:/path/to/signing_key.json      # default: file:./keys/signing_key.json
export FITCONNECT_DECRYPTION_KEY=file:/path/to/decryption_key.json # default: file:./keys/decryption_key.json
export FITCONNECT_POLL_INTERVAL=15s
export FITCONNECT_ENVIRONMENT=TEST
```

The default key locations (`./keys/…`) mean you can just drop the two JWK
files into a `keys/` directory next to the `pom.xml` (it is git-ignored).

> This particular sample injects `SubmissionSender`, so it will not start with
> `fitconnect.enabled=false` (there would be no bean to inject). That switch
> is for apps that guard the injection with `ObjectProvider<SubmissionSender>`.

---

## Run

```bash
mvn spring-boot:run
```

### Send a Gewerbeanmeldung

```bash
curl -sS -X POST localhost:8080/api/gewerbeanmeldungen \
  -H 'Content-Type: application/json' \
  -d '{
        "destinationId": "9f6bb611-df46-494a-9a98-a253f1362dc7",
        "businessName":  "Baeckerei Mustermann",
        "ownerName":     "Erika Mustermann",
        "applicantEmail":"erika@example.com"
      }'
# 202 Accepted
# {"submissionId":"…","caseId":"…"}
```

### Watch it come back in

If the `destinationId` you sent to is one this app also polls, the poller
picks it up within `FITCONNECT_POLL_INTERVAL` and the listeners run
(watch the log for `AUDIT received …` then `Stored Gewerbeanmeldung …`):

```bash
curl -sS localhost:8080/api/empfangene-gewerbeanmeldungen | jq
```

### Observability

`spring-boot-starter-actuator` is on the classpath, so `fitko-spring`
contributes receive-pipeline meters and a health indicator automatically:

```bash
curl -sS localhost:8080/actuator/health | jq '.components.fitConnectReceiver'
curl -sS 'localhost:8080/actuator/metrics/fitconnect.receive.poll'
curl -sS 'localhost:8080/actuator/metrics/fitconnect.receive.submissions.processed'
```

---

## Sending — how it works

[`GewerbeanmeldungService`](src/main/java/com/example/gewerbeamt/send/GewerbeanmeldungService.java):

```java
SubmissionToSend submission = SubmissionToSend.builder(
        Leistung.GEWERBEANMELDUNG_LEIKA, "Gewerbeanmeldung",
        DataFormat.XML, xmlPayload, URI.create(schemaUri))
    .destinationId(request.destinationId())   // required — no configured fallback
    .replyChannelEmail(request.applicantEmail())
    .attachment(AttachmentToSend.ofBytes(bytes, "text/plain", "ausweis.txt"))
    .build();

SentSubmission sent = submissionSender.send(submission);   // throws SubmissionSendException on failure
```

- **`destinationId` is mandatory** on every call. Resolving it from a LeiKa
  key + region (the SDK's `RouterClient`) is out of scope for the starter —
  you pass it in.
- `send()` throws `SubmissionSendException` (unchecked) if FIT-Connect rejected
  or could not deliver the submission;
  [`GewerbeanmeldungController`](src/main/java/com/example/gewerbeamt/send/GewerbeanmeldungController.java)
  maps that to `502`.

---

## Receiving — how it works

[`GewerbeanmeldungHandler`](src/main/java/com/example/gewerbeamt/receive/GewerbeanmeldungHandler.java):

```java
@SubmissionEventListener(serviceIds = Leistung.GEWERBEANMELDUNG_LEIKA)
public void onGewerbeanmeldung(SubmissionReceivedEvent event) {
    IncomingSubmission submission = event.getSubmission();
    // ... map submission.getDataAsString() onto your domain model, persist ...
    submission.accept();   // FIT-Connect then deletes it from the delivery service
}
```

Key points the sample demonstrates:

- **`@SubmissionEventListener` vs `@EventListener`.** Plain `@EventListener` sees
  every submission. `@SubmissionEventListener(serviceIds = …)` filters by LeiKa
  key — one handler per Leistung. Both are just Spring events, so `@Async`,
  `@Order`, `@TransactionalEventListener` all work.
- **Multiple listeners coexist.**
  [`SubmissionAuditListener`](src/main/java/com/example/gewerbeamt/receive/SubmissionAuditListener.java)
  is a second, unfiltered listener that runs first (`@Order(0)`) and only
  logs; the domain handler (`@Order(10)`) is what resolves the submission.
- **Idempotency is mandatory.** Delivery is at-least-once; an unresolved
  submission is re-downloaded and re-published on every poll cycle.
  [`ReceivedSubmissionStore`](src/main/java/com/example/gewerbeamt/receive/ReceivedSubmissionStore.java)
  dedupes on `submissionId`.
- **`accept()` / `reject(...)` / neither.** `accept()` deletes it server-side.
  `reject(new DataSchemaViolation())` deletes it too, with a reason for the
  sender. Calling neither leaves it for the next poll —
  `fitconnect.receiver.default-outcome` (default `LEAVE`) decides the fate of
  a submission no listener resolved.
- Listener methods run on the single poller thread, one submission at a time
  — keep them fast or make them `@Async`.
  `fitconnect.receiver.polling.submission-timeout` (default 10s) abandons a
  submission whose listeners run too long.

---

## Testing your integration

`mvn test` — runs offline, no FIT-Connect account needed. Three patterns:

| Test | Pattern |
|---|---|
| [`GewerbeanmeldungServiceTest`](src/test/java/com/example/gewerbeamt/send/GewerbeanmeldungServiceTest.java) | Send side: mock `SubmissionSender`, capture the `SubmissionToSend`, assert on it. No Spring, no SDK. |
| [`GewerbeanmeldungHandlerTest`](src/test/java/com/example/gewerbeamt/receive/GewerbeanmeldungHandlerTest.java) | Receive side: mock `IncomingSubmission`, wrap in `SubmissionReceivedEvent`, call the listener directly. Covers the accept path, the reject path, and re-delivery idempotency. |
| [`GewerbeamtApplicationTests`](src/test/java/com/example/gewerbeamt/GewerbeamtApplicationTests.java) | Full `@SpringBootTest` context: proves the starter auto-configures and wires `SubmissionSender`. SDK `SenderClient` replaced with `@MockitoBean`; receiver switched off. |

`fitko-spring`'s own `src/test` additionally shows driving a *real* poll
cycle against a mocked SDK `SubscriberClient`.

---

## Out of scope (same as the starter)

Sending replies, the FIT-Connect reply channel, large/chunked attachments,
destination/routing lookup (`RouterClient` / `DestinationClient`), virus
scanning, registering a callback URL. See the starter's
[user guide § Out of scope](../docs/user-guide.md#out-of-scope)
and [configuration reference](../docs/configuration.md).
