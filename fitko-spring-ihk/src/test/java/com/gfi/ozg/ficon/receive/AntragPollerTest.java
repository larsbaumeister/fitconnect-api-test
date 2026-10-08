package com.gfi.ozg.ficon.receive;

import com.gfi.ozg.ficon.receive.AntragPoller.ReceivingDestination;
import dev.fitko.fitconnect.api.domain.model.submission.SubmissionForPickup;
import dev.fitko.fitconnect.api.domain.subscriber.ReceivedSubmission;
import dev.fitko.fitconnect.client.SubscriberClient;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.UUID;

import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AntragPollerTest {

    private final AntragHandler handler = mock(AntragHandler.class);
    private final SubscriberClient aachenClient = mock(SubscriberClient.class);
    private final SubscriberClient hannoverClient = mock(SubscriberClient.class);
    private final UUID aachen = UUID.randomUUID();
    private final UUID hannover = UUID.randomUUID();

    @Test
    void handsEverySubmissionToTheHandlerWithItsDestinationsTenant() {
        ReceivedSubmission fromAachen = available(aachenClient, aachen);
        ReceivedSubmission fromHannover = available(hannoverClient, hannover);

        poller(Duration.ofMinutes(20)).poll();

        verify(handler).handle(fromAachen, "101-aachen");
        verify(handler).handle(fromHannover, "133-hannover");
    }

    @Test
    void skipsAFailedSubmissionUntilItsCooldownIsOver() {
        ReceivedSubmission submission = available(aachenClient, aachen);
        doThrow(new IllegalStateException("Camunda unreachable")).when(handler).handle(submission, "101-aachen");
        when(hannoverClient.getAvailableSubmissionsForDestination(hannover)).thenReturn(List.of());

        AntragPoller withCooldown = poller(Duration.ofMinutes(20));
        withCooldown.poll();
        withCooldown.poll();
        verify(handler, times(1)).handle(submission, "101-aachen");

        AntragPoller withoutCooldown = poller(Duration.ZERO);
        withoutCooldown.poll();
        withoutCooldown.poll();
        verify(handler, times(3)).handle(submission, "101-aachen");
    }

    @Test
    void aFailingDestinationDoesNotStopTheOthers() {
        when(aachenClient.getAvailableSubmissionsForDestination(aachen)).thenThrow(new IllegalStateException("503"));
        ReceivedSubmission fromHannover = available(hannoverClient, hannover);

        poller(Duration.ofMinutes(20)).poll();

        verify(handler).handle(fromHannover, "133-hannover");
    }

    private AntragPoller poller(Duration retryCooldown) {
        return new AntragPoller(List.of(
                new ReceivingDestination("101-aachen", aachen, aachenClient),
                new ReceivingDestination("133-hannover", hannover, hannoverClient)), handler, retryCooldown);
    }

    private static ReceivedSubmission available(SubscriberClient client, UUID destinationId) {
        SubmissionForPickup pickup = new SubmissionForPickup(destinationId, UUID.randomUUID(), UUID.randomUUID());
        ReceivedSubmission submission = mock(ReceivedSubmission.class);
        when(client.getAvailableSubmissionsForDestination(destinationId)).thenReturn(List.of(pickup));
        when(client.requestSubmission(pickup)).thenReturn(submission);
        return submission;
    }
}
