package com.capstone.tracking.team;

import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ApplicationLikeRepository extends JpaRepository<ApplicationLike, UUID> {

    Optional<ApplicationLike> findByRequestIdAndUserId(UUID requestId, UUID userId);

    long countByRequestId(UUID requestId);
}
