package com.capstone.tracking.subject;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface FormationScopeRepository extends JpaRepository<FormationScope, UUID> {

    List<FormationScope> findBySubjectIdOrderByMajor_CodeAsc(UUID subjectId);

    Optional<FormationScope> findBySubjectIdAndMajorId(UUID subjectId, UUID majorId);

    List<FormationScope> findByPhaseIn(List<ScopePhase> phases);

    @Query("select s from FormationScope s where s.subject.semesterCode = :semester order by s.subject.code, s.major.code")
    List<FormationScope> findBySemester(@Param("semester") String semester);

    boolean existsBySubjectIdAndPhaseNot(UUID subjectId, ScopePhase phase);
}
