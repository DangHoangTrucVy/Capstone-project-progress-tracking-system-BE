package com.capstone.tracking.roster;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface SemesterStudentRepository extends JpaRepository<SemesterStudent, UUID> {

    Optional<SemesterStudent> findBySemesterCodeAndUserId(String semesterCode, UUID userId);

    List<SemesterStudent> findBySemesterCode(String semesterCode);

    List<SemesterStudent> findBySemesterCodeAndCurrentTerm(String semesterCode, int currentTerm);

    long countBySemesterCode(String semesterCode);

    boolean existsByUserId(UUID userId);

    /** The student's semesters, newest first: the first one is where they land after signing in (mục 9). */
    @Query("""
            select ss from SemesterStudent ss, com.capstone.tracking.semester.Semester s
            where s.code = ss.semesterCode and ss.user.id = :userId
            order by s.startDate desc""")
    List<SemesterStudent> findByUserNewestFirst(@Param("userId") UUID userId);
}
