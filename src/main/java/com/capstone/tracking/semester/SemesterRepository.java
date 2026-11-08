package com.capstone.tracking.semester;

import jakarta.persistence.LockModeType;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface SemesterRepository extends JpaRepository<Semester, String> {

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select s from Semester s where s.code = :code")
    Optional<Semester> lockByCode(@Param("code") String code);

    List<Semester> findAllByOrderByStartDateDesc();

    List<Semester> findByModeratorId(UUID moderatorId);

    /** Semesters in formation whose deadline was reached and whose rooms are not locked yet (7.1). */
    @Query("select s from Semester s where s.phase = :phase and s.roomsLockedAt is null and s.deadline is not null "
            + "and s.deadline <= :now")
    List<Semester> findDueForRoomLock(@Param("phase") SemesterPhase phase, @Param("now") Instant now);
}
