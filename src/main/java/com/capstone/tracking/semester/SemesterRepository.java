package com.capstone.tracking.semester;

import jakarta.persistence.LockModeType;
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
}
