package com.gfi.ozg.ficon.inbox;

import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface InboxSubmissionRepository extends JpaRepository<InboxSubmission, UUID> {

    /** Ids of the submissions due for a (re)try, oldest due first. */
    @Query("select s.submissionId from InboxSubmission s"
            + " where s.status = com.gfi.ozg.ficon.inbox.InboxStatus.PENDING and s.nextAttemptAt <= :now"
            + " order by s.nextAttemptAt")
    List<UUID> findDueIds(Instant now, Pageable page);

    /**
     * Loads one submission with a row lock ({@code select ... for update}),
     * held until the surrounding transaction ends - so with several replicas
     * the second one to reach the same row waits, then sees it is no longer
     * {@code PENDING} and skips it.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select s from InboxSubmission s where s.submissionId = :submissionId")
    Optional<InboxSubmission> findByIdForUpdate(UUID submissionId);
}
