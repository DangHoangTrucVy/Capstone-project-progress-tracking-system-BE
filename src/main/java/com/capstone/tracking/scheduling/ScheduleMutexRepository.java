package com.capstone.tracking.scheduling;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;

public interface ScheduleMutexRepository extends JpaRepository<ScheduleMutex, Integer> {
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select m from ScheduleMutex m where m.id = 1")
    ScheduleMutex lockCalendar();
}
