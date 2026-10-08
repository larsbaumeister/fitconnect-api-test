package com.gfi.ozg.ficon.inbox;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;

import java.util.Optional;
import java.util.UUID;

public interface InboxSubmissionRepository extends JpaRepository<InboxSubmission, UUID> {

    /**
     * Loads one submission with a row lock ({@code select ... for update}),
     * held until the surrounding transaction ends - so when two workers or
     * replicas handle the same submission at once, the second one waits, then
     * sees the first one's status instead of starting the process again.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select s from InboxSubmission s where s.submissionId = :submissionId")
    Optional<InboxSubmission> findByIdForUpdate(UUID submissionId);
}
