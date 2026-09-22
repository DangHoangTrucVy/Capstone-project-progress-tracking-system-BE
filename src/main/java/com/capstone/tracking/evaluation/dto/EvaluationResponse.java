package com.capstone.tracking.evaluation.dto;

import com.capstone.tracking.evaluation.EvaluationRecord;
import com.capstone.tracking.evaluation.EvaluationStatus;

import java.time.Instant;
import java.util.UUID;

/**
 * The only way EvaluationRecord (§11 Restricted Confidential) is ever exposed through a controller —
 * never return the entity itself.
 */
public record EvaluationResponse(
        UUID id,
        UUID groupId,
        UUID instructorId,
        int topicFitScore,
        int productQualityScore,
        int communicationScore,
        double totalScore,
        String feedbackNotes,
        Instant evaluatedAt,
        EvaluationStatus status
) {
    public static EvaluationResponse from(EvaluationRecord e) {
        return new EvaluationResponse(
                e.getId(),
                e.getGroup().getId(),
                e.getInstructor().getId(),
                e.getTopicFitScore(),
                e.getProductQualityScore(),
                e.getCommunicationScore(),
                e.getTotalScore(),
                e.getFeedbackNotes(),
                e.getEvaluatedAt(),
                e.getStatus());
    }
}
