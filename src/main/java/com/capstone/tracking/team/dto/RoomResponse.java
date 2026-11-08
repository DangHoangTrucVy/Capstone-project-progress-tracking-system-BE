package com.capstone.tracking.team.dto;

import com.capstone.tracking.semester.SemesterPhase;
import com.capstone.tracking.team.RoomStatus;
import com.capstone.tracking.team.StudentGroup;
import com.capstone.tracking.user.dto.ProfileView;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * A room (or the group it became) as the viewer may see it. {@code code} is null until the room becomes a group.
 * Members' profiles follow 8.1: teammates and the Moderator see them in full, others see a private profile as name and
 * student code only.
 */
public record RoomResponse(
        UUID id,
        String code,
        String name,
        Integer roomNo,
        String wantedSkills,
        String semesterCode,
        SemesterPhase semesterPhase,
        UUID majorId,
        String majorCode,
        String majorName,
        int size,
        int minSize,
        int maxSize,
        boolean belowMinimum,
        RoomStatus status,
        String statusLabel,
        boolean full,
        boolean locked,
        StudentGroup.FinalizeStatus finalizeStatus,
        Instant finalizeRequestedAt,
        String finalizeNote,
        boolean finalized,
        Instant finalizedAt,
        UUID leaderId,
        String leaderName,
        List<Member> members,
        Instant createdAt
) {
    public record Member(UUID userId, String fullName, String studentCode, boolean leader, Instant joinedAt,
                         ProfileView profile) {}
}
