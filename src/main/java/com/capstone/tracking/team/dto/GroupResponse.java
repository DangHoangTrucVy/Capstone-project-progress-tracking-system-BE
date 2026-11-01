package com.capstone.tracking.team.dto;

import com.capstone.tracking.subject.ScopePhase;
import com.capstone.tracking.team.GroupState;
import com.capstone.tracking.team.StudentGroup;
import com.capstone.tracking.user.dto.ProfileView;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * A group as the viewer may see it. Members' profiles follow 8.1: teammates and staff see them in full, others see a
 * private profile as name and student code only.
 */
public record GroupResponse(
        UUID id,
        String code,
        String name,
        String wantedSkills,
        UUID scopeId,
        UUID subjectId,
        String subjectCode,
        String semesterCode,
        String majorCode,
        String majorName,
        ScopePhase scopePhase,
        int size,
        int minSize,
        int maxSize,
        GroupState state,
        boolean full,
        boolean locked,
        boolean finalized,
        Instant finalizedAt,
        StudentGroup.DissolveReason dissolvedReason,
        Instant dissolvedAt,
        UUID leaderId,
        String leaderName,
        List<Member> members,
        Instant createdAt
) {
    public record Member(UUID userId, String fullName, String studentCode, boolean leader, Instant joinedAt,
                         ProfileView profile) {}
}
