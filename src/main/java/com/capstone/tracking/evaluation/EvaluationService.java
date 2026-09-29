package com.capstone.tracking.evaluation;

import com.capstone.tracking.audit.AuditAction;
import com.capstone.tracking.audit.AuditService;
import com.capstone.tracking.common.exception.ResourceNotFoundException;
import com.capstone.tracking.evaluation.dto.EvaluationCreateRequest;
import com.capstone.tracking.group.GroupMemberRepository;
import com.capstone.tracking.group.MemberStatus;
import com.capstone.tracking.group.StudentGroup;
import com.capstone.tracking.group.StudentGroupService;
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

/**
 * Sprint 5 — API-010. Per UC-004/US-007, an Instructor's "Save and Publish" is one click: this
 * creates the record already {@code PUBLISHED}, there is no separate Draft step in this build.
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class EvaluationService {

    private final EvaluationRecordRepository evaluationRecordRepository;
    private final StudentGroupService studentGroupService;
    private final GroupMemberRepository groupMemberRepository;
    private final AuditService auditService;

    @Transactional
    public EvaluationRecord create(UUID groupId, EvaluationCreateRequest request, User actingUser) {
        StudentGroup group = studentGroupService.getById(groupId);
        if (actingUser.getRole() != Role.INSTRUCTOR) {
            throw new AccessDeniedException("Only an Instructor can publish an evaluation");
        }
        studentGroupService.requireSupervisorOrAdmin(group, actingUser);
        double totalScore = EvaluationRecord.weightedTotal(
                request.topicFitScore(), request.productQualityScore(), request.communicationScore());

        EvaluationRecord evaluation = EvaluationRecord.builder()
                .group(group)
                .instructor(actingUser)
                .topicFitScore(request.topicFitScore())
                .productQualityScore(request.productQualityScore())
                .communicationScore(request.communicationScore())
                .totalScore(totalScore)
                .feedbackNotes(request.feedback())
                .evaluatedAt(Instant.now())
                .status(EvaluationStatus.PUBLISHED)
                .build();
        evaluation = evaluationRecordRepository.save(evaluation);

        auditService.record("EvaluationRecord", evaluation.getId(), AuditAction.CREATE, actingUser,
                Map.of("groupId", groupId, "totalScore", totalScore));
        return evaluation;
    }

    /**
     * STUDENT/GROUP_LEADER only ever see Published records (§11: Restricted until published).
     */
    public Page<EvaluationRecord> listByGroup(UUID groupId, User actingUser, Pageable pageable) {
        StudentGroup group = studentGroupService.getById(groupId);
        requireCanReadGroupEvaluations(group, actingUser);

        if (isPrivileged(group, actingUser)) {
            return evaluationRecordRepository.findByGroupId(groupId, pageable);
        }
        return evaluationRecordRepository.findByGroupIdAndStatus(groupId, EvaluationStatus.PUBLISHED, pageable);
    }

    public EvaluationRecord getById(UUID id, User actingUser) {
        EvaluationRecord evaluation = getById(id);
        StudentGroup group = evaluation.getGroup();
        requireCanReadEvaluation(evaluation, actingUser);

        if (!isPrivileged(group, actingUser) && evaluation.getStatus() != EvaluationStatus.PUBLISHED) {
            // Hides existence entirely for a non-privileged viewer, matching the Restricted classification.
            throw ResourceNotFoundException.of("EvaluationRecord", id);
        }
        return evaluation;
    }

    public EvaluationRecord getById(UUID id) {
        return evaluationRecordRepository.findById(id)
                .orElseThrow(() -> ResourceNotFoundException.of("EvaluationRecord", id));
    }

    private void requireCanReadGroupEvaluations(StudentGroup group, User actingUser) {
        if (actingUser == null) {
            throw new AccessDeniedException("Authentication required");
        }
        if (actingUser.getRole() == Role.ADMIN) {
            return;
        }
        if (actingUser.getRole() == Role.INSTRUCTOR) {
            if (group.getSupervisor() != null && group.getSupervisor().getId().equals(actingUser.getId())) {
                return;
            }
            throw new AccessDeniedException("Only the group's supervisor can view its evaluations");
        }
        if (actingUser.getRole() == Role.GROUP_LEADER || actingUser.getRole() == Role.STUDENT) {
            if (!groupMemberRepository.existsByGroupIdAndUserIdAndStatus(group.getId(), actingUser.getId(), MemberStatus.ACTIVE)) {
                throw new AccessDeniedException("You are not an active member of this group");
            }
            return;
        }
        throw new AccessDeniedException("This role cannot view group evaluations");
    }

    private void requireCanReadEvaluation(EvaluationRecord evaluation, User actingUser) {
        if (actingUser == null) {
            throw new AccessDeniedException("Authentication required");
        }
        if (actingUser.getRole() == Role.ADMIN) {
            return;
        }
        StudentGroup group = evaluation.getGroup();
        if (actingUser.getRole() == Role.INSTRUCTOR) {
            boolean isSupervisor = group != null && group.getSupervisor() != null && group.getSupervisor().getId().equals(actingUser.getId());
            boolean isEvaluator = evaluation.getInstructor() != null && evaluation.getInstructor().getId().equals(actingUser.getId());
            if (isSupervisor || isEvaluator) {
                return;
            }
            throw new AccessDeniedException("You are not the supervisor or evaluator for this evaluation");
        }
        if (actingUser.getRole() == Role.GROUP_LEADER || actingUser.getRole() == Role.STUDENT) {
            if (group == null || !groupMemberRepository.existsByGroupIdAndUserIdAndStatus(group.getId(), actingUser.getId(), MemberStatus.ACTIVE)) {
                throw new AccessDeniedException("You are not an active member of this group");
            }
            return;
        }
        throw new AccessDeniedException("This role cannot view group evaluations");
    }

    private boolean isPrivileged(StudentGroup group, User user) {
        if (user == null) {
            return false;
        }
        return user.getRole() == Role.ADMIN
                || (user.getRole() == Role.INSTRUCTOR && group != null && group.getSupervisor() != null && group.getSupervisor().getId().equals(user.getId()));
    }
}
