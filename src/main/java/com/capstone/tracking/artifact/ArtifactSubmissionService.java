package com.capstone.tracking.artifact;

import com.capstone.tracking.artifact.dto.ArtifactCreateRequest;
import com.capstone.tracking.audit.AuditAction;
import com.capstone.tracking.audit.AuditService;
import com.capstone.tracking.common.exception.BadRequestException;
import com.capstone.tracking.common.exception.ResourceNotFoundException;
import com.capstone.tracking.group.GroupMemberRepository;
import com.capstone.tracking.group.MemberStatus;
import com.capstone.tracking.group.StudentGroup;
import com.capstone.tracking.group.StudentGroupService;
import com.capstone.tracking.meeting.MeetingSession;
import com.capstone.tracking.meeting.MeetingSessionRepository;
import com.capstone.tracking.user.Role;
import com.capstone.tracking.user.User;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/** Sprint 3 — API-005: group members submit progress artifacts, Instructor/Admin accept them. */
@Service
@RequiredArgsConstructor
public class ArtifactSubmissionService {

    private final ArtifactSubmissionRepository artifactSubmissionRepository;
    private final StudentGroupService studentGroupService;
    private final GroupMemberRepository groupMemberRepository;
    private final MeetingSessionRepository meetingSessionRepository;
    private final AuditService auditService;

    @Transactional
    public ArtifactSubmission create(UUID groupId, ArtifactCreateRequest request, User actingUser) {
        StudentGroup group = studentGroupService.getById(groupId);
        requireMembership(groupId, actingUser);

        MeetingSession session = null;
        if (request.sessionId() != null) {
            session = meetingSessionRepository.findById(request.sessionId())
                    .orElseThrow(() -> ResourceNotFoundException.of("MeetingSession", request.sessionId()));
        }

        int version = 1;
        var previous = artifactSubmissionRepository.findTopByGroup_IdAndTitleOrderByVersionDesc(groupId, request.title());
        if (previous.isPresent()) {
            ArtifactSubmission old = previous.get();
            old.setStatus(ArtifactStatus.SUPERCEDED);
            version = old.getVersion() + 1;
        }

        ArtifactSubmission artifact = ArtifactSubmission.builder()
                .group(group)
                .session(session)
                .title(request.title())
                .fileUrl(request.fileUrl())
                .fileType(request.fileType())
                .version(version)
                .submittedAt(Instant.now())
                .status(ArtifactStatus.SUBMITTED)
                .build();
        artifact = artifactSubmissionRepository.save(artifact);

        auditService.record("ArtifactSubmission", artifact.getId(), AuditAction.CREATE, actingUser,
                Map.of("groupId", groupId, "version", version));
        return artifact;
    }

    @Transactional
    public ArtifactSubmission accept(UUID id, User actingUser) {
        ArtifactSubmission artifact = getById(id);
        if (artifact.getStatus() != ArtifactStatus.SUBMITTED) {
            throw new BadRequestException("Only a Submitted artifact can be accepted");
        }
        artifact.setStatus(ArtifactStatus.ACCEPTED);
        auditService.record("ArtifactSubmission", artifact.getId(), AuditAction.APPROVE, actingUser, Map.of());
        return artifact;
    }

    public ArtifactSubmission getById(UUID id) {
        return artifactSubmissionRepository.findById(id)
                .orElseThrow(() -> ResourceNotFoundException.of("ArtifactSubmission", id));
    }

    public Page<ArtifactSubmission> listByGroup(UUID groupId, Pageable pageable) {
        return artifactSubmissionRepository.findByGroupId(groupId, pageable);
    }

    private void requireMembership(UUID groupId, User actingUser) {
        if ((actingUser.getRole() == Role.STUDENT || actingUser.getRole() == Role.GROUP_LEADER)
                && !groupMemberRepository.existsByGroupIdAndUserIdAndStatus(groupId, actingUser.getId(), MemberStatus.ACTIVE)) {
            throw new AccessDeniedException("You are not an active member of this group");
        }
    }
}
