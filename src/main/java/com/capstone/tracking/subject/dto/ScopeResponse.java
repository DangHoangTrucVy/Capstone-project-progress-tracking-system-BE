package com.capstone.tracking.subject.dto;

import com.capstone.tracking.subject.FormationScope;
import com.capstone.tracking.subject.ScopePhase;
import java.time.Instant;
import java.util.UUID;

/** A formation scope: one major of one subject, its phase and its deadline (own or common). */
public record ScopeResponse(
        UUID id,
        UUID subjectId,
        String subjectCode,
        String subjectName,
        String semesterCode,
        UUID majorId,
        String majorCode,
        String majorName,
        ScopePhase phase,
        Instant deadline,
        boolean ownDeadline,
        Instant openedAt,
        Instant lockedAt,
        Instant finalizedAt,
        Instant previousFinalizedAt,
        Instant reopenedAt,
        String reopenReason,
        String lastChangeAfterFinalize,
        Instant lastChangeAfterFinalizeAt,
        long studentCount,
        long groupCount
) {
    public static ScopeResponse from(FormationScope s, Instant deadline, long students, long groups) {
        return new ScopeResponse(s.getId(), s.getSubject().getId(), s.getSubject().getCode(), s.getSubject().getName(),
                s.semesterCode(), s.getMajor().getId(), s.getMajor().getCode(), s.getMajor().getName(), s.getPhase(),
                deadline, s.getDeadlineOverride() != null, s.getOpenedAt(), s.getLockedAt(), s.getFinalizedAt(),
                s.getPreviousFinalizedAt(), s.getReopenedAt(), s.getReopenReason(), s.getLastChangeAfterFinalize(),
                s.getLastChangeAfterFinalizeAt(), students, groups);
    }
}
