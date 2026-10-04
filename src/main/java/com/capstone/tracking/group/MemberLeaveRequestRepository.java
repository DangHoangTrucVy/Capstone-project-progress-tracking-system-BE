package com.capstone.tracking.group;

import jakarta.persistence.LockModeType;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface MemberLeaveRequestRepository extends JpaRepository<MemberLeaveRequest, UUID> {

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select r from MemberLeaveRequest r where r.id = :id")
    Optional<MemberLeaveRequest> lockById(@Param("id") UUID id);

    List<MemberLeaveRequest> findByGroupIdOrderByCreatedAtDesc(UUID groupId);

    Optional<MemberLeaveRequest> findByGroupIdAndUserIdAndStatus(UUID groupId, UUID userId, LeaveRequestStatus status);

    List<MemberLeaveRequest> findByGroupIdAndUserIdAndStatusIn(UUID groupId, UUID userId, List<LeaveRequestStatus> statuses);
}
