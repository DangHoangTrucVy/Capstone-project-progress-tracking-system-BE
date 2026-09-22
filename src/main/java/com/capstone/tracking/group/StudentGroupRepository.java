package com.capstone.tracking.group;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.UUID;

public interface StudentGroupRepository extends JpaRepository<StudentGroup, UUID> {

    boolean existsByGroupCodeIgnoreCase(String groupCode);

    Page<StudentGroup> findBySupervisorId(UUID supervisorId, Pageable pageable);

    Page<StudentGroup> findByTopicId(UUID topicId, Pageable pageable);

    @Query("select g from StudentGroup g where "
            + "(select count(m) from GroupMember m where m.group = g and m.status = :active) < :max")
    Page<StudentGroup> findNotFull(@Param("active") MemberStatus active, @Param("max") long max, Pageable pageable);
}
