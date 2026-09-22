package com.capstone.tracking.group;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface GroupMemberRepository extends JpaRepository<GroupMember, UUID> {

    List<GroupMember> findByGroupIdAndStatus(UUID groupId, MemberStatus status);

    Optional<GroupMember> findByGroupIdAndUserIdAndStatus(UUID groupId, UUID userId, MemberStatus status);

    boolean existsByGroupIdAndUserIdAndStatus(UUID groupId, UUID userId, MemberStatus status);

    boolean existsByGroupIdAndIsLeaderTrueAndStatus(UUID groupId, MemberStatus status);

    boolean existsByUserIdAndStatus(UUID userId, MemberStatus status);

    long countByGroupIdAndStatus(UUID groupId, MemberStatus status);

    @Query("select m.group.id, count(m) from GroupMember m "
            + "where m.group.id in :groupIds and m.status = :status group by m.group.id")
    List<Object[]> countByGroupIds(@Param("groupIds") Collection<UUID> groupIds, @Param("status") MemberStatus status);
}
