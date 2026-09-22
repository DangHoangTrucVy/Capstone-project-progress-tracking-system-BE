package com.capstone.tracking.evaluation;

import com.capstone.tracking.audit.AuditAction;
import com.capstone.tracking.audit.AuditService;
import com.capstone.tracking.common.exception.ResourceNotFoundException;
import com.capstone.tracking.evaluation.dto.EvaluationCreateRequest;
import com.capstone.tracking.group.StudentGroup;
import com.capstone.tracking.group.StudentGroupService;
import com.capstone.tracking.user.Role;
import com.capstone.tracking.user.User;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Sprint 5 — API-010. Per UC-004/US-007, an Instructor's "Save and Publish" is one click: this
 * creates the record already {@code PUBLISHED}, there is no separate Draft step in this build.
 */
@Service
@RequiredArgsConstructor
public class EvaluationService {

    private final EvaluationRecordRepository evaluationRecordRepository;
    private final StudentGroupService studentGroupService;
    private final AuditService auditService;

    @Transactional
    public EvaluationRecord create(UUID groupId, EvaluationCreateRequest request, User actingUser) {
        StudentGroup group = studentGroupService.getById(groupId);
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
     * The filter is applied post-pagination, so a non-privileged viewer's page total reflects only
     * the Published rows within that page, not the group's true total — acceptable at this data
     * scale (at most one evaluation per group per period).
     */
    public Page<EvaluationRecord> listByGroup(UUID groupId, User actingUser, Pageable pageable) {
        Page<EvaluationRecord> page = evaluationRecordRepository.findByGroupId(groupId, pageable);
        if (isPrivileged(actingUser)) {
            return page;
        }
        List<EvaluationRecord> published = page.getContent().stream()
                .filter(e -> e.getStatus() == EvaluationStatus.PUBLISHED)
                .toList();
        return new PageImpl<>(published, pageable, published.size());
    }

    public EvaluationRecord getById(UUID id, User actingUser) {
        EvaluationRecord evaluation = evaluationRecordRepository.findById(id)
                .orElseThrow(() -> ResourceNotFoundException.of("EvaluationRecord", id));
        if (!isPrivileged(actingUser) && evaluation.getStatus() != EvaluationStatus.PUBLISHED) {
            // Hides existence entirely for a non-privileged viewer, matching the Restricted classification.
            throw ResourceNotFoundException.of("EvaluationRecord", id);
        }
        return evaluation;
    }

    private boolean isPrivileged(User user) {
        return user.getRole() == Role.ADMIN || user.getRole() == Role.INSTRUCTOR;
    }
}
