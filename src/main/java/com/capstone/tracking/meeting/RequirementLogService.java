package com.capstone.tracking.meeting;

import com.capstone.tracking.audit.AuditAction;
import com.capstone.tracking.audit.AuditService;
import com.capstone.tracking.common.exception.ResourceNotFoundException;
import com.capstone.tracking.group.GroupMemberRepository;
import com.capstone.tracking.group.MemberStatus;
import com.capstone.tracking.group.StudentGroup;
import com.capstone.tracking.meeting.dto.RequirementCreateRequest;
import com.capstone.tracking.meeting.dto.RequirementUpdateRequest;
import com.capstone.tracking.user.Role;
import com.capstone.tracking.user.User;
import com.capstone.tracking.user.UserService;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Map;
import java.util.UUID;

/** Sprint 4 — API-007: requirements logged during a meeting session. */
@Service
@RequiredArgsConstructor
public class RequirementLogService {

    private final RequirementLogRepository requirementLogRepository;
    private final MeetingSessionRepository meetingSessionRepository;
    private final GroupMemberRepository groupMemberRepository;
    private final UserService userService;
    private final AuditService auditService;

    @Transactional
    public RequirementLog create(UUID sessionId, RequirementCreateRequest request, User actingUser) {
        MeetingSession session = meetingSessionRepository.findById(sessionId)
                .orElseThrow(() -> ResourceNotFoundException.of("MeetingSession", sessionId));
        StudentGroup group = session.getBooking().getGroup();
        requireMembership(group, actingUser);

        RequirementLog log = RequirementLog.builder()
                .session(session)
                .group(group)
                .title(request.title())
                .description(request.description())
                .priority(request.priority())
                .status(RequirementStatus.OPEN)
                .build();
        log = requirementLogRepository.save(log);

        auditService.record("RequirementLog", log.getId(), AuditAction.CREATE, actingUser,
                Map.of("sessionId", sessionId));
        return log;
    }

    @Transactional
    public RequirementLog update(UUID id, RequirementUpdateRequest request, User actingUser) {
        RequirementLog log = getById(id);
        requireMembership(log.getGroup(), actingUser);

        if (request.status() != null) {
            log.setStatus(request.status());
        }
        if (request.assignedTo() != null) {
            log.setAssignedTo(userService.getById(request.assignedTo()));
        }
        auditService.record("RequirementLog", log.getId(), AuditAction.UPDATE, actingUser, Map.of());
        return log;
    }

    public RequirementLog getById(UUID id) {
        return requirementLogRepository.findById(id)
                .orElseThrow(() -> ResourceNotFoundException.of("RequirementLog", id));
    }

    public Page<RequirementLog> listBySession(UUID sessionId, Pageable pageable) {
        return requirementLogRepository.findBySessionId(sessionId, pageable);
    }

    private void requireMembership(StudentGroup group, User actingUser) {
        if ((actingUser.getRole() == Role.STUDENT || actingUser.getRole() == Role.GROUP_LEADER)
                && !groupMemberRepository.existsByGroupIdAndUserIdAndStatus(group.getId(), actingUser.getId(), MemberStatus.ACTIVE)) {
            throw new AccessDeniedException("You are not an active member of this group");
        }
    }
}
