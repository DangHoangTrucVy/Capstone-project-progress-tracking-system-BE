package com.capstone.tracking.eligibility;

import java.util.UUID;

/**
 * Published (synchronously, inside the flagging transaction) when a student is marked not eligible for the capstone,
 * so other modules can react without the eligibility module depending on them.
 */
public record StudentMarkedIneligible(UUID userId, String reason) {
}
