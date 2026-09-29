package com.capstone.tracking.evaluation;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

public interface EvaluationRecordRepository extends JpaRepository<EvaluationRecord, UUID> {

    Page<EvaluationRecord> findByGroupId(UUID groupId, Pageable pageable);

    Page<EvaluationRecord> findByGroupIdAndStatus(UUID groupId, EvaluationStatus status, Pageable pageable);
}
