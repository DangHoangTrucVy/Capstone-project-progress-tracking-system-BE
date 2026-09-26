package com.capstone.tracking.progress;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface WeeklyProgressReportRepository extends JpaRepository<WeeklyProgressReport, UUID> {

    List<WeeklyProgressReport> findByGroupIdOrderByWeekNumberAsc(UUID groupId);

    boolean existsByGroupIdAndWeekNumber(UUID groupId, int weekNumber);
}
