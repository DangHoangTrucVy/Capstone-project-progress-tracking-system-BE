package com.capstone.tracking.group;

import jakarta.persistence.LockModeType;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface GroupJoinRequestRepository extends JpaRepository<GroupJoinRequest, UUID> {

    /** Serializes the leader's, applicant's and invitee's decisions on the same request. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select r from GroupJoinRequest r where r.id = :id")
    Optional<GroupJoinRequest> lockById(@Param("id") UUID id);

    List<GroupJoinRequest> findByGroupIdAndTypeOrderByCreatedAtDesc(UUID groupId, JoinRequestType type);

    List<GroupJoinRequest> findByStudentIdAndTypeOrderByCreatedAtDesc(UUID studentId, JoinRequestType type);

    /** Open (not yet expired) requests of a type; YC08 caps a student's open Applies at 3. */
    @Query("select count(r) from GroupJoinRequest r where r.student.id = :studentId and r.type = :type "
            + "and r.status = com.capstone.tracking.group.JoinRequestStatus.PENDING and r.expiresAt > :now")
    long countOpen(@Param("studentId") UUID studentId, @Param("type") JoinRequestType type, @Param("now") Instant now);

    @Query("select r from GroupJoinRequest r where r.group.id = :groupId and r.student.id = :studentId "
            + "and r.type = :type and r.status = com.capstone.tracking.group.JoinRequestStatus.PENDING")
    Optional<GroupJoinRequest> findPending(@Param("groupId") UUID groupId, @Param("studentId") UUID studentId,
                                           @Param("type") JoinRequestType type);

    /** YC13: the student joined a group, so every other open Apply/Invite of theirs is void. */
    @Modifying
    @Query("update GroupJoinRequest r set r.status = com.capstone.tracking.group.JoinRequestStatus.CANCELLED, "
            + "r.respondedAt = :now where r.student.id = :studentId and r.id <> :exceptId "
            + "and r.status = com.capstone.tracking.group.JoinRequestStatus.PENDING")
    int cancelOtherPending(@Param("studentId") UUID studentId, @Param("exceptId") UUID exceptId, @Param("now") Instant now);

    @Modifying
    @Query("update GroupJoinRequest r set r.status = com.capstone.tracking.group.JoinRequestStatus.CANCELLED, "
            + "r.respondedAt = :now where r.student.id = :studentId "
            + "and r.status = com.capstone.tracking.group.JoinRequestStatus.PENDING")
    int cancelAllPending(@Param("studentId") UUID studentId, @Param("now") Instant now);

    /** YC14: write down requests whose deadline passed. */
    @Modifying
    @Query("update GroupJoinRequest r set r.status = com.capstone.tracking.group.JoinRequestStatus.EXPIRED, "
            + "r.respondedAt = :now where r.status = com.capstone.tracking.group.JoinRequestStatus.PENDING "
            + "and r.expiresAt <= :now")
    int expireDue(@Param("now") Instant now);
}
