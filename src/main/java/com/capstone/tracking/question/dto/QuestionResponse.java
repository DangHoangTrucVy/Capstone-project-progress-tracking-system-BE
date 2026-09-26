package com.capstone.tracking.question.dto;

import com.capstone.tracking.question.QuestionBankItem;
import com.capstone.tracking.question.QuestionStatus;
import com.capstone.tracking.user.Role;

import java.util.UUID;

public record QuestionResponse(
        UUID id,
        UUID topicId,
        String category,
        String questionText,
        String guidanceNotes,
        QuestionStatus status,
        UUID createdById,
        String createdByName,
        Role createdByRole
) {
    public static QuestionResponse from(QuestionBankItem q) {
        return new QuestionResponse(q.getId(), q.getTopic().getId(), q.getCategory(),
                q.getQuestionText(), q.getGuidanceNotes(), q.getStatus(),
                q.getCreatedBy().getId(), q.getCreatedBy().getFullName(), q.getCreatedBy().getRole());
    }
}
