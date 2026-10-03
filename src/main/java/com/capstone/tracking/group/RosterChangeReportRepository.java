package com.capstone.tracking.group;

import java.util.List;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

public interface RosterChangeReportRepository extends JpaRepository<RosterChangeReport, UUID> {

    Page<RosterChangeReport> findByStatus(RosterChangeReport.Status status, Pageable pageable);

    List<RosterChangeReport> findByGroupIdOrderByCreatedAtDesc(UUID groupId);
}
