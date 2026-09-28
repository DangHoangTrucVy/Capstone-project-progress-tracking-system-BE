package com.capstone.tracking.warning;

import com.capstone.tracking.audit.AuditAction;
import com.capstone.tracking.audit.AuditService;
import com.capstone.tracking.common.exception.BadRequestException;
import com.capstone.tracking.common.exception.ConflictException;
import com.capstone.tracking.common.exception.ResourceNotFoundException;
import com.capstone.tracking.group.GroupMemberRepository;
import com.capstone.tracking.group.MemberStatus;
import com.capstone.tracking.group.StudentGroup;
import com.capstone.tracking.group.StudentGroupService;
import com.capstone.tracking.notification.DomainEvent;
import com.capstone.tracking.notification.DomainEventType;
import com.capstone.tracking.user.User;
import com.capstone.tracking.user.UserService;
import com.capstone.tracking.warning.dto.WarningFlagRequest;
import com.capstone.tracking.warning.dto.WarningFlagResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Bước 4.4: the group's supervisor (or an Admin) raises and resolves warning flags; members see them as badges. */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class WarningFlagService {

    private final WarningFlagRepository flagRepository;
    private final StudentGroupService studentGroupService;
    private final GroupMemberRepository groupMemberRepository;
    private final UserService userService;
    private final AuditService auditService;
    private final ApplicationEventPublisher events;

    @Transactional
    public WarningFlagResponse raise(UUID groupId, WarningFlagRequest request, User actingUser) {
        StudentGroup group = studentGroupService.getById(groupId);
        studentGroupService.requireSupervisorOrAdmin(group, actingUser);

        User member = null;
        if (request.type() == WarningFlagType.MEMBER_INACTIVE) {
            if (request.memberId() == null) {
                throw new BadRequestException("memberId is required for a MEMBER_INACTIVE flag");
            }
            if (!groupMemberRepository.existsByGroupIdAndUserIdAndStatus(groupId, request.memberId(), MemberStatus.ACTIVE)) {
                throw new BadRequestException("User " + request.memberId() + " is not an active member of this group");
            }
            member = userService.getById(request.memberId());
        }

        WarningFlag flag = flagRepository.save(WarningFlag.builder()
                .group(group)
                .member(member)
                .type(request.type())
                .severity(request.severity())
                .reason(request.reason())
                .raisedBy(actingUser)
                .raisedAt(Instant.now())
                .build());

        Map<String, Object> details = new HashMap<>();
        details.put("type", request.type());
        details.put("severity", request.severity());
        if (member != null) {
            details.put("memberId", member.getId());
        }
        auditService.record("WarningFlag", flag.getId(), AuditAction.CREATE, actingUser, details);
        events.publishEvent(DomainEvent.of(DomainEventType.WARNING_FLAG_RAISED, groupId, flag.getId(),
                actingUser.getId(), label(flag)).withDetails(request.reason(), null));
        return WarningFlagResponse.from(flag);
    }

    @Transactional
    public WarningFlagResponse resolve(UUID id, String note, User actingUser) {
        WarningFlag flag = flagRepository.findById(id).orElseThrow(() -> ResourceNotFoundException.of("WarningFlag", id));
        studentGroupService.requireSupervisorOrAdmin(flag.getGroup(), actingUser);
        if (!flag.isActive()) {
            throw new ConflictException("This warning flag is already resolved");
        }
        flag.setResolvedBy(actingUser);
        flag.setResolvedAt(Instant.now());
        flag.setResolutionNote(note);

        auditService.record("WarningFlag", flag.getId(), AuditAction.UPDATE, actingUser, Map.of("resolved", true));
        events.publishEvent(DomainEvent.of(DomainEventType.WARNING_FLAG_RESOLVED, flag.getGroup().getId(), flag.getId(),
                actingUser.getId(), label(flag)).withDetails(note, null));
        return WarningFlagResponse.from(flag);
    }

    public List<WarningFlagResponse> listByGroup(UUID groupId, boolean activeOnly, User actingUser) {
        studentGroupService.getById(groupId);
        studentGroupService.requireCanView(groupId, actingUser);
        List<WarningFlag> flags = activeOnly
                ? flagRepository.findByGroupIdAndResolvedAtIsNullOrderByRaisedAtDesc(groupId)
                : flagRepository.findByGroupIdOrderByRaisedAtDesc(groupId);
        return flags.stream().map(WarningFlagResponse::from).toList();
    }

    private static String label(WarningFlag flag) {
        return switch (flag.getType()) {
            case GROUP_BEHIND_SCHEDULE -> "Nhóm chậm tiến độ (" + flag.getSeverity() + ")";
            case MEMBER_INACTIVE -> "Thành viên " + flag.getMember().getFullName() + " không chủ động (" + flag.getSeverity() + ")";
        };
    }
}
