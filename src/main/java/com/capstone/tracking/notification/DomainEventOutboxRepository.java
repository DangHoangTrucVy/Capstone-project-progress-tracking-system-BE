package com.capstone.tracking.notification;

import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.*;
import org.springframework.data.repository.query.Param;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface DomainEventOutboxRepository extends JpaRepository<DomainEventOutbox, UUID> {
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select e from DomainEventOutbox e where e.id = :id")
    Optional<DomainEventOutbox> lockById(@Param("id") UUID id);

    @Query("select e.id from DomainEventOutbox e where e.deliveredAt is null and e.nextAttemptAt <= :now order by e.nextAttemptAt")
    List<UUID> due(@Param("now") Instant now, Pageable page);
}
