package com.capstone.tracking.report;

import com.capstone.tracking.roster.Enrollment;
import com.capstone.tracking.subject.ScopePhase;
import com.capstone.tracking.subject.ScopeRules;
import com.capstone.tracking.user.User;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Statistics by semester, subject and major (7.3): students with and without a group (the ineligible counted apart
 * and also among those without a group), groups by size and by state (a group both full and locked counts in both),
 * groups below the minimum, unhandled complaints and pending requests. Same source as the Excel export.
 */
@Service
@RequiredArgsConstructor
public class StatisticsService {

    private final Snapshot snapshot;
    private final ScopeRules rules;

    public record Counts(long totalStudents, long withGroup, long withoutGroup, long ineligible,
                         java.util.SortedMap<Integer, Long> groupsBySize, long totalGroups, long openGroups, long fullGroups, long lockedGroups,
                         long groupsUnderMinimum, long openComplaints, long pendingApplies, long pendingInvites) {}

    public record ScopeStats(UUID scopeId, String subjectCode, String majorCode, String majorName, ScopePhase phase,
                             Instant deadline, Instant finalizedAt, Counts counts) {}

    public record Statistics(String semesterCode, UUID subjectId, UUID majorId, Instant takenAt, Counts total,
                             List<ScopeStats> scopes) {}

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public Statistics compute(String semesterCode, UUID subjectId, UUID majorId, User viewer) {
        Snapshot.Data data = snapshot.take(semesterCode, subjectId, majorId, viewer, rules::deadline);
        List<ScopeStats> rows = new ArrayList<>();
        Counts total = empty();
        for (Snapshot.ScopeData s : data.scopes()) {
            Counts c = counts(s);
            rows.add(new ScopeStats(s.scope().getId(), s.scope().getSubject().getCode(), s.scope().getMajor().getCode(),
                    s.scope().getMajor().getName(), s.scope().getPhase(), s.deadline(), s.scope().getFinalizedAt(), c));
            total = add(total, c);
        }
        return new Statistics(semesterCode, subjectId, majorId, data.takenAt(), total, rows);
    }

    private static Counts counts(Snapshot.ScopeData s) {
        long total = s.students().size();
        long withGroup = s.students().stream().filter(e -> s.membershipByEnrollment().containsKey(e.getId())).count();
        long ineligible = s.students().stream().filter(Enrollment::isIneligible).count();
        java.util.SortedMap<Integer, Long> bySize = new java.util.TreeMap<>();
        for (int size = 1; size <= s.scope().getSubject().getMaxGroupSize(); size++) {
            bySize.put(size, 0L);
        }
        long open = 0;
        long full = 0;
        long locked = 0;
        long small = 0;
        int min = s.scope().getSubject().getMinGroupSize();
        for (Snapshot.GroupData g : s.groups()) {
            int size = g.members().size();
            bySize.merge(size, 1L, Long::sum);
            if (g.full()) {
                full++;
            }
            if (g.state().contains("Khóa")) {
                locked++;
            }
            if (g.state().equals("Mở")) {
                open++;
            }
            if (size < min) {
                small++;
            }
        }
        return new Counts(total, withGroup, total - withGroup, ineligible, bySize, s.groups().size(), open, full, locked,
                small, s.openComplaints(), s.pendingApplies(), s.pendingInvites());
    }

    private static Counts empty() {
        return new Counts(0, 0, 0, 0, new java.util.TreeMap<>(), 0, 0, 0, 0, 0, 0, 0, 0);
    }

    private static Counts add(Counts a, Counts b) {
        java.util.SortedMap<Integer, Long> sizes = new java.util.TreeMap<>(a.groupsBySize());
        b.groupsBySize().forEach((size, n) -> sizes.merge(size, n, Long::sum));
        return new Counts(a.totalStudents() + b.totalStudents(), a.withGroup() + b.withGroup(),
                a.withoutGroup() + b.withoutGroup(), a.ineligible() + b.ineligible(), sizes,
                a.totalGroups() + b.totalGroups(), a.openGroups() + b.openGroups(), a.fullGroups() + b.fullGroups(),
                a.lockedGroups() + b.lockedGroups(), a.groupsUnderMinimum() + b.groupsUnderMinimum(),
                a.openComplaints() + b.openComplaints(), a.pendingApplies() + b.pendingApplies(),
                a.pendingInvites() + b.pendingInvites());
    }
}
