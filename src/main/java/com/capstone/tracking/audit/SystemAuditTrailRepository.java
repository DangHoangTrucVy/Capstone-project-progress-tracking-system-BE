package com.capstone.tracking.audit;

import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

public interface SystemAuditTrailRepository extends JpaRepository<SystemAuditTrail, UUID> {

    Page<SystemAuditTrail> findBySemesterCodeOrderByPerformedAtDesc(String semesterCode, Pageable pageable);
}
