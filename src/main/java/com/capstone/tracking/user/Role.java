package com.capstone.tracking.user;

/**
 * RBAC roles per blueprint.md §11 (Security, Privacy and Compliance).
 * Spring Security expects the "ROLE_" prefix on the granted authority, added in {@link User#getAuthorities()}.
 */
public enum Role {
    ADMIN,
    INSTRUCTOR,
    /** Hội đồng: approves topics, sits on the closed council (Review 3) and grades final defenses. */
    COUNCIL,
    GROUP_LEADER,
    STUDENT
}
