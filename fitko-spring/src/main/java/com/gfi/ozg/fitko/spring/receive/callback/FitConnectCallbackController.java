package com.gfi.ozg.fitko.spring.receive.callback;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.core.JsonProcessingException;
import dev.fitko.fitconnect.api.domain.model.callback.CallbackHeaderKeys;
import dev.fitko.fitconnect.api.domain.model.callback.NewEventsCallback;
import dev.fitko.fitconnect.api.domain.model.submission.SubmissionForPickup;
import dev.fitko.fitconnect.api.domain.validation.ValidationResult;
import com.gfi.ozg.fitko.spring.receive.ReceivingDestination;
import com.gfi.ozg.fitko.spring.receive.SubmissionProcessor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Receives FIT-Connect's push notification for new submissions - a
 * destination's registered {@code Callback} - as an alternative or
 * complement to {@link com.gfi.ozg.fitko.spring.receive.AntragPollingService}
 * polling. Registered only when {@code fitconnect.receiver.callback.enabled}
 * is {@code true} and {@code spring-boot-starter-web} is on the classpath -
 * see {@code FitConnectCallbackAutoConfiguration}.
 *
 * <p>FIT-Connect's callback is a notification, not a delivery: the body just
 * lists which submissions are waiting, exactly like a poll response - this
 * still downloads and decrypts each one through the matching {@code
 * SubscriberClient} via {@link SubmissionProcessor}, same as polling does.
 *
 * <p>Every request is authenticated per the SDK's own {@code
 * SubscriberClient#validateCallback} - an HMAC of {@code
 * "<timestamp>.<body>"} keyed by the destination's registered callback
 * secret, with a 5-minute replay window enforced by the SDK. A destination
 * only accepts callbacks once both {@code fitconnect.receiver.callback.enabled}
 * and that destination's own {@code callback-secret} are set; one without a
 * secret returns 404 here (it's still reachable via polling).
 */
@RestController
public class FitConnectCallbackController {

    private static final Logger LOGGER = LoggerFactory.getLogger(FitConnectCallbackController.class);

    private final Map<UUID, ReceivingDestination> destinationsById;
    private final SubmissionProcessor submissionProcessor;
    private final ObjectMapper objectMapper;

    public FitConnectCallbackController(List<ReceivingDestination> destinations, SubmissionProcessor submissionProcessor,
                                         ObjectMapper objectMapper) {
        this.destinationsById = destinations.stream()
                .collect(Collectors.toUnmodifiableMap(ReceivingDestination::destinationId, d -> d));
        this.submissionProcessor = Objects.requireNonNull(submissionProcessor, "submissionProcessor must not be null");
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper must not be null");
    }

    @PostMapping("${fitconnect.receiver.callback.path:/fitconnect/callback}/{destinationId}")
    public ResponseEntity<Void> receiveCallback(
            @PathVariable("destinationId") UUID destinationId,
            @RequestHeader(CallbackHeaderKeys.AUTHENTICATION) String hmac,
            @RequestHeader(CallbackHeaderKeys.TIMESTAMP) long timestampInSec,
            @RequestBody String rawBody) {

        ReceivingDestination destination = destinationsById.get(destinationId);
        if (destination == null || !destination.supportsCallback()) {
            LOGGER.warn("Rejected callback for unknown or non-callback destination {}", destinationId);
            return ResponseEntity.notFound().build();
        }

        ValidationResult validation = destination.client()
                .validateCallback(hmac, timestampInSec, rawBody, destination.callbackSecret());
        if (!validation.isValid()) {
            LOGGER.warn("Rejected callback for destination {}: failed validation", destinationId);
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }

        NewEventsCallback event = parseBody(destinationId, rawBody);
        if (event == null) {
            return ResponseEntity.badRequest().build();
        }

        List<SubmissionForPickup> submissions = event.getSubmissions();
        if (submissions != null) {
            for (SubmissionForPickup submission : submissions) {
                if (destinationId.equals(submission.getDestinationId())) {
                    submissionProcessor.process(destination.client(), submission.getSubmissionId());
                } else {
                    LOGGER.warn("Callback for destination {} listed a submission for destination {}, ignored",
                            destinationId, submission.getDestinationId());
                }
            }
        }
        // Reply pickup via callback is out of scope, same as it is for polling - see the README.

        return ResponseEntity.ok().build();
    }

    private NewEventsCallback parseBody(UUID destinationId, String rawBody) {
        try {
            return objectMapper.readValue(rawBody, NewEventsCallback.class);
        } catch (JsonProcessingException e) {
            LOGGER.warn("Rejected callback for destination {}: body is not a valid callback event", destinationId, e);
            return null;
        }
    }
}
