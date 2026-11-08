package com.capstone.tracking.team;

import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/** Rooms; "active" = not closed. */
public interface StudentGroupRepository extends JpaRepository<StudentGroup, UUID> {

    @Query("select g from StudentGroup g where g.scope.id = :scopeId and g.dissolvedAt is null order by g.roomNo, g.name")
    List<StudentGroup> findActiveByScope(@Param("scopeId") UUID scopeId);

    @Query("select g from StudentGroup g where g.scope.semesterCode = :semester and g.dissolvedAt is null "
            + "order by g.scope.major.code, g.roomNo, g.name")
    List<StudentGroup> findActiveBySemester(@Param("semester") String semester);

    @Query("select g from StudentGroup g where g.scope.semesterCode = :semester and g.dissolvedAt is null "
            + "and g.finalizeStatus = com.capstone.tracking.team.StudentGroup.FinalizeStatus.REQUESTED "
            + "order by g.finalizeRequestedAt")
    List<StudentGroup> findFinalizeRequested(@Param("semester") String semester);

    @Query("select count(g) from StudentGroup g where g.scope.semesterCode = :semester and g.dissolvedAt is null")
    long countActiveBySemester(@Param("semester") String semester);

    boolean existsByCode(String code);
}
