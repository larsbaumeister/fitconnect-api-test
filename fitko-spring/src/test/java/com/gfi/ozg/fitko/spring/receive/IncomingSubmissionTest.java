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

class IncomingSubmissionTest {

    private final ReceivedSubmission delegate = mock(ReceivedSubmission.class);
    private final IncomingSubmission submission = new IncomingSubmission(delegate);

    @Test
    void acceptDelegatesAndMarksResolved() {
        submission.accept();

        verify(delegate).acceptSubmission();
        assertThat(submission.isResolved()).isTrue();
    }

    @Test
    void rejectDelegatesAndMarksResolved() {
        submission.reject(new TechnicalError());

        verify(delegate).rejectSubmission(anyList());
        assertThat(submission.isResolved()).isTrue();
    }

    @Test
    void cannotBeResolvedTwice() {
        submission.accept();

        assertThatThrownBy(submission::accept).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> submission.reject(new TechnicalError())).isInstanceOf(IllegalStateException.class);
        // only the first, successful call reached the SDK
        verify(delegate).acceptSubmission();
        verify(delegate, never()).rejectSubmission(any());
    }

    @Test
    void defaultOutcomeLeaveDoesNothing() {
        submission.applyIfUnresolved(DefaultOutcome.LEAVE);

        verify(delegate, never()).acceptSubmission();
        verify(delegate, never()).rejectSubmission(any());
        assertThat(submission.isResolved()).isFalse();
    }

    @Test
    void defaultOutcomeAcceptsWhenUnresolved() {
        submission.applyIfUnresolved(DefaultOutcome.ACCEPT);

        verify(delegate).acceptSubmission();
        assertThat(submission.isResolved()).isTrue();
    }

    @Test
    void defaultOutcomeRejectsWhenUnresolved() {
        submission.applyIfUnresolved(DefaultOutcome.REJECT);

        verify(delegate).rejectSubmission(anyList());
        assertThat(submission.isResolved()).isTrue();
    }

    @Test
    void defaultOutcomeIsANoOpOnceAListenerAlreadyResolvedIt() {
        submission.accept();

        submission.applyIfUnresolved(DefaultOutcome.REJECT);

        verify(delegate, never()).rejectSubmission(any());
    }

    @Test
    void rejectAcceptsAVarargsListOfProblems() {
        List<Problem> captured = List.of(new TechnicalError());

        submission.reject(captured.toArray(new Problem[0]));

        verify(delegate).rejectSubmission(captured);
    }
}
