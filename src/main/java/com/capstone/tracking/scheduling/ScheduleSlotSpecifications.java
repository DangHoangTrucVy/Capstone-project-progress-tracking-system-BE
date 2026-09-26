package com.capstone.tracking.scheduling;

import org.springframework.data.jpa.domain.Specification;

import java.time.Instant;
import java.util.UUID;

/**
 * Optional filters for API-002 (slot search). Each spec returns a null predicate when its filter is
 * absent, so Spring Data drops it instead of binding a null parameter — the old
 * {@code (:param is null or ...)} JPQL made PostgreSQL fail with "could not determine data type of
 * parameter" for untyped nulls (UUID / enum / timestamptz).
 */
public final class ScheduleSlotSpecifications {

    private ScheduleSlotSpecifications() {
    }

    public static Specification<ScheduleSlot> search(UUID instructorId, SlotStatus status, Instant fromDate, Instant toDate) {
        return Specification.where(hasInstructor(instructorId))
                .and(hasStatus(status))
                .and(startsAtOrAfter(fromDate))
                .and(startsAtOrBefore(toDate));
    }

    public static Specification<ScheduleSlot> hasInstructor(UUID instructorId) {
        return (root, query, cb) -> instructorId == null ? null : cb.equal(root.get("instructor").get("id"), instructorId);
    }

    public static Specification<ScheduleSlot> hasStatus(SlotStatus status) {
        return (root, query, cb) -> status == null ? null : cb.equal(root.get("status"), status);
    }

    public static Specification<ScheduleSlot> startsAtOrAfter(Instant fromDate) {
        return (root, query, cb) -> fromDate == null ? null : cb.greaterThanOrEqualTo(root.get("startTime"), fromDate);
    }

    public static Specification<ScheduleSlot> startsAtOrBefore(Instant toDate) {
        return (root, query, cb) -> toDate == null ? null : cb.lessThanOrEqualTo(root.get("startTime"), toDate);
    }
}
