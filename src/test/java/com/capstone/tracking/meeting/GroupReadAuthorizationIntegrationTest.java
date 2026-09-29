package com.capstone.tracking.meeting;

import com.capstone.tracking.WorkflowTestSupport;
import com.capstone.tracking.evaluation.EvaluationRecord;
import com.capstone.tracking.evaluation.EvaluationRecordRepository;
import com.capstone.tracking.evaluation.EvaluationService;
import com.capstone.tracking.evaluation.EvaluationStatus;
import com.capstone.tracking.group.GroupMember;
import com.capstone.tracking.group.MemberStatus;
import com.capstone.tracking.group.StudentGroup;
import com.capstone.tracking.scheduling.Booking;
import com.capstone.tracking.scheduling.BookingRepository;
import com.capstone.tracking.scheduling.BookingStatus;
import com.capstone.tracking.scheduling.LocationType;
import com.capstone.tracking.scheduling.ScheduleSlot;
import com.capstone.tracking.scheduling.ScheduleSlotRepository;
import com.capstone.tracking.scheduling.SlotStatus;
import com.capstone.tracking.user.Role;
import com.capstone.tracking.user.User;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.domain.Pageable;
import org.springframework.security.access.AccessDeniedException;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = "spring.datasource.url=jdbc:h2:mem:group_read_auth;MODE=PostgreSQL;DB_CLOSE_DELAY=-1")
class GroupReadAuthorizationIntegrationTest extends WorkflowTestSupport {

    @Autowired private BookingRepository bookings;
    @Autowired private ScheduleSlotRepository slots;
    @Autowired private MeetingSessionRepository sessions;
    @Autowired private MeetingMinuteRepository minutes;
    @Autowired private RequirementLogRepository requirements;
    @Autowired private EvaluationRecordRepository evaluations;
    @Autowired private MeetingSessionService sessionService;
    @Autowired private MeetingMinuteService minuteService;
    @Autowired private RequirementLogService requirementService;
    @Autowired private EvaluationService evaluationService;

    private User slotInstructor;
    private User supervisorA;
    private User outsiderInstructor;
    private User leaderA;
    private User studentA;
    private User removedMemberA;
    private User leaderB;
    private User supervisorB;
    private User council;
    private User admin;

    private StudentGroup groupA;
    private StudentGroup groupB;
    private MeetingSession sessionA;
    private MeetingMinute minuteA;
    private RequirementLog requirementA;
    private EvaluationRecord publishedEvalA;
    private EvaluationRecord draftEvalA;

    @BeforeEach
    void setUp() {
        slotInstructor = user("slot-gv", Role.INSTRUCTOR);
        supervisorA = user("sup-a", Role.INSTRUCTOR);
        supervisorB = user("sup-b", Role.INSTRUCTOR);
        outsiderInstructor = user("outsider-gv", Role.INSTRUCTOR);
        leaderA = user("leader-a", Role.GROUP_LEADER);
        studentA = user("student-a", Role.STUDENT);
        removedMemberA = user("removed-a", Role.GROUP_LEADER);
        leaderB = user("leader-b", Role.GROUP_LEADER);
        council = user("council-user", Role.COUNCIL);
        admin = user("admin-user", Role.ADMIN);

        groupA = group("GRP-A", supervisorA);
        groupB = group("GRP-B", supervisorB);

        addMember(groupA, leaderA, true, MemberStatus.ACTIVE);
        addMember(groupA, studentA, false, MemberStatus.ACTIVE);
        addMember(groupA, removedMemberA, false, MemberStatus.REMOVED);
        addMember(groupB, leaderB, true, MemberStatus.ACTIVE);

        ScheduleSlot slot = slots.save(ScheduleSlot.builder()
                .instructor(slotInstructor)
                .startTime(Instant.now().plus(1, ChronoUnit.DAYS))
                .endTime(Instant.now().plus(1, ChronoUnit.DAYS).plus(30, ChronoUnit.MINUTES))
                .durationMinutes(30)
                .capacityGroups(1)
                .bookedCount(1)
                .locationType(LocationType.ONLINE)
                .meetingUrl("https://meet.example.com/" + suffix)
                .status(SlotStatus.FULL)
                .build());

        Booking bookingA = bookings.save(Booking.builder()
                .slot(slot)
                .group(groupA)
                .bookedAt(Instant.now())
                .bookingStatus(BookingStatus.CONFIRMED)
                .build());

        sessionA = sessions.save(MeetingSession.builder()
                .booking(bookingA)
                .sessionStatus(SessionStatus.CONCLUDED)
                .rawNotes("Confidential group A raw notes")
                .startedAt(Instant.now().minus(1, ChronoUnit.HOURS))
                .endedAt(Instant.now().minus(30, ChronoUnit.MINUTES))
                .build());

        minuteA = minutes.save(MeetingMinute.builder()
                .session(sessionA)
                .status(MinuteStatus.APPROVED)
                .generatedContent("Secret minutes draft A")
                .finalContent("Secret final minutes of Group A")
                .studentSignedAt(Instant.now().minus(20, ChronoUnit.MINUTES))
                .instructorSignedAt(Instant.now().minus(10, ChronoUnit.MINUTES))
                .build());

        requirementA = requirements.save(RequirementLog.builder()
                .session(sessionA)
                .group(groupA)
                .title("Group A confidential requirement")
                .description("Confidential requirement description")
                .priority(Priority.HIGH)
                .status(RequirementStatus.OPEN)
                .build());

        publishedEvalA = evaluations.save(EvaluationRecord.builder()
                .group(groupA)
                .instructor(supervisorA)
                .topicFitScore(85)
                .productQualityScore(90)
                .communicationScore(80)
                .totalScore(86.0)
                .feedbackNotes("Group A published evaluation notes")
                .evaluatedAt(Instant.now())
                .status(EvaluationStatus.PUBLISHED)
                .build());

        draftEvalA = evaluations.save(EvaluationRecord.builder()
                .group(groupA)
                .instructor(supervisorA)
                .topicFitScore(70)
                .productQualityScore(70)
                .communicationScore(70)
                .totalScore(70.0)
                .feedbackNotes("Group A confidential draft evaluation")
                .evaluatedAt(Instant.now())
                .status(EvaluationStatus.DRAFT)
                .build());
    }

