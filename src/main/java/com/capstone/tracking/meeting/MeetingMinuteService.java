package com.capstone.tracking.meeting;

import com.capstone.tracking.audit.AuditAction;
import com.capstone.tracking.audit.AuditService;
import com.capstone.tracking.common.exception.BadRequestException;
import com.capstone.tracking.common.exception.ConflictException;
import com.capstone.tracking.common.exception.ResourceNotFoundException;
import com.capstone.tracking.group.GroupMemberRepository;
import com.capstone.tracking.group.MemberStatus;
import com.capstone.tracking.group.StudentGroup;
import com.capstone.tracking.meeting.dto.ApprovalDecision;
import com.capstone.tracking.meeting.dto.MinuteGenerateRequest;
import com.capstone.tracking.meeting.dto.MinuteGenerateResponse;
import com.capstone.tracking.meeting.dto.MinuteSignRequest;
import com.capstone.tracking.user.Role;
import com.capstone.tracking.user.User;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Pageable;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Sprint 4 — API-008/API-009. {@link #generate} is a deterministic text template, not a real AI
 * call (no AI service is wired into this codebase); it exists to hold the workflow together and
 * give the group leader something concrete to review and edit before submitting for approval.
 */
@Service
@RequiredArgsConstructor
public class MeetingMinuteService {

    private final MeetingMinuteRepository meetingMinuteRepository;
    private final MeetingSessionRepository meetingSessionRepository;
    private final RequirementLogRepository requirementLogRepository;
    private final GroupMemberRepository groupMemberRepository;
    private final AuditService auditService;

    @Transactional
    public MinuteGenerateResponse generate(UUID sessionId, MinuteGenerateRequest request, User actingUser) {
        MeetingSession session = meetingSessionRepository.findById(sessionId)
                .orElseThrow(() -> ResourceNotFoundException.of("MeetingSession", sessionId));
        requireMembership(session.getBooking().getGroup(), actingUser);

        String notes = request != null && request.notes() != null && !request.notes().isBlank()
                ? request.notes()
                : session.getRawNotes();
        if (notes == null || notes.isBlank()) {
            throw new BadRequestException("Cannot generate minutes from empty notes");
        }
        if (request != null && request.notes() != null && !request.notes().isBlank()) {
            session.setRawNotes(notes);
        }

        Optional<MeetingMinute> existing = meetingMinuteRepository.findBySessionId(sessionId);
        if (existing.isPresent() && existing.get().getStatus() != MinuteStatus.DRAFT) {
            throw new BadRequestException("Minutes have already been submitted for approval and can no longer be regenerated");
        }

        var requirements = requirementLogRepository.findBySessionId(sessionId, Pageable.unpaged()).getContent();
        Map<String, String> sections = new LinkedHashMap<>();
        sections.put("Objectives", "Review progress and outstanding requirements for the group's session.");
        sections.put("Discussion", notes);
        sections.put("New Requirements", requirements.isEmpty()
                ? "None logged during this session."
                : requirements.stream()
                        .map(r -> "- " + r.getTitle() + " (" + r.getPriority() + ")")
                        .collect(Collectors.joining("\n")));
        sections.put("Conclusion", "Pending group and instructor review.");

        String draft = sections.entrySet().stream()
                .map(e -> "## " + e.getKey() + "\n" + e.getValue())
                .collect(Collectors.joining("\n\n"));

        MeetingMinute minute = existing.orElseGet(() -> MeetingMinute.builder()
                .session(session)
                .status(MinuteStatus.DRAFT)
                .build());
        boolean isNew = minute.getId() == null;
        minute.setGeneratedContent(draft);
        minute = meetingMinuteRepository.save(minute);

        auditService.record("MeetingMinute", minute.getId(), isNew ? AuditAction.CREATE : AuditAction.UPDATE,
                actingUser, Map.of("sessionId", sessionId));

        return new MinuteGenerateResponse(draft, sections);
    }

    @Transactional
    public MeetingMinute sign(UUID sessionId, MinuteSignRequest request, User actingUser) {
        MeetingMinute minute = getBySession(sessionId);
        StudentGroup group = minute.getSession().getBooking().getGroup();

        if (actingUser.getRole() == Role.GROUP_LEADER) {
            if (minute.getStatus() != MinuteStatus.DRAFT && minute.getStatus() != MinuteStatus.REJECTED) {
                throw new ConflictException("Minutes are not awaiting Group Leader submission");
            }
            requireMembership(group, actingUser);
            minute.setFinalContent(request.finalContent() != null ? request.finalContent() : minute.getGeneratedContent());
            minute.setStudentSignedAt(Instant.now());
            minute.setStatus(MinuteStatus.UNDER_REVIEW);
            auditService.record("MeetingMinute", minute.getId(), AuditAction.SIGN, actingUser, Map.of());
        } else if (actingUser.getRole() == Role.INSTRUCTOR || actingUser.getRole() == Role.ADMIN) {
            if (minute.getStatus() != MinuteStatus.UNDER_REVIEW) {
                throw new ConflictException("Minutes are not awaiting Instructor approval");
            }
            minute.setInstructorSignedAt(Instant.now());
            if (request.decision() == ApprovalDecision.APPROVE) {
                minute.setStatus(MinuteStatus.APPROVED);
                auditService.record("MeetingMinute", minute.getId(), AuditAction.APPROVE, actingUser,
                        Map.of("comments", request.comments() == null ? "" : request.comments()));
            } else {
                minute.setStatus(MinuteStatus.REJECTED);
                auditService.record("MeetingMinute", minute.getId(), AuditAction.REJECT, actingUser,
                        Map.of("comments", request.comments() == null ? "" : request.comments()));
            }
        } else {
            throw new AccessDeniedException("This role cannot sign meeting minutes");
        }
        return minute;
    }

    public MeetingMinute getBySession(UUID sessionId) {
        return meetingMinuteRepository.findBySessionId(sessionId)
                .orElseThrow(() -> ResourceNotFoundException.of("MeetingMinute for session", sessionId));
    }

    private void requireMembership(StudentGroup group, User actingUser) {
        if ((actingUser.getRole() == Role.STUDENT || actingUser.getRole() == Role.GROUP_LEADER)
                && !groupMemberRepository.existsByGroupIdAndUserIdAndStatus(group.getId(), actingUser.getId(), MemberStatus.ACTIVE)) {
            throw new AccessDeniedException("You are not an active member of this group");
        }
    }
}
