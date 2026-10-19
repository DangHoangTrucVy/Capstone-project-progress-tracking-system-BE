package com.capstone.tracking.team;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface JoinRequestRepository extends JpaRepository<JoinRequest, UUID> {

    List<JoinRequest> findByGroupIdOrderByCreatedAtDesc(UUID groupId);

    @Query("select r from JoinRequest r where r.enrollment.id = :enrollmentId order by r.createdAt desc")
    List<JoinRequest> findByEnrollment(@Param("enrollmentId") UUID enrollmentId);

    /** Open Applies of a student (6.2: at most 3). */
    @Query("select count(r) from JoinRequest r where r.enrollment.id = :enrollmentId "
            + "and r.type = com.capstone.tracking.team.JoinRequest.Type.APPLY "
            + "and r.status = com.capstone.tracking.team.JoinRequest.Status.PENDING and r.expiresAt > :now")
    long countOpenApplies(@Param("enrollmentId") UUID enrollmentId, @Param("now") Instant now);

    @Query("select r from JoinRequest r where r.group.id = :groupId and r.enrollment.id = :enrollmentId "
            + "and r.type = :type and r.status = com.capstone.tracking.team.JoinRequest.Status.PENDING")
    Optional<JoinRequest> findPending(@Param("groupId") UUID groupId, @Param("enrollmentId") UUID enrollmentId,
                                      @Param("type") JoinRequest.Type type);

    /** The student's other open requests in the subject, closed when they join a group (6.2). */
    @Modifying
    @Query("update JoinRequest r set r.status = com.capstone.tracking.team.JoinRequest.Status.CANCELLED, "
            + "r.cancelReason = :reason, r.respondedAt = :now "
            + "where r.enrollment.id = :enrollmentId and r.status = com.capstone.tracking.team.JoinRequest.Status.PENDING")
    int cancelPendingOfEnrollment(@Param("enrollmentId") UUID enrollmentId,
                                  @Param("reason") JoinRequest.CancelReason reason, @Param("now") Instant now);

    @Modifying
    @Query("update JoinRequest r set r.status = com.capstone.tracking.team.JoinRequest.Status.CANCELLED, "
            + "r.cancelReason = :reason, r.respondedAt = :now "
            + "where r.group.id = :groupId and r.status = com.capstone.tracking.team.JoinRequest.Status.PENDING")
    int cancelPendingOfGroup(@Param("groupId") UUID groupId,
                             @Param("reason") JoinRequest.CancelReason reason, @Param("now") Instant now);

    /** The deadline closes every open request of the scope (3.4). */
    @Modifying
    @Query("update JoinRequest r set r.status = com.capstone.tracking.team.JoinRequest.Status.CANCELLED, "
            + "r.cancelReason = com.capstone.tracking.team.JoinRequest.CancelReason.DEADLINE, r.respondedAt = :now "
            + "where r.group.id in (select g.id from StudentGroup g where g.scope.id = :scopeId) and r.status = com.capstone.tracking.team.JoinRequest.Status.PENDING")
    int cancelPendingOfScope(@Param("scopeId") UUID scopeId, @Param("now") Instant now);

    @Modifying
    @Query("update JoinRequest r set r.status = com.capstone.tracking.team.JoinRequest.Status.EXPIRED, r.respondedAt = :now "
            + "where r.status = com.capstone.tracking.team.JoinRequest.Status.PENDING and r.expiresAt <= :now")
    int expireDue(@Param("now") Instant now);

    @Query("select count(r) from JoinRequest r where r.group.id in (select g.id from StudentGroup g where g.scope.id = :scopeId) and r.type = :type "
            + "and r.status = com.capstone.tracking.team.JoinRequest.Status.PENDING and r.expiresAt > :now")
    long countOpenInScope(@Param("scopeId") UUID scopeId, @Param("type") JoinRequest.Type type,
                          @Param("now") Instant now);
}
