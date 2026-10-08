package com.gfi.ozg.ficon.inbox;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.Optional;
import java.util.UUID;

public interface InboxSubmissionRepository extends JpaRepository<InboxSubmission, UUID> {

    /**
     * Loads the row and locks it until the surrounding transaction ends - or
     * returns empty right away if another transaction (another replica)
     * already holds that lock, instead of waiting for it.
     */
    @Query(value = "select * from inbox_submission where submission_id = :submissionId for update skip locked",
            nativeQuery = true)
    Optional<InboxSubmission> lockUnlessLocked(UUID submissionId);
}
