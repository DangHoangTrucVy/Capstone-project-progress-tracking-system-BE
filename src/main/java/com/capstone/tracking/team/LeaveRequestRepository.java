package com.capstone.tracking.team;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface LeaveRequestRepository extends JpaRepository<LeaveRequest, UUID> {

    List<LeaveRequest> findByGroupIdOrderByCreatedAtDesc(UUID groupId);

    List<LeaveRequest> findByUserIdOrderByCreatedAtDesc(UUID userId);

    @Query("select r from LeaveRequest r where r.enrollment.id = :enrollmentId and r.status in "
            + "(com.capstone.tracking.team.LeaveRequest.Status.PENDING_LEADER, "
            + "com.capstone.tracking.team.LeaveRequest.Status.PENDING_MODERATOR)")
    Optional<LeaveRequest> findOpenByEnrollment(@Param("enrollmentId") UUID enrollmentId);

    /** Complaints and requests waiting for the Moderator, by subject and optionally scope. */
    @Query("select r from LeaveRequest r where r.group.scope.subject.id = :subjectId "
            + "and (:scopeId is null or r.group.scope.id = :scopeId) "
            + "and r.status = com.capstone.tracking.team.LeaveRequest.Status.PENDING_MODERATOR order by r.createdAt")
    List<LeaveRequest> findWaitingForModerator(@Param("subjectId") UUID subjectId, @Param("scopeId") UUID scopeId);

    @Query("select count(r) from LeaveRequest r where r.group.id in (select g.id from StudentGroup g where g.scope.id = :scopeId) and r.status in "
            + "(com.capstone.tracking.team.LeaveRequest.Status.PENDING_LEADER, "
            + "com.capstone.tracking.team.LeaveRequest.Status.PENDING_MODERATOR)")
    long countOpenInScope(@Param("scopeId") UUID scopeId);

    /** At the deadline, requests the Leader has not answered become complaints to the Moderator (3.4). */
    @Modifying
    @Query("update LeaveRequest r set r.status = com.capstone.tracking.team.LeaveRequest.Status.PENDING_MODERATOR, "
            + "r.complaint = true where r.group.id in (select g.id from StudentGroup g where g.scope.id = :scopeId) "
            + "and r.status = com.capstone.tracking.team.LeaveRequest.Status.PENDING_LEADER")
    int escalatePendingOfScope(@Param("scopeId") UUID scopeId);

    /** A dissolved group's open requests and complaints are closed (7.1). */
    @Modifying
    @Query("update LeaveRequest r set r.status = com.capstone.tracking.team.LeaveRequest.Status.CLOSED, r.decidedAt = :now "
            + "where r.group.id = :groupId and r.status in "
            + "(com.capstone.tracking.team.LeaveRequest.Status.PENDING_LEADER, "
            + "com.capstone.tracking.team.LeaveRequest.Status.PENDING_MODERATOR)")
    int closeOpenOfGroup(@Param("groupId") UUID groupId, @Param("now") Instant now);

    /** A member who leaves the group in another way no longer needs their request. */
    @Modifying
    @Query("update LeaveRequest r set r.status = com.capstone.tracking.team.LeaveRequest.Status.CLOSED, r.decidedAt = :now "
            + "where r.enrollment.id = :enrollmentId and r.status in "
            + "(com.capstone.tracking.team.LeaveRequest.Status.PENDING_LEADER, "
            + "com.capstone.tracking.team.LeaveRequest.Status.PENDING_MODERATOR)")
    int closeOpenOfEnrollment(@Param("enrollmentId") UUID enrollmentId, @Param("now") Instant now);
}
