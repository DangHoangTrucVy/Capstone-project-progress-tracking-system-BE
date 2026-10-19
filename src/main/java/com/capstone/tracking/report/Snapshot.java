package com.capstone.tracking.report;

import com.capstone.tracking.common.exception.ResourceNotFoundException;
import com.capstone.tracking.roster.Enrollment;
import com.capstone.tracking.roster.EnrollmentRepository;
import com.capstone.tracking.semester.Access;
import com.capstone.tracking.semester.Semester;
import com.capstone.tracking.subject.FormationScope;
import com.capstone.tracking.subject.FormationScopeRepository;
import com.capstone.tracking.subject.Subject;
import com.capstone.tracking.subject.SubjectRepository;
import com.capstone.tracking.team.GroupCore;
import com.capstone.tracking.team.GroupMember;
import com.capstone.tracking.team.GroupMemberRepository;
import com.capstone.tracking.team.JoinRequest;
import com.capstone.tracking.team.JoinRequestRepository;
import com.capstone.tracking.team.LeaveRequestRepository;
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
 * The data behind statistics and the Excel export, read once per call: the scopes selected (one major, one subject or
 * the whole semester), their groups with members, and their students. Callers run it in one repeatable-read
 * transaction, so the numbers and the file agree at the moment they are produced (7.3, 12).
 */
@Component
@RequiredArgsConstructor
@Transactional(propagation = Propagation.MANDATORY)
public class Snapshot {

    private final Access access;
    private final SubjectRepository subjects;
    private final FormationScopeRepository scopes;
    private final EnrollmentRepository enrollments;
    private final StudentGroupRepository groups;
    private final GroupMemberRepository members;
    private final JoinRequestRepository joinRequests;
    private final LeaveRequestRepository leaveRequests;
    private final GroupCore core;

    public record GroupData(StudentGroup group, List<GroupMember> members, boolean full, String state) {}

    public record ScopeData(FormationScope scope, Instant deadline, List<GroupData> groups, List<Enrollment> students,
                            Map<UUID, GroupMember> membershipByEnrollment, long openComplaints, long pendingApplies,
                            long pendingInvites) {}

    public record Data(Semester semester, Subject subject, UUID majorId, Instant takenAt, List<ScopeData> scopes) {}

    public Data take(String semesterCode, UUID subjectId, UUID majorId, User viewer, java.util.function.Function<FormationScope, Instant> deadline) {
        Semester semester = access.requireModerator(semesterCode, viewer);
        Subject subject = null;
        List<FormationScope> selected;
        if (subjectId != null) {
            subject = subjects.findById(subjectId).orElseThrow(() -> ResourceNotFoundException.of("Subject", subjectId));
            if (!subject.getSemesterCode().equals(semesterCode)) {
                throw new ResourceNotFoundException("Subject " + subjectId + " is not in semester " + semesterCode);
            }
            selected = scopes.findBySubjectIdOrderByMajor_CodeAsc(subjectId);
        } else {
            selected = scopes.findBySemester(semesterCode);
        }
        selected = selected.stream().filter(s -> majorId == null || s.getMajor().getId().equals(majorId)).toList();
        Instant now = Instant.now();
        List<ScopeData> data = selected.stream().map(scope -> scopeData(scope, deadline.apply(scope), now)).toList();
        return new Data(semester, subject, majorId, now, data);
    }

    private ScopeData scopeData(FormationScope scope, Instant deadline, Instant now) {
        List<StudentGroup> active = groups.findActiveByScope(scope.getId());
        Map<UUID, List<GroupMember>> byGroup = active.isEmpty() ? Map.of()
                : members.findActiveIn(active.stream().map(StudentGroup::getId).toList()).stream()
                        .collect(Collectors.groupingBy(m -> m.getGroup().getId()));
        int max = scope.getSubject().getMaxGroupSize();
        List<GroupData> groupData = active.stream().map(g -> {
            List<GroupMember> list = byGroup.getOrDefault(g.getId(), List.of());
            String state = stateLabel(g, list.size(), max, now);
            return new GroupData(g, list, list.size() >= max, state);
        }).toList();
        Map<UUID, GroupMember> membership = byGroup.values().stream().flatMap(List::stream)
                .collect(Collectors.toMap(m -> m.getEnrollment().getId(), m -> m));
        return new ScopeData(scope, deadline, groupData, enrollments.findByScopeId(scope.getId()), membership,
                leaveRequests.countOpenInScope(scope.getId()),
                joinRequests.countOpenInScope(scope.getId(), JoinRequest.Type.APPLY, now),
                joinRequests.countOpenInScope(scope.getId(), JoinRequest.Type.INVITE, now));
    }

    /** "Mở", "Đủ", "Khóa", or "Đủ, Khóa": a full group keeps its lock information (6.3, 10). */
    private String stateLabel(StudentGroup g, int size, int max, Instant now) {
        boolean full = size >= max;
        boolean locked = core.state(g, size, now) == com.capstone.tracking.team.GroupState.LOCKED;
        if (full && locked) {
            return "Đủ, Khóa";
        }
        return full ? "Đủ" : locked ? "Khóa" : "Mở";
    }
}
