package com.capstone.tracking.warning;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface WarningFlagRepository extends JpaRepository<WarningFlag, UUID> {

    List<WarningFlag> findByGroupIdOrderByRaisedAtDesc(UUID groupId);

    List<WarningFlag> findByGroupIdAndResolvedAtIsNullOrderByRaisedAtDesc(UUID groupId);
}
