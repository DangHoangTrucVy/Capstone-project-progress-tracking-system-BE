package com.capstone.tracking.overview;

import com.capstone.tracking.artifact.ArtifactSubmissionRepository;
import com.capstone.tracking.defense.DefenseService;
import com.capstone.tracking.defense.DefenseStatus;
import com.capstone.tracking.defense.dto.DefenseSessionResponse;
import com.capstone.tracking.group.GroupStatus;
import com.capstone.tracking.group.StudentGroup;
import com.capstone.tracking.group.StudentGroupService;
import com.capstone.tracking.milestone.Milestone;
import com.capstone.tracking.milestone.MilestoneService;
import com.capstone.tracking.notification.NotificationService;
import com.capstone.tracking.overview.GroupOverviewResponse.DefenseTrack;
import com.capstone.tracking.progress.WeeklyProgressReport;
import com.capstone.tracking.progress.WeeklyProgressReportRepository;
import com.capstone.tracking.proposal.TopicProposalService;
import com.capstone.tracking.proposal.dto.TopicProposalResponse;
import com.capstone.tracking.review.ReviewRound;
import com.capstone.tracking.review.ReviewService;
import com.capstone.tracking.review.dto.ReviewSessionResponse;
import com.capstone.tracking.scheduling.BookingRepository;
import com.capstone.tracking.user.User;
import com.capstone.tracking.warning.WarningFlagService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class GroupOverviewService {

    private final StudentGroupService studentGroupService;
    private final TopicProposalService proposalService;
    private final MilestoneService milestoneService;
    private final ArtifactSubmissionRepository artifactRepository;
    private final WeeklyProgressReportRepository progressRepository;
    private final WarningFlagService warningFlagService;
    private final BookingRepository bookingRepository;
    private final ReviewService reviewService;
    private final DefenseService defenseService;
    private final NotificationService notificationService;

    public GroupOverviewResponse overview(UUID groupId, User actingUser) {
        StudentGroup group = studentGroupService.getById(groupId);
        studentGroupService.requireCanView(groupId, actingUser);

        List<TopicProposalResponse> proposals = proposalService.listByGroup(groupId, actingUser);
        List<WeeklyProgressReport> weekly = progressRepository.findByGroupIdOrderByWeekNumberAsc(groupId);
        Instant now = Instant.now();
        List<GroupOverviewResponse.Meeting> meetings = bookingRepository.findConfirmedByGroup(groupId).stream()
                .filter(b -> b.getSlot().getEndTime().isAfter(now))
                .map(b -> new GroupOverviewResponse.Meeting(b.getId(), b.getSlot().getStartTime(), b.getSlot().getEndTime(),
                        b.getSlot().getInstructor().getFullName(), b.getSlot().getMeetingUrl()))
                .toList();
        List<ReviewSessionResponse> reviews = reviewService.listByGroup(groupId, actingUser);
        List<DefenseSessionResponse> defenses = defenseService.listByGroup(groupId, actingUser);

        return new GroupOverviewResponse(
                group.getId(),
                group.getGroupCode(),
                group.getSemester(),
                group.getStatus(),
                group.getTopic() != null ? group.getTopic().getTitle() : null,
                group.getSupervisor() != null ? group.getSupervisor().getFullName() : null,
                proposals.isEmpty() ? null : proposals.get(proposals.size() - 1),
                milestoneProgress(group, now),
                weekly.isEmpty() ? null : weekly.get(weekly.size() - 1).getProgressPercentage(),
                warningFlagService.listByGroup(groupId, true, actingUser),
                meetings,
                reviews,
                defenses,
                defenseTrack(group, reviews, defenses),
                notificationService.unreadCount(actingUser));
    }

    private GroupOverviewResponse.MilestoneProgress milestoneProgress(StudentGroup group, Instant now) {
        List<Milestone> milestones = milestoneService.list(group.getSemester());
        Set<UUID> submitted = new HashSet<>(artifactRepository.findSubmittedMilestoneIds(group.getId()));
        List<GroupOverviewResponse.MilestoneItem> items = milestones.stream()
                .map(m -> {
                    boolean done = submitted.contains(m.getId());
                    return new GroupOverviewResponse.MilestoneItem(m.getId(), m.getCode(), m.getName(), m.getDueDate(), done,
                            !done && m.getDueDate() != null && now.isAfter(m.getDueDate()));
                })
                .toList();
        int done = (int) items.stream().filter(GroupOverviewResponse.MilestoneItem::submitted).count();
        int percentage = items.isEmpty() ? 0 : Math.round(100f * done / items.size());
        return new GroupOverviewResponse.MilestoneProgress(items.size(), done, percentage, items);
    }

    private DefenseTrack defenseTrack(StudentGroup group, List<ReviewSessionResponse> reviews,
                                      List<DefenseSessionResponse> defenses) {
        if (group.getStatus() == GroupStatus.COMPLETED) {
            return DefenseTrack.COMPLETED;
        }
        if (group.getStatus() == GroupStatus.FAILED) {
            return DefenseTrack.FAILED;
        }
        if (defenses.stream().anyMatch(d -> d.attempt() == 1 && d.status() == DefenseStatus.FAILED)) {
            return DefenseTrack.DEFENSE_2;
        }
        ReviewSessionResponse council = reviews.stream()
                .filter(r -> r.round() == ReviewRound.REVIEW_3 && r.outcome() != null)
                .findFirst().orElse(null);
        if (council == null) {
            return DefenseTrack.NOT_REVIEWED;
        }
        return switch (council.outcome()) {
            case READY_FOR_DEFENSE_1 -> DefenseTrack.DEFENSE_1;
            case DEFER_TO_DEFENSE_2 -> DefenseTrack.DEFENSE_2;
            case REVISE_BEFORE_DEFENSE_1 -> {
                if (council.revisionCompletedAt() != null) {
                    yield DefenseTrack.DEFENSE_1;
                }
                boolean missed = council.revisionDeadline() != null && Instant.now().isAfter(council.revisionDeadline());
                yield missed ? DefenseTrack.DEFENSE_2 : DefenseTrack.REVISE_BEFORE_DEFENSE_1;
            }
        };
    }
}
