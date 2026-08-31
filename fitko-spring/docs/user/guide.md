# fitko-spring — User Guide

Spring Boot 4 auto-configuration for the [FIT-Connect Java SDK](https://docs.fitko.de/fit-connect/docs/sdks/java-sdk/overview):
add the dependency, set `fitconnect.*` properties, get an injectable
`AntragSender` for sending and a Spring event for every submission a
background poller downloads. No manual `ClientFactory`/`ApplicationConfig`
wiring.

## Prerequisites

- JDK 17+
- A sender and/or subscriber client (`clientId`/`clientSecret`) from the
  FIT-Connect [Self-Service-Portal](https://docs.fitko.de/fit-connect/docs/getting-started/account)
- To receive: a signing key and at least one decryption key as JWKs (see
  [Zertifikate](https://docs.fitko.de/fit-connect/docs/receiving/certificate))

## Install

Not published to a repository yet — build and install it locally first:

```bash
git clone <this repo> && cd fitko-spring && mvn install
```

Then add it as a dependency:

```xml
<dependency>
  <groupId>com.gfi.ozg.fitko</groupId>
  <artifactId>fitko-spring</artifactId>
  <version>1.0.0</version>
</dependency>
```

## Configure

```yaml
fitconnect:
  environment: TEST
  sender:
    client-id: ${FITCONNECT_SENDER_CLIENT_ID}
    client-secret: ${FITCONNECT_SENDER_CLIENT_SECRET}
  receiver:
    client-id: ${FITCONNECT_RECEIVER_CLIENT_ID}
    client-secret: ${FITCONNECT_RECEIVER_CLIENT_SECRET}
    destinations: # every destination this app receives on; one poller handles them all
      - id: 9f6bb611-df46-494a-9a98-a253f1362dc7
        signing-key: file:/etc/fitconnect/ihk-a/signing_key.json
        decryption-keys:
          - file:/etc/fitconnect/ihk-a/decryption_key.json
```

Each destination is its own Zustellpunkt with its own key pair, so each
carries its own keys here too — `client-id`/`client-secret` can still be
shared (see [`configuration.md`](configuration.md)).

Only send, or only receive? Set `fitconnect.sender.enabled=false` /
`fitconnect.receiver.enabled=false`. Every property is documented on
`FitConnectProperties` and autocompletes in your IDE. Full reference:
[`configuration.md`](configuration.md); full annotated example:
[`application.yaml`](application.yaml).

## Sending

```java
@Service
class GewerbeanmeldungService {

    private final AntragSender antragSender;

    GewerbeanmeldungService(AntragSender antragSender) {
        this.antragSender = antragSender;
    }

    void submit(UUID destinationId, String xmlPayload) {
        AntragToSend antrag = AntragToSend.builder(
                        "urn:de:fim:leika:leistung:99050035001000", "Gewerbeanmeldung",
                        DataFormat.XML, xmlPayload, URI.create("https://fimportal.de/.../xzufi"))
                .destinationId(destinationId) // required - no configured fallback
                .replyChannelEmail("applicant@example.com")
                .build();
        SentSubmission sent = antragSender.send(antrag);
    }
}
```

`send()` throws `AntragSendException` if FIT-Connect rejected or couldn't
deliver it, `IllegalStateException` if `destinationId` is missing.

## Receiving

Handle it like any Spring event:

```java
@Component
class GewerbeanmeldungHandler {

    @EventListener
    void onAntrag(AntragReceivedEvent event) {
        ReceivedAntrag antrag = event.getAntrag();
        process(antrag.getDataAsString());
        antrag.accept(); // or antrag.reject(new DataSchemaViolation()); leave both unset to reconsider it next poll
    }
}
```

**Listeners must be idempotent.** Leaving a submission unresolved (the
default) re-delivers it on every poll — same submission, fully re-downloaded,
re-decrypted, re-published — until something calls `accept()`/`reject()`.
`fitconnect.receiver.default-outcome` decides what happens when nothing does
(default `LEAVE`, safest).

Receiving on several destinations? Use `@AntragEventListener` instead of
`@EventListener` for a handler per Leistung — other listeners still see every
submission, filtered or not:

```java
@Component
class LeistungHandlers {

    @AntragEventListener(serviceIds = "urn:de:fim:leika:leistung:99050035001000")
    void onGewerbeanmeldung(AntragReceivedEvent event) { ... }

    @AntragEventListener(serviceIds = "urn:de:fim:leika:leistung:99050035002000")
    void onBauantrag(AntragReceivedEvent event) { ... }

    @AntragEventListener // no serviceIds: every submission, e.g. logging/auditing
    void onAnyAntrag(AntragReceivedEvent event) { ... }
}
```

### Observability

With Micrometer on the classpath (e.g. via `spring-boot-starter-actuator`):
`fitconnect.receive.*` meters — poll count/duration, per-destination
submission counters. With Actuator's health API too: a `fitConnectReceiver`
health indicator, `DOWN` when a destination hasn't polled successfully for a
while. Both opt in by classpath only — see
[`configuration.md`](configuration.md#observability-of-the-receive-pipeline-optional).

### Receiving via callback (push)

Instead of waiting for the next poll, FIT-Connect can push a notification the
moment a submission is ready:

```yaml
fitconnect:
  receiver:
    callback:
      enabled: true
    destinations:
      - id: 9f6bb611-df46-494a-9a98-a253f1362dc7
        signing-key: file:/etc/fitconnect/signing_key.json
        decryption-keys:
          - file:/etc/fitconnect/decryption_key.json
        callback-secret: ${FITCONNECT_CALLBACK_SECRET} # required for this destination to accept callbacks
```

Then register `https://your-app-host/fitconnect/callback/<destinationId>` as
that destination's `Callback` with FIT-Connect (`callbackSecret` matching the
one above) — a one-time step via `DestinationClient`, outside this starter's
scope (see [Out of scope](#out-of-scope)). Same event/listener handling as
polling: a callback is a notification, not a delivery, so it's downloaded and
decrypted through the same pipeline. Needs `spring-boot-starter-web` on the
classpath; without it, `callback.enabled=true` is a no-op. Polling keeps
running regardless, so a missed callback is still picked up next poll cycle.

## Testing your integration

Mock `AntragSender` for send-side tests. For receive-side tests, construct an
`AntragReceivedEvent` and publish it, or call your `@EventListener` method
directly — see this project's own `src/test` for the pattern (mocked SDK
`SenderClient`/`SubscriberClient`, real `@SpringBootTest` contexts, no
network or key material).

## Out of scope

- **Sending replies** (`SubscriberClient.sendReply`) and the **FIT-Connect
  reply channel** (`ReplyChannel.ofFitConnect`) — see
  [Bidirektionale Kommunikation](https://docs.fitko.de/fit-connect/docs/sdks/java-sdk/receiver#bidirektionale-kommunikation).
- **Reply pickup via callback** — a `NewRepliesCallback`'s `replies` are
  ignored, consistent with replies being out of scope generally.
- **Large/chunked attachments** (`Attachment.fromLargeAttachment`) for files
  too big to hold in memory — see
  [Übertragung großer Attachments](https://docs.fitko.de/fit-connect/docs/sdks/java-sdk/sender#übertragung-großer-attachments).
- **Destination/routing lookup and provisioning** (`RouterClient`,
  `DestinationClient`) — you set `fitconnect.receiver.destinations` /
  `AntragToSend.destinationId` directly, and register a destination's
  `Callback` with FIT-Connect yourself; see
  [Routing-Informationen](https://docs.fitko.de/fit-connect/docs/sdks/java-sdk/sender#routing-informationen)
  to resolve a destination from a LeiKa key and region.
- **Virus scanning** — the SDK's no-op scanner by default; see the
  [SDK overview](https://docs.fitko.de/fit-connect/docs/sdks/java-sdk/overview#virenscanner)
  to wire up ClamAV or ICAP.

For how this starter is built, see [../developer/architecture.md](../developer/architecture.md).
