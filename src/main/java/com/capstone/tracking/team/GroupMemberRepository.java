package com.capstone.tracking.team;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface GroupMemberRepository extends JpaRepository<GroupMember, UUID> {

    /** Active members, oldest first: the order used to pick a new Leader (6.5) and to list the group. */
    @Query("select m from GroupMember m where m.group.id = :groupId and m.leftAt is null "
            + "order by m.joinedAt asc, m.user.studentCode asc")
    List<GroupMember> findActive(@Param("groupId") UUID groupId);

    @Query("select m from GroupMember m join fetch m.group where m.group.id in :groupIds and m.leftAt is null "
            + "order by m.joinedAt asc, m.user.studentCode asc")
    List<GroupMember> findActiveIn(@Param("groupIds") Collection<UUID> groupIds);

    @Query("select count(m) from GroupMember m where m.group.id = :groupId and m.leftAt is null")
    long countActive(@Param("groupId") UUID groupId);

    @Query("select m from GroupMember m join fetch m.group where m.enrollment.id = :enrollmentId and m.leftAt is null")
    Optional<GroupMember> findActiveByEnrollment(@Param("enrollmentId") UUID enrollmentId);

    @Query("select m from GroupMember m where m.group.id = :groupId and m.user.id = :userId and m.leftAt is null")
    Optional<GroupMember> findActiveInGroup(@Param("groupId") UUID groupId, @Param("userId") UUID userId);

    @Query("select m from GroupMember m join fetch m.group where m.enrollment.id in :enrollmentIds and m.leftAt is null")
    List<GroupMember> findActiveByEnrollments(@Param("enrollmentIds") Collection<UUID> enrollmentIds);
}
