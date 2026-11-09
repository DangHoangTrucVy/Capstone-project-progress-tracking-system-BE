package com.capstone.tracking.report;

import com.capstone.tracking.roster.Enrollment;
import com.capstone.tracking.semester.SemesterPhase;
import com.capstone.tracking.team.RoomStatus;
import com.capstone.tracking.user.User;
import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.SortedMap;
import java.util.TreeMap;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Statistics by semester, filtered by major (7.7): students with and without a room (the ineligible counted apart),
 * rooms by size and by status, rooms below their minimum, empty rooms, groups (finalized rooms), unhandled complaints,
 * pending requests, invites and finalize requests. Same source as the Excel export.
 */
@Service
@RequiredArgsConstructor
public class StatisticsService {

    private final Snapshot snapshot;

    public record Counts(long totalStudents, long withGroup, long withoutGroup, long ineligible,
                         SortedMap<Integer, Long> roomsBySize, Map<RoomStatus, Long> roomsByStatus, long totalRooms,
                         long emptyRooms, long roomsBelowMinimum, long groups, long pendingFinalizeRequests,
                         long openComplaints, long pendingRequests, long pendingInvites) {}

    public record MajorStats(UUID majorId, String majorCode, String majorName, Counts counts) {}

    public record Statistics(String semesterCode, SemesterPhase phase, Instant deadline, UUID majorId, Instant takenAt,
                             Counts total, List<MajorStats> majors) {}

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public Statistics compute(String semesterCode, UUID majorId, User viewer) {
        Snapshot.Data data = snapshot.take(semesterCode, majorId, viewer);
        int max = data.semester().getMaxGroupSize();
        List<MajorStats> rows = new ArrayList<>();
        Counts total = empty(max);
        for (Snapshot.ScopeData s : data.scopes()) {
            Counts c = counts(s, max);
            rows.add(new MajorStats(s.scope().getMajor().getId(), s.scope().getMajor().getCode(),
                    s.scope().getMajor().getName(), c));
            total = add(total, c);
        }
        return new Statistics(semesterCode, data.semester().getPhase(), data.semester().getDeadline(), majorId,
                data.takenAt(), total, rows);
    }

    private static Counts counts(Snapshot.ScopeData s, int max) {
        long total = s.students().size();
        long withGroup = s.students().stream().filter(e -> s.membershipByEnrollment().containsKey(e.getId())).count();
        long ineligible = s.students().stream().filter(Enrollment::isIneligible).count();
        SortedMap<Integer, Long> bySize = sizes(max);
        Map<RoomStatus, Long> byStatus = statuses();
        long empty = 0;
        long small = 0;
        long groups = 0;
        long requested = 0;
        for (Snapshot.RoomData r : s.rooms()) {
            int size = r.size();
            bySize.merge(size, 1L, Long::sum);
            byStatus.merge(r.status(), 1L, Long::sum);
            if (size == 0) {
                empty++;
            }
            if (!r.room().isApproved() && size > 0 && size < r.room().getMinSize()) {
                small++;
            }
            if (r.room().isApproved()) {
                groups++;
            }
            if (r.room().isFinalizeRequested()) {
                requested++;
            }
        }
        return new Counts(total, withGroup, total - withGroup, ineligible, bySize, byStatus, s.rooms().size(), empty,
                small, groups, requested, s.openComplaints(), s.pendingRequests(), s.pendingInvites());
    }

    private static SortedMap<Integer, Long> sizes(int max) {
        SortedMap<Integer, Long> bySize = new TreeMap<>();
        for (int size = 0; size <= max; size++) {
            bySize.put(size, 0L);
        }
        return bySize;
    }

    private static Map<RoomStatus, Long> statuses() {
        Map<RoomStatus, Long> byStatus = new EnumMap<>(RoomStatus.class);
        for (RoomStatus status : RoomStatus.values()) {
            if (status != RoomStatus.CLOSED) {
                byStatus.put(status, 0L);
            }
        }
        return byStatus;
    }

    private static Counts empty(int max) {
        return new Counts(0, 0, 0, 0, sizes(max), statuses(), 0, 0, 0, 0, 0, 0, 0, 0);
    }

    private static Counts add(Counts a, Counts b) {
        SortedMap<Integer, Long> sizes = new TreeMap<>(a.roomsBySize());
        b.roomsBySize().forEach((size, n) -> sizes.merge(size, n, Long::sum));
        Map<RoomStatus, Long> statuses = new EnumMap<>(a.roomsByStatus());
        b.roomsByStatus().forEach((status, n) -> statuses.merge(status, n, Long::sum));
        return new Counts(a.totalStudents() + b.totalStudents(), a.withGroup() + b.withGroup(),
                a.withoutGroup() + b.withoutGroup(), a.ineligible() + b.ineligible(), sizes, statuses,
                a.totalRooms() + b.totalRooms(), a.emptyRooms() + b.emptyRooms(),
                a.roomsBelowMinimum() + b.roomsBelowMinimum(), a.groups() + b.groups(),
                a.pendingFinalizeRequests() + b.pendingFinalizeRequests(), a.openComplaints() + b.openComplaints(),
                a.pendingRequests() + b.pendingRequests(), a.pendingInvites() + b.pendingInvites());
    }
}
