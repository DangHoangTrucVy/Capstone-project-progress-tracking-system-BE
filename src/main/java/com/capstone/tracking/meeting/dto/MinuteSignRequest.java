package com.capstone.tracking.meeting.dto;

import jakarta.validation.constraints.NotNull;

/**
 * Matches API-009's payload, with an added optional {@code finalContent} so the Group Leader can
 * submit lightly-edited text alongside their sign-off (UC-003 step 5) — the literal contract has no
 * content field. For the Instructor's step, {@code decision} is required and {@code finalContent}
 * is ignored.
 */
public record MinuteSignRequest(
        @NotNull ApprovalDecision decision,
        String comments,
        String finalContent
) {
}
