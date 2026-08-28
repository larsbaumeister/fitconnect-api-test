package com.gfi.ozg.fitko.spring.receive;

import dev.fitko.fitconnect.api.domain.model.event.problems.Problem;
import dev.fitko.fitconnect.api.domain.model.event.problems.other.TechnicalError;
import dev.fitko.fitconnect.api.domain.subscriber.ReceivedSubmission;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

class ReceivedAntragTest {

    private final ReceivedSubmission delegate = mock(ReceivedSubmission.class);
    private final ReceivedAntrag antrag = new ReceivedAntrag(delegate);

    @Test
    void acceptDelegatesAndMarksResolved() {
        antrag.accept();

        verify(delegate).acceptSubmission();
        assertThat(antrag.isResolved()).isTrue();
    }

    @Test
    void rejectDelegatesAndMarksResolved() {
        antrag.reject(new TechnicalError());

        verify(delegate).rejectSubmission(anyList());
        assertThat(antrag.isResolved()).isTrue();
    }

    @Test
    void cannotBeResolvedTwice() {
        antrag.accept();

        assertThatThrownBy(antrag::accept).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> antrag.reject(new TechnicalError())).isInstanceOf(IllegalStateException.class);
        // only the first, successful call reached the SDK
        verify(delegate).acceptSubmission();
        verify(delegate, never()).rejectSubmission(any());
    }

    @Test
    void defaultOutcomeLeaveDoesNothing() {
        antrag.applyIfUnresolved(DefaultOutcome.LEAVE);

        verify(delegate, never()).acceptSubmission();
        verify(delegate, never()).rejectSubmission(any());
        assertThat(antrag.isResolved()).isFalse();
    }

    @Test
    void defaultOutcomeAcceptsWhenUnresolved() {
        antrag.applyIfUnresolved(DefaultOutcome.ACCEPT);

        verify(delegate).acceptSubmission();
        assertThat(antrag.isResolved()).isTrue();
    }

    @Test
    void defaultOutcomeRejectsWhenUnresolved() {
        antrag.applyIfUnresolved(DefaultOutcome.REJECT);

        verify(delegate).rejectSubmission(anyList());
        assertThat(antrag.isResolved()).isTrue();
    }

    @Test
    void defaultOutcomeIsANoOpOnceAListenerAlreadyResolvedIt() {
        antrag.accept();

        antrag.applyIfUnresolved(DefaultOutcome.REJECT);

        verify(delegate, never()).rejectSubmission(any());
    }

    @Test
    void rejectAcceptsAVarargsListOfProblems() {
        List<Problem> captured = List.of(new TechnicalError());

        antrag.reject(captured.toArray(new Problem[0]));

        verify(delegate).rejectSubmission(captured);
    }
}
