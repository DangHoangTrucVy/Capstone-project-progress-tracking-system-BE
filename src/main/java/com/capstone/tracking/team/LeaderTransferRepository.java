package com.capstone.tracking.team;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface LeaderTransferRepository extends JpaRepository<LeaderTransfer, UUID> {

    @Query("select t from LeaderTransfer t where t.group.id = :groupId "
            + "and t.status = com.capstone.tracking.team.LeaderTransfer.Status.PENDING")
    Optional<LeaderTransfer> findPending(@Param("groupId") UUID groupId);

    @Query("select t from LeaderTransfer t where (t.toUser.id = :userId or t.fromUser.id = :userId) "
            + "order by t.createdAt desc")
    List<LeaderTransfer> findInvolving(@Param("userId") UUID userId);

    /** Any change of Leader or members voids a pending transfer of that group. */
    @Modifying
    @Query("update LeaderTransfer t set t.status = com.capstone.tracking.team.LeaderTransfer.Status.CANCELLED, "
            + "t.respondedAt = :now where t.group.id = :groupId "
            + "and t.status = com.capstone.tracking.team.LeaderTransfer.Status.PENDING")
    int cancelPendingOfGroup(@Param("groupId") UUID groupId, @Param("now") Instant now);

    /** Finalizing (and reopening) a list voids the transfers proposed before it. */
    @Modifying
    @Query("update LeaderTransfer t set t.status = com.capstone.tracking.team.LeaderTransfer.Status.CANCELLED, "
            + "t.respondedAt = :now where t.group.id in (select g.id from StudentGroup g where g.scope.id = :scopeId) "
            + "and t.status = com.capstone.tracking.team.LeaderTransfer.Status.PENDING")
    int cancelPendingOfScope(@Param("scopeId") UUID scopeId, @Param("now") Instant now);

    /** Transfer-and-leave cannot happen once nobody may leave by themselves (the deadline). */
    @Modifying
    @Query("update LeaderTransfer t set t.status = com.capstone.tracking.team.LeaderTransfer.Status.CANCELLED, "
            + "t.respondedAt = :now where t.group.id in (select g.id from StudentGroup g where g.scope.id = :scopeId) and t.leaveAfter = true "
            + "and t.status = com.capstone.tracking.team.LeaderTransfer.Status.PENDING")
    int cancelPendingLeavesOfScope(@Param("scopeId") UUID scopeId, @Param("now") Instant now);
}
