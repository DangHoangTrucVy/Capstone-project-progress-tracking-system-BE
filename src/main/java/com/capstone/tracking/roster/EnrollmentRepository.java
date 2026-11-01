package com.capstone.tracking.roster;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface EnrollmentRepository extends JpaRepository<Enrollment, UUID> {

    Optional<Enrollment> findBySubjectIdAndUserId(UUID subjectId, UUID userId);

    List<Enrollment> findByScopeId(UUID scopeId);

    List<Enrollment> findBySubjectId(UUID subjectId);

    List<Enrollment> findBySemesterStudentId(UUID semesterStudentId);

    List<Enrollment> findByUserId(UUID userId);

    long countByScopeId(UUID scopeId);

    /**
     * The student list screen (4.3): by subject, optionally major, group status, eligibility and a keyword on student
     * code, name or email.
     */
    @Query("""
            select e from Enrollment e
            where e.subjectId = :subjectId
              and (:majorId is null or e.scope.major.id = :majorId)
              and (:eligibility is null or e.eligibility = :eligibility)
              and (:hasGroup is null
                   or (:hasGroup = true and exists (select m from GroupMember m where m.enrollment = e and m.leftAt is null))
                   or (:hasGroup = false and not exists (select m from GroupMember m where m.enrollment = e and m.leftAt is null)))
              and (lower(e.user.fullName) like :q or lower(e.user.email) like :q
                   or lower(coalesce(e.user.studentCode, '')) like :q)
            order by e.scope.major.code, e.user.studentCode""")
    Page<Enrollment> search(@Param("subjectId") UUID subjectId, @Param("majorId") UUID majorId,
                            @Param("eligibility") Eligibility eligibility, @Param("hasGroup") Boolean hasGroup,
                            @Param("q") String q, Pageable pageable);

    /** Students of the scope without a group, for the Leader's search (8.2) and the Moderator's arranging (7.1). */
    @Query("""
            select e from Enrollment e
            where e.scope.id = :scopeId and e.eligibility <> com.capstone.tracking.roster.Eligibility.INELIGIBLE
              and not exists (select m from GroupMember m where m.enrollment = e and m.leftAt is null)
            order by e.user.studentCode""")
    List<Enrollment> findWithoutGroup(@Param("scopeId") UUID scopeId);


}