    // =========================================================================
    // 1. GET /api/v1/meetings/{id}
    // =========================================================================

    @Test
    @DisplayName("Meeting: Authorized users (leader, slot instructor, supervisor, admin) can read meeting")
    void meeting_authorizedUsersCanRead() throws Exception {
        for (User user : new User[]{leaderA, slotInstructor, supervisorA, admin}) {
            mockMvc.perform(get("/api/v1/meetings/" + sessionA.getId())
                            .header("Authorization", "Bearer " + token(user)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.id").value(sessionA.getId().toString()))
                    .andExpect(jsonPath("$.rawNotes").value("Confidential group A raw notes"));
        }
    }

    @Test
    @DisplayName("Meeting: Unauthorized users (leaderB, removedMemberA, outsiderInstructor, council) get 403")
    void meeting_unauthorizedUsersGetForbidden() throws Exception {
        for (User user : new User[]{leaderB, removedMemberA, outsiderInstructor, council, studentA}) {
            mockMvc.perform(get("/api/v1/meetings/" + sessionA.getId())
                            .header("Authorization", "Bearer " + token(user)))
                    .andExpect(status().isForbidden());
        }
    }

    @Test
    @DisplayName("Meeting: Non-existent meeting returns 404")
    void meeting_notFound() throws Exception {
        mockMvc.perform(get("/api/v1/meetings/" + UUID.randomUUID())
                        .header("Authorization", "Bearer " + token(leaderA)))
                .andExpect(status().isNotFound());
    }

    // =========================================================================
    // 2. GET /api/v1/meetings/{id}/minutes
    // =========================================================================

    @Test
    @DisplayName("Minutes: Authorized users can read minutes")
    void minutes_authorizedUsersCanRead() throws Exception {
        for (User user : new User[]{leaderA, slotInstructor, supervisorA, admin}) {
            mockMvc.perform(get("/api/v1/meetings/" + sessionA.getId() + "/minutes")
                            .header("Authorization", "Bearer " + token(user)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.id").value(minuteA.getId().toString()))
                    .andExpect(jsonPath("$.finalContent").value("Secret final minutes of Group A"));
        }
    }

    @Test
    @DisplayName("Minutes: Unauthorized users get 403 without seeing content")
    void minutes_unauthorizedUsersGetForbidden() throws Exception {
        for (User user : new User[]{leaderB, removedMemberA, outsiderInstructor, council, studentA}) {
            mockMvc.perform(get("/api/v1/meetings/" + sessionA.getId() + "/minutes")
                            .header("Authorization", "Bearer " + token(user)))
                    .andExpect(status().isForbidden());
        }
    }

    @Test
    @DisplayName("Minutes: Non-existent session returns 404")
    void minutes_sessionNotFound() throws Exception {
        mockMvc.perform(get("/api/v1/meetings/" + UUID.randomUUID() + "/minutes")
                        .header("Authorization", "Bearer " + token(leaderA)))
                .andExpect(status().isNotFound());
    }

    // =========================================================================
    // 3. GET /api/v1/meetings/{id}/requirements
    // =========================================================================

    @Test
    @DisplayName("Requirements: Authorized users can list requirements")
    void requirements_authorizedUsersCanList() throws Exception {
        for (User user : new User[]{leaderA, slotInstructor, supervisorA, admin}) {
            mockMvc.perform(get("/api/v1/meetings/" + sessionA.getId() + "/requirements")
                            .header("Authorization", "Bearer " + token(user)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.content.length()").value(1))
                    .andExpect(jsonPath("$.content[0].title").value("Group A confidential requirement"));
        }
    }

    @Test
    @DisplayName("Requirements: Unauthorized users get 403 even for empty requirement lists")
    void requirements_unauthorizedUsersGetForbidden() throws Exception {
        for (User user : new User[]{leaderB, removedMemberA, outsiderInstructor, council, studentA}) {
            mockMvc.perform(get("/api/v1/meetings/" + sessionA.getId() + "/requirements")
                            .header("Authorization", "Bearer " + token(user)))
                    .andExpect(status().isForbidden());
        }
    }

    @Test
    @DisplayName("Requirements: Non-existent session returns 404")
    void requirements_sessionNotFound() throws Exception {
        mockMvc.perform(get("/api/v1/meetings/" + UUID.randomUUID() + "/requirements")
                        .header("Authorization", "Bearer " + token(leaderA)))
                .andExpect(status().isNotFound());
    }

    // =========================================================================
    // 4. GET /api/v1/evaluations/{id}
    // =========================================================================

    @Test
    @DisplayName("Evaluation by ID: Published record readable by active group leader, supervisor, and admin")
    void evaluationById_publishedRecord() throws Exception {
        for (User user : new User[]{leaderA, supervisorA, admin}) {
            mockMvc.perform(get("/api/v1/evaluations/" + publishedEvalA.getId())
                            .header("Authorization", "Bearer " + token(user)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.id").value(publishedEvalA.getId().toString()))
                    .andExpect(jsonPath("$.totalScore").value(86.0));
        }
    }

    @Test
    @DisplayName("Evaluation by ID: Outsiders (leaderB, removed member, slot instructor, outsider, council) get 403")
    void evaluationById_unauthorizedGetForbidden() throws Exception {
        for (User user : new User[]{leaderB, removedMemberA, slotInstructor, outsiderInstructor, council, studentA}) {
            mockMvc.perform(get("/api/v1/evaluations/" + publishedEvalA.getId())
                            .header("Authorization", "Bearer " + token(user)))
                    .andExpect(status().isForbidden());
        }
    }

    @Test
    @DisplayName("Evaluation by ID: Draft record visible to supervisor & admin; returns 404 to active leader to hide existence")
    void evaluationById_draftVisibility() throws Exception {
        // Supervisor & Admin see draft
        mockMvc.perform(get("/api/v1/evaluations/" + draftEvalA.getId())
                        .header("Authorization", "Bearer " + token(supervisorA)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("DRAFT"));

        mockMvc.perform(get("/api/v1/evaluations/" + draftEvalA.getId())
                        .header("Authorization", "Bearer " + token(admin)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("DRAFT"));

        // Active leader gets 404 (hiding unpublished record)
        mockMvc.perform(get("/api/v1/evaluations/" + draftEvalA.getId())
                        .header("Authorization", "Bearer " + token(leaderA)))
                .andExpect(status().isNotFound());

        // Outsider gets 403
        mockMvc.perform(get("/api/v1/evaluations/" + draftEvalA.getId())
                        .header("Authorization", "Bearer " + token(leaderB)))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("Evaluation by ID: Non-existent record returns 404")
    void evaluationById_notFound() throws Exception {
        mockMvc.perform(get("/api/v1/evaluations/" + UUID.randomUUID())
                        .header("Authorization", "Bearer " + token(leaderA)))
                .andExpect(status().isNotFound());
    }

    // =========================================================================
    // 5. GET /api/v1/groups/{groupId}/evaluations
    // =========================================================================

    @Test
    @DisplayName("Group evaluations: Active group leader sees only PUBLISHED records")
    void groupEvaluations_activeMembersSeeOnlyPublished() throws Exception {
        mockMvc.perform(get("/api/v1/groups/" + groupA.getId() + "/evaluations")
                        .header("Authorization", "Bearer " + token(leaderA)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content.length()").value(1))
                .andExpect(jsonPath("$.content[0].id").value(publishedEvalA.getId().toString()))
                .andExpect(jsonPath("$.content[0].status").value("PUBLISHED"));
    }

    @Test
    @DisplayName("Group evaluations: Supervisor & Admin see both DRAFT and PUBLISHED records")
    void groupEvaluations_privilegedSeeAll() throws Exception {
        for (User user : new User[]{supervisorA, admin}) {
            mockMvc.perform(get("/api/v1/groups/" + groupA.getId() + "/evaluations")
                            .header("Authorization", "Bearer " + token(user)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.content.length()").value(2));
        }
    }

    @Test
    @DisplayName("Group evaluations: Outsiders (leaderB, removed member, outsider, slot instructor, council) get 403")
    void groupEvaluations_unauthorizedGetForbidden() throws Exception {
        for (User user : new User[]{leaderB, removedMemberA, outsiderInstructor, slotInstructor, council, studentA}) {
            mockMvc.perform(get("/api/v1/groups/" + groupA.getId() + "/evaluations")
                            .header("Authorization", "Bearer " + token(user)))
                    .andExpect(status().isForbidden());
        }
    }

    @Test
    @DisplayName("Group evaluations: Non-existent group returns 404")
    void groupEvaluations_notFound() throws Exception {
        mockMvc.perform(get("/api/v1/groups/" + UUID.randomUUID() + "/evaluations")
                        .header("Authorization", "Bearer " + token(leaderA)))
                .andExpect(status().isNotFound());
    }

    // =========================================================================
    // 6. Direct Service Layer Security Checks (Defense-in-depth)
    // =========================================================================

    @Test
    @DisplayName("Service layer direct calls enforce authorization for caller")
    void serviceLayerDirectCalls() {
        // Active student member of group A can read via service
        assertThat(sessionService.getById(sessionA.getId(), studentA)).isNotNull();
        assertThat(minuteService.getBySession(sessionA.getId(), studentA)).isNotNull();
        assertThat(requirementService.listBySession(sessionA.getId(), studentA, Pageable.unpaged())).isNotEmpty();
        assertThat(evaluationService.getById(publishedEvalA.getId(), studentA)).isNotNull();
        assertThat(evaluationService.listByGroup(groupA.getId(), studentA, Pageable.unpaged())).isNotEmpty();

        // Outsider leaderB is blocked with AccessDeniedException
        assertThatThrownBy(() -> sessionService.getById(sessionA.getId(), leaderB))
                .isInstanceOf(AccessDeniedException.class);

        assertThatThrownBy(() -> minuteService.getBySession(sessionA.getId(), leaderB))
                .isInstanceOf(AccessDeniedException.class);

        assertThatThrownBy(() -> requirementService.listBySession(sessionA.getId(), leaderB, Pageable.unpaged()))
                .isInstanceOf(AccessDeniedException.class);

        assertThatThrownBy(() -> evaluationService.getById(publishedEvalA.getId(), leaderB))
                .isInstanceOf(AccessDeniedException.class);

        assertThatThrownBy(() -> evaluationService.listByGroup(groupA.getId(), leaderB, Pageable.unpaged()))
                .isInstanceOf(AccessDeniedException.class);

        // Removed member of group A is blocked with AccessDeniedException
        assertThatThrownBy(() -> sessionService.getById(sessionA.getId(), removedMemberA))
                .isInstanceOf(AccessDeniedException.class);

        assertThatThrownBy(() -> evaluationService.listByGroup(groupA.getId(), removedMemberA, Pageable.unpaged()))
                .isInstanceOf(AccessDeniedException.class);
    }

    // --- Helpers -------------------------------------------------------------

    private void addMember(StudentGroup grp, User u, boolean isLeader, MemberStatus status) {
        groupMemberRepository.save(GroupMember.builder()
                .group(grp)
                .user(u)
                .isLeader(isLeader)
                .joinedAt(Instant.now())
                .status(status)
                .build());
    }

    private StudentGroup group(String code, User supervisor) {
        return studentGroupRepository.save(StudentGroup.builder()
                .groupCode(code + "-" + suffix)
                .supervisor(supervisor)
                .semester("Fall2026")
                .status(com.capstone.tracking.group.GroupStatus.ACTIVE)
                .build());
    }

    private String token(User user) {
        return jwtTokenProvider.generateAccessToken(user.getId(), user.getEmail(), user.getRole().name());
    }
}
