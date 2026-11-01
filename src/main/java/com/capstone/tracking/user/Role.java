package com.capstone.tracking.user;

/**
 * Account roles (requirement v2.8, mục 2). Moderator is not a role: it is a lecturer assigned to a semester
 * ({@code semesters.moderator_id}); Leader is not a role either: it is a member flag of one group.
 * Spring Security expects the "ROLE_" prefix on the granted authority, added in {@link User#getAuthorities()}.
 */
public enum Role {
    ADMIN,
    LECTURER,
    STUDENT
}
