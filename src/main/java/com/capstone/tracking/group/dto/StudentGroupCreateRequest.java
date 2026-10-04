package com.capstone.tracking.group.dto;

import jakarta.validation.constraints.NotBlank;

import java.util.UUID;

/**
 * A student creates a group with just a semester (and optionally a code; one is generated otherwise) and becomes its
 * Leader (YC07). topicId/supervisorId are Admin-only and optional: groups form before a topic/supervisor is assigned.
 */
public record StudentGroupCreateRequest(
        String groupCode,
        UUID topicId,
        UUID supervisorId,
        @NotBlank String semester
) {
}
