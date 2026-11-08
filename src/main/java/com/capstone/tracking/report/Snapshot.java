package com.capstone.tracking.report;

import com.capstone.tracking.roster.Enrollment;
import com.capstone.tracking.roster.EnrollmentRepository;
import com.capstone.tracking.semester.Access;
import com.capstone.tracking.semester.FormationScope;
import com.capstone.tracking.semester.FormationScopeRepository;
import com.capstone.tracking.semester.Semester;
import com.capstone.tracking.team.GroupCore;
import com.capstone.tracking.team.GroupMember;
import com.capstone.tracking.team.GroupMemberRepository;
import com.capstone.tracking.team.JoinRequest;
import com.capstone.tracking.team.JoinRequestRepository;
import com.capstone.tracking.team.LeaveRequestRepository;
import com.capstone.tracking.team.RoomStatus;
import com.capstone.tracking.team.StudentGroup;
import com.capstone.tracking.team.StudentGroupRepository;
import com.capstone.tracking.user.User;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * The data behind statistics and the Excel export, read once per call: the semester's majors (or one), their rooms
 * with members, and their students. Callers run it in one repeatable-read transaction, so the numbers and the file
 * agree at the moment they are produced (7.7, 12).
 */
@Component
@RequiredArgsConstructor
@Transactional(propagation = Propagation.MANDATORY)
public class Snapshot {

    private final Access access;
    private final FormationScopeRepository scopes;
    private final EnrollmentRepository enrollments;
    private final StudentGroupRepository groups;
    private final GroupMemberRepository members;
    private final JoinRequestRepository joinRequests;
    private final LeaveRequestRepository leaveRequests;
    private final GroupCore core;

    public record RoomData(StudentGroup room, List<GroupMember> members, RoomStatus status) {
        public int size() {
            return members.size();
        }
    }

    public record ScopeData(FormationScope scope, List<RoomData> rooms, List<Enrollment> students,
                            Map<UUID, GroupMember> membershipByEnrollment, long openComplaints, long pendingRequests,
                            long pendingInvites) {}

    public record Data(Semester semester, UUID majorId, Instant takenAt, List<ScopeData> scopes) {}

    public Data take(String semesterCode, UUID majorId, User viewer) {
        Semester semester = access.requireModerator(semesterCode, viewer);
        Instant now = Instant.now();
        List<ScopeData> data = scopes.findBySemester(semesterCode).stream()
                .filter(s -> majorId == null || s.getMajor().getId().equals(majorId))
                .map(scope -> scopeData(semester, scope, now)).toList();
        return new Data(semester, majorId, now, data);
    }

    private ScopeData scopeData(Semester semester, FormationScope scope, Instant now) {
        List<StudentGroup> rooms = groups.findActiveByScope(scope.getId());
        Map<UUID, List<GroupMember>> byRoom = rooms.isEmpty() ? Map.of()
                : members.findActiveIn(rooms.stream().map(StudentGroup::getId).toList()).stream()
                        .collect(Collectors.groupingBy(m -> m.getGroup().getId()));
        List<RoomData> roomData = rooms.stream().map(g -> {
            List<GroupMember> list = byRoom.getOrDefault(g.getId(), List.of());
            return new RoomData(g, list, core.status(g, list.size(), semester, now));
        }).toList();
        Map<UUID, GroupMember> membership = byRoom.values().stream().flatMap(List::stream)
                .collect(Collectors.toMap(m -> m.getEnrollment().getId(), m -> m));
        return new ScopeData(scope, roomData, enrollments.findByScopeId(scope.getId()), membership,
                leaveRequests.countOpenInScope(scope.getId()),
                joinRequests.countOpenInScope(scope.getId(), JoinRequest.Type.APPLY, now),
                joinRequests.countOpenInScope(scope.getId(), JoinRequest.Type.INVITE, now));
    }
}
