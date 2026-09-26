package com.capstone.tracking.progress;

import com.capstone.tracking.audit.AuditAction;
import com.capstone.tracking.audit.AuditService;
import com.capstone.tracking.common.exception.BadRequestException;
import com.capstone.tracking.common.exception.ConflictException;
import com.capstone.tracking.common.exception.ResourceNotFoundException;
import com.capstone.tracking.group.GroupMember;
import com.capstone.tracking.group.GroupMemberRepository;
import com.capstone.tracking.group.MemberStatus;
import com.capstone.tracking.group.StudentGroup;
import com.capstone.tracking.group.StudentGroupService;
import com.capstone.tracking.progress.dto.ProgressFeedbackRequest;
import com.capstone.tracking.progress.dto.ProgressReportRequest;
import com.capstone.tracking.progress.dto.ProgressReportResponse;
import com.capstone.tracking.progress.dto.ProgressSummaryResponse;
import com.capstone.tracking.progress.dto.ProgressTaskRequest;
import com.capstone.tracking.user.Role;
import com.capstone.tracking.user.User;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Weekly progress reports for the group dashboard. Members write them; the group's supervisor (or an Admin)
 * gives feedback. Returns DTOs rather than entities because the responses read lazy tasks and user names,
 * which must happen while this transaction is still open (open-in-view is off).
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class ProgressService {

    private final WeeklyProgressReportRepository reportRepository;
    private final StudentGroupService studentGroupService;
    private final GroupMemberRepository groupMemberRepository;
    private final AuditService auditService;

    @Transactional
    public ProgressReportResponse create(UUID groupId, ProgressReportRequest request, User actingUser) {
        StudentGroup group = studentGroupService.getById(groupId);
        requireMembership(groupId, actingUser);
        if (reportRepository.existsByGroupIdAndWeekNumber(groupId, request.weekNumber())) {
            throw new ConflictException("Week " + request.weekNumber() + " is already reported for this group; "
                    + "update it with PUT /api/v1/progress/{id}");
        }

        WeeklyProgressReport report = WeeklyProgressReport.builder().group(group).submittedBy(actingUser).build();
        apply(report, request);
        report = reportRepository.save(report);

        auditService.record("WeeklyProgressReport", report.getId(), AuditAction.CREATE, actingUser,
                Map.of("groupId", groupId, "weekNumber", request.weekNumber()));
        return ProgressReportResponse.from(report);
    }

    @Transactional
    public ProgressReportResponse update(UUID id, ProgressReportRequest request, User actingUser) {
        WeeklyProgressReport report = find(id);
        UUID groupId = report.getGroup().getId();
        requireMembership(groupId, actingUser);
        if (report.getWeekNumber() != request.weekNumber()
                && reportRepository.existsByGroupIdAndWeekNumber(groupId, request.weekNumber())) {
            throw new ConflictException("Week " + request.weekNumber() + " is already reported for this group");
        }

        apply(report, request);
        report = reportRepository.saveAndFlush(report); // flush so updatedAt in the response is current

        auditService.record("WeeklyProgressReport", report.getId(), AuditAction.UPDATE, actingUser,
                Map.of("weekNumber", request.weekNumber(), "progressPercentage", request.progressPercentage()));
        return ProgressReportResponse.from(report);
    }

    /** Only the group's own supervisor, or an Admin, comments on a report. */
    @Transactional
    public ProgressReportResponse giveFeedback(UUID id, ProgressFeedbackRequest request, User actingUser) {
        WeeklyProgressReport report = find(id);
        User supervisor = report.getGroup().getSupervisor();
        if (actingUser.getRole() != Role.ADMIN
                && (supervisor == null || !supervisor.getId().equals(actingUser.getId()))) {
            throw new AccessDeniedException("Only the group's supervisor can give feedback on its progress");
        }
        report.setInstructorFeedback(request.feedback());
        report.setFeedbackBy(actingUser);
        report.setFeedbackAt(Instant.now());

        auditService.record("WeeklyProgressReport", report.getId(), AuditAction.UPDATE, actingUser, Map.of("feedback", true));
        return ProgressReportResponse.from(report);
    }

    public ProgressReportResponse getById(UUID id, User actingUser) {
        WeeklyProgressReport report = find(id);
        requireMembership(report.getGroup().getId(), actingUser);
        return ProgressReportResponse.from(report);
    }

    /** Oldest week first, ready to plot. */
    public List<ProgressReportResponse> listByGroup(UUID groupId, User actingUser) {
        studentGroupService.getById(groupId);
        requireMembership(groupId, actingUser);
        return reportRepository.findByGroupIdOrderByWeekNumberAsc(groupId).stream()
                .map(ProgressReportResponse::from)
                .toList();
    }

    public ProgressSummaryResponse summary(UUID groupId, User actingUser) {
        List<ProgressReportResponse> reports = listByGroup(groupId, actingUser);
        List<ProgressSummaryResponse.WeekPoint> trend = reports.stream()
                .map(r -> new ProgressSummaryResponse.WeekPoint(r.weekNumber(), r.progressPercentage()))
                .toList();
        if (reports.isEmpty()) {
            return new ProgressSummaryResponse(groupId, 0, null, null, null, null, trend);
        }
        ProgressReportResponse latest = reports.get(reports.size() - 1);
        return new ProgressSummaryResponse(groupId, reports.size(), latest.weekNumber(), latest.progressPercentage(),
                latest.totalTasks(), latest.doneTasks(), trend);
    }

    private void apply(WeeklyProgressReport report, ProgressReportRequest request) {
        Map<UUID, User> members = groupMemberRepository
                .findByGroupIdAndStatus(report.getGroup().getId(), MemberStatus.ACTIVE).stream()
                .map(GroupMember::getUser)
                .collect(Collectors.toMap(User::getId, Function.identity()));

        report.setWeekNumber(request.weekNumber());
        report.setProgressPercentage(request.progressPercentage());
        report.setSummary(request.summary());
        report.setBlockers(request.blockers());
        report.setNextWeekPlan(request.nextWeekPlan());

        // Full replace: orphanRemoval deletes the tasks that are no longer listed.
        report.getTasks().clear();
        List<ProgressTaskRequest> tasks = request.tasks() == null ? List.of() : request.tasks();
        for (int i = 0; i < tasks.size(); i++) {
            ProgressTaskRequest t = tasks.get(i);
            User assignee = null;
            if (t.assigneeId() != null) {
                assignee = members.get(t.assigneeId());
                if (assignee == null) {
                    throw new BadRequestException("Task '" + t.title() + "' is assigned to someone who is not an active member of this group");
                }
            }
            report.getTasks().add(ProgressTask.builder()
                    .report(report).title(t.title()).status(t.status()).assignee(assignee).sortOrder(i).build());
        }
    }

    private WeeklyProgressReport find(UUID id) {
        return reportRepository.findById(id).orElseThrow(() -> ResourceNotFoundException.of("WeeklyProgressReport", id));
    }

    /** Students and leaders only see and write their own group's progress; instructors and admins see all. */
    private void requireMembership(UUID groupId, User actingUser) {
        if ((actingUser.getRole() == Role.STUDENT || actingUser.getRole() == Role.GROUP_LEADER)
                && !groupMemberRepository.existsByGroupIdAndUserIdAndStatus(groupId, actingUser.getId(), MemberStatus.ACTIVE)) {
            throw new AccessDeniedException("You are not an active member of this group");
        }
    }
}
