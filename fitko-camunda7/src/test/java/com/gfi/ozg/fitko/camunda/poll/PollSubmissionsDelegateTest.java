package com.gfi.ozg.fitko.camunda.poll;

import com.gfi.ozg.fitko.camunda.config.FitConnectCamundaConfig;
import com.gfi.ozg.fitko.camunda.config.SubscriberClientProvider;
import dev.fitko.fitconnect.api.domain.model.submission.SubmissionForPickup;
import dev.fitko.fitconnect.client.SubscriberClient;
import org.camunda.bpm.engine.delegate.DelegateExecution;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class PollSubmissionsDelegateTest {

    private static final UUID DESTINATION_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");

    private SubscriberClient client;
    private PollSubmissionsDelegate delegate;

    @BeforeEach
    void setUp() {
        client = mock(SubscriberClient.class);
        SubscriberClientProvider clientProvider = mock(SubscriberClientProvider.class);
        when(clientProvider.getClient()).thenReturn(client);

        FitConnectCamundaConfig config = new FitConnectCamundaConfig();
        config.set(FitConnectCamundaConfig.DESTINATION_ID, DESTINATION_ID.toString());
        config.set(FitConnectCamundaConfig.POLL_LIMIT, "50");

        delegate = new PollSubmissionsDelegate(clientProvider, config);
    }

    @Test
    void publishesAvailableSubmissionIdsAsAcollection() {
        UUID a = UUID.randomUUID();
        UUID b = UUID.randomUUID();
        when(client.getAvailableSubmissionsForDestination(eq(DESTINATION_ID), eq(0), eq(50)))
                .thenReturn(List.of(pickup(a), pickup(b)));

        Map<String, Object> vars = execute();

        assertThat(refs(vars)).containsExactlyInAnyOrder(a.toString(), b.toString());
        assertThat(vars).containsEntry("submissionCount", 2);
    }

    @Test
    void handlesAnEmptyDeliveryQueue() {
        when(client.getAvailableSubmissionsForDestination(any(), anyInt(), anyInt())).thenReturn(List.of());

        Map<String, Object> vars = execute();

        assertThat(refs(vars)).isEmpty();
        assertThat(vars).containsEntry("submissionCount", 0);
    }

    @SuppressWarnings("unchecked")
    private static List<String> refs(Map<String, Object> vars) {
        return (List<String>) vars.get("submissionRefs");
    }

    private static SubmissionForPickup pickup(UUID submissionId) {
        return new SubmissionForPickup(DESTINATION_ID, submissionId, UUID.randomUUID());
    }

    private Map<String, Object> execute() {
        Map<String, Object> vars = new HashMap<>();
        DelegateExecution execution = mock(DelegateExecution.class);
        doAnswer(inv -> {
            vars.put(inv.getArgument(0), inv.getArgument(1));
            return null;
        }).when(execution).setVariable(anyString(), any());
        when(execution.getVariable(anyString())).thenAnswer(inv -> vars.get(inv.getArgument(0)));

        delegate.execute(execution);
        return vars;
    }
}
