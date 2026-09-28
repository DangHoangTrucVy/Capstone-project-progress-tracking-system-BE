package com.capstone.tracking.notification.email;

import jakarta.persistence.LockModeType;
import jakarta.persistence.QueryHint;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.jpa.repository.QueryHints;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public interface EmailOutboxRepository extends JpaRepository<EmailOutbox, UUID> {

    boolean existsByEventId(UUID eventId);

    /**
     * Due pending emails, locked FOR UPDATE SKIP LOCKED (lock timeout -2): with several instances each dispatcher
     * claims different rows, so an email is never sent twice.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @QueryHints(@QueryHint(name = "jakarta.persistence.lock.timeout", value = "-2"))
    @Query("select e from EmailOutbox e where e.status = com.capstone.tracking.notification.email.EmailOutbox.Status.PENDING "
            + "and e.nextAttemptAt <= :now order by e.nextAttemptAt asc")
    List<EmailOutbox> lockDue(@Param("now") Instant now, Pageable pageable);
}
