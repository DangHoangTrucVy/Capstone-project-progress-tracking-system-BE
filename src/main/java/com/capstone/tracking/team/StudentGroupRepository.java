package com.capstone.tracking.team;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface StudentGroupRepository extends JpaRepository<StudentGroup, UUID> {

    @Query("select g from StudentGroup g where g.scope.id = :scopeId and g.dissolvedAt is null order by g.code")
    List<StudentGroup> findActiveByScope(@Param("scopeId") UUID scopeId);

    @Query("select g from StudentGroup g where g.scope.subject.id = :subjectId and g.dissolvedAt is null "
            + "order by g.scope.major.code, g.code")
    List<StudentGroup> findActiveBySubject(@Param("subjectId") UUID subjectId);

    @Query("select g from StudentGroup g where g.scope.subject.semesterCode = :semester and g.dissolvedAt is null")
    List<StudentGroup> findActiveBySemester(@Param("semester") String semester);

    @Query("select count(g) from StudentGroup g where g.scope.id = :scopeId")
    long countAllInScope(@Param("scopeId") UUID scopeId);

    @Query("select count(g) from StudentGroup g where g.scope.subject.semesterCode = :semester and g.dissolvedAt is null")
    long countActiveBySemester(@Param("semester") String semester);

    boolean existsByCode(String code);
}
