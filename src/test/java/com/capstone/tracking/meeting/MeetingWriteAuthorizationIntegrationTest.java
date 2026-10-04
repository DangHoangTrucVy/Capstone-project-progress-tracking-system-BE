package com.capstone.tracking.meeting;

import com.capstone.tracking.WorkflowTestSupport;
import com.capstone.tracking.audit.AuditAction;
import com.capstone.tracking.audit.SystemAuditTrailRepository;
import com.capstone.tracking.evaluation.EvaluationRecordRepository;
import com.capstone.tracking.evaluation.EvaluationService;
import com.capstone.tracking.evaluation.dto.EvaluationCreateRequest;
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
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.domain.Pageable;
import org.springframework.http.MediaType;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.test.web.servlet.ResultActions;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Real request transactions: reload persisted state after rejected writes, without a test transaction. */
@SpringBootTest(properties = "spring.datasource.url=jdbc:h2:mem:meeting_write_auth;MODE=PostgreSQL;DB_CLOSE_DELAY=-1")
class MeetingWriteAuthorizationIntegrationTest extends WorkflowTestSupport {
    @Autowired private BookingRepository bookings;
    @Autowired private ScheduleSlotRepository slots;
    @Autowired private MeetingSessionRepository sessions;
    @Autowired private MeetingMinuteRepository minutes;
    @Autowired private RequirementLogRepository requirements;
    @Autowired private EvaluationRecordRepository evaluations;
    @Autowired private SystemAuditTrailRepository audits;
    @Autowired private MeetingSessionService sessionService;
    @Autowired private EvaluationService evaluationService;

    private User instructor;
    private User supervisor;
    private User leader;
    private StudentGroup group;
    private Booking booking;

    @BeforeEach
    void fixture() {
        instructor = user("slot-instructor", Role.INSTRUCTOR);
        supervisor = user("supervisor", Role.INSTRUCTOR);
        group = group("WRITE", supervisor, true);
        leader = user("leader", Role.GROUP_LEADER);
        join(group, leader, true);
        Instant start = Instant.now().minus(1, ChronoUnit.HOURS);
        ScheduleSlot slot = slots.save(ScheduleSlot.builder().instructor(instructor)
                .startTime(start).endTime(start.plus(30, ChronoUnit.MINUTES)).durationMinutes(30)
                .capacityGroups(1).bookedCount(1).status(SlotStatus.FULL).locationType(LocationType.ONLINE).build());
        booking = bookings.save(Booking.builder().group(group).slot(slot)
                .bookedAt(start.minus(2, ChronoUnit.DAYS)).bookingStatus(BookingStatus.CONFIRMED).build());
    }

    @ParameterizedTest
    @ValueSource(strings = {"otherInstructor", "supervisor", "otherLeader", "removedLeader", "nonLeader",
            "council", "student", "admin"})
    void outsidersCannotMutateMeetingsRequirementsOrMinutes(String actorName) throws Exception {
        User outsider = actor(actorName);
        long auditCount = audits.count();
        postJson("/api/v1/bookings/" + booking.getId() + "/meetings", outsider, Map.of())
                .andExpect(status().isForbidden());
        assertThat(sessions.findByBookingId(booking.getId())).isEmpty();

        MeetingSession session = session(SessionStatus.SCHEDULED);
        // Existing sessions must not turn an authorization denial into a state-dependent conflict.
        postJson("/api/v1/bookings/" + booking.getId() + "/meetings", outsider, Map.of())
                .andExpect(status().isForbidden());
        putJson(meetingUrl(session) + "/start", outsider, Map.of()).andExpect(status().isForbidden());
        MeetingSession afterStart = sessions.findById(session.getId()).orElseThrow();
        assertThat(afterStart.getSessionStatus()).isEqualTo(SessionStatus.SCHEDULED);
        assertThat(afterStart.getStartedAt()).isNull();

        session.setSessionStatus(SessionStatus.IN_PROGRESS);
        session.setStartedAt(booking.getSlot().getStartTime());
        sessions.save(session);
        putJson(meetingUrl(session) + "/end", outsider, Map.of("rawNotes", "unauthorized replacement"))
                .andExpect(status().isForbidden());
        MeetingSession afterEnd = sessions.findById(session.getId()).orElseThrow();
        assertThat(afterEnd.getSessionStatus()).isEqualTo(SessionStatus.IN_PROGRESS);
        assertThat(afterEnd.getEndedAt()).isNull();
        assertThat(afterEnd.getRawNotes()).isEqualTo("original notes");
        assertThat(bookings.findById(booking.getId()).orElseThrow().getBookingStatus()).isEqualTo(BookingStatus.CONFIRMED);

        long requirementCount = requirements.count();
        postJson(meetingUrl(session) + "/requirements", outsider, Map.of("title", "Injected", "priority", "HIGH"))
                .andExpect(status().isForbidden());
        assertThat(requirements.count()).isEqualTo(requirementCount);
        RequirementLog requirement = requirements.save(RequirementLog.builder().session(session).group(group)
                .title("Original requirement").priority(Priority.MEDIUM).status(RequirementStatus.OPEN).build());
        putJson("/api/v1/requirements/" + requirement.getId(), outsider,
                Map.of("status", "RESOLVED", "assignedTo", leader.getId())).andExpect(status().isForbidden());
        RequirementLog afterUpdate = requirements.findById(requirement.getId()).orElseThrow();
        assertThat(afterUpdate.getStatus()).isEqualTo(RequirementStatus.OPEN);
        assertThat(afterUpdate.getAssignedTo()).isNull();

        postJson(meetingUrl(session) + "/minutes/generate", outsider, Map.of("notes", "injected notes"))
                .andExpect(status().isForbidden());
        assertThat(minutes.findBySessionId(session.getId())).isEmpty();
        assertThat(sessions.findById(session.getId()).orElseThrow().getRawNotes()).isEqualTo("original notes");

        // Admin alone retains the existing minutes-approval exception, tested separately below.
        if (!actorName.equals("admin")) {
            MeetingMinute minute = minutes.save(MeetingMinute.builder().session(session)
                    .generatedContent("original draft").status(MinuteStatus.DRAFT).build());
            putJson(meetingUrl(session) + "/minutes/sign", outsider,
                    Map.of("decision", "APPROVE", "finalContent", "injected draft")).andExpect(status().isForbidden());
            MeetingMinute afterSubmit = minutes.findById(minute.getId()).orElseThrow();
            assertThat(afterSubmit.getStatus()).isEqualTo(MinuteStatus.DRAFT);
            assertThat(afterSubmit.getFinalContent()).isNull();
            assertThat(afterSubmit.getStudentSignedAt()).isNull();

            minute.setStatus(MinuteStatus.UNDER_REVIEW);
            minutes.save(minute);
            for (String decision : new String[]{"APPROVE", "REJECT"}) {
                putJson(meetingUrl(session) + "/minutes/sign", outsider, Map.of("decision", decision))
                        .andExpect(status().isForbidden());
                MeetingMinute afterSign = minutes.findById(minute.getId()).orElseThrow();
                assertThat(afterSign.getStatus()).isEqualTo(MinuteStatus.UNDER_REVIEW);
                assertThat(afterSign.getInstructorSignedAt()).isNull();
            }
        }
        assertThat(audits.count()).isEqualTo(auditCount);
    }

    @ParameterizedTest
    @ValueSource(strings = {"leader", "slotInstructor"})
    void activeLeaderAndBookedInstructorCanCompleteTheMeetingFlow(String actorName) throws Exception {
        User writer = actor(actorName);
        UUID sessionId = UUID.fromString(body(postJson("/api/v1/bookings/" + booking.getId() + "/meetings", writer, Map.of())
                .andExpect(status().isCreated())).get("id").asText());
        String url = "/api/v1/meetings/" + sessionId;
        putJson(url + "/start", writer, Map.of()).andExpect(status().isOk());
        UUID requirementId = UUID.fromString(body(postJson(url + "/requirements", writer,
                Map.of("title", "Review design", "priority", "HIGH")).andExpect(status().isCreated())).get("id").asText());
        putJson("/api/v1/requirements/" + requirementId, writer, Map.of("status", "RESOLVED"))
                .andExpect(status().isOk());
        putJson(url + "/end", writer, Map.of("rawNotes", "approved discussion")).andExpect(status().isOk());
        postJson(url + "/minutes/generate", writer, Map.of()).andExpect(status().isOk());
        putJson(url + "/minutes/sign", leader, Map.of("decision", "APPROVE", "finalContent", "leader submission"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("UNDER_REVIEW"));
        putJson(url + "/minutes/sign", instructor, Map.of("decision", "APPROVE"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("APPROVED"));
        assertThat(bookings.findById(booking.getId()).orElseThrow().getBookingStatus()).isEqualTo(BookingStatus.ATTENDED);
        assertThat(sessions.findById(sessionId).orElseThrow().getSessionStatus()).isEqualTo(SessionStatus.CONCLUDED);
        assertThat(requirements.findById(requirementId).orElseThrow().getStatus()).isEqualTo(RequirementStatus.RESOLVED);
        assertThat(minutes.findBySessionId(sessionId).orElseThrow().getFinalContent()).isEqualTo("leader submission");
    }

    @ParameterizedTest
    @CsvSource({"slotInstructor,APPROVE,APPROVED", "slotInstructor,REJECT,REJECTED",
            "admin,APPROVE,APPROVED", "admin,REJECT,REJECTED"})
    void onlyBookedInstructorOrAdminApprovesOrRejectsMinutesWithAudit(String actorName, String decision, MinuteStatus expected)
            throws Exception {
        User approver = actor(actorName);
        MeetingSession session = session(SessionStatus.CONCLUDED);
        MeetingMinute minute = minutes.save(MeetingMinute.builder().session(session)
                .generatedContent("draft").finalContent("submitted").status(MinuteStatus.UNDER_REVIEW).build());
        putJson(meetingUrl(session) + "/minutes/sign", approver, Map.of("decision", decision))
                .andExpect(status().isOk());
        MeetingMinute result = minutes.findById(minute.getId()).orElseThrow();
        assertThat(result.getStatus()).isEqualTo(expected);
        assertThat(result.getInstructorSignedAt()).isNotNull();
        assertThat(result.getFinalContent()).isEqualTo("submitted");
        var entries = audits.findByEntityNameAndEntityId("MeetingMinute", minute.getId(), Pageable.unpaged()).getContent();
        assertThat(entries).hasSize(1);
        assertThat(entries.get(0).getAction()).isEqualTo(AuditAction.valueOf(decision));
        assertThat(entries.get(0).getPerformedBy().getId()).isEqualTo(approver.getId());
    }

    @ParameterizedTest
    @ValueSource(strings = {"otherInstructor", "slotInstructor", "leader", "otherLeader", "admin", "council", "student"})
    void onlyGroupSupervisorCanPublishEvaluation(String actorName) throws Exception {
        User outsider = actor(actorName);
        long before = evaluations.count();
        long auditCount = audits.count();
        postJson("/api/v1/groups/" + group.getId() + "/evaluations", outsider, scores()).andExpect(status().isForbidden());
        assertThat(evaluations.count()).isEqualTo(before);
        assertThat(audits.count()).isEqualTo(auditCount);
    }

    @Test
    void supervisorCanPublishEvaluationWithAudit() throws Exception {
        UUID id = UUID.fromString(body(postJson("/api/v1/groups/" + group.getId() + "/evaluations", supervisor, scores())
                .andExpect(status().isCreated()).andExpect(jsonPath("$.status").value("PUBLISHED"))
                .andExpect(jsonPath("$.totalScore").value(86.0))).get("id").asText());
        assertThat(evaluations.findById(id)).isPresent();
        assertThat(audits.findByEntityNameAndEntityId("EvaluationRecord", id, Pageable.unpaged()).getContent()).hasSize(1);
    }

    @Test
    void groupWithoutSupervisorCannotBeEvaluatedByAnyInstructor() throws Exception {
        group.setSupervisor(null);
        studentGroupRepository.save(group);
        long before = evaluations.count();
        postJson("/api/v1/groups/" + group.getId() + "/evaluations", supervisor, scores()).andExpect(status().isForbidden());
        assertThat(evaluations.count()).isEqualTo(before);
    }

    @ParameterizedTest
    @ValueSource(strings = {"otherInstructor", "otherLeader", "admin", "council", "student"})
    void servicesRejectUnauthorizedWritersWithoutControllerChecks(String actorName) {
        User outsider = actor(actorName);
        long auditCount = audits.count();
        long evaluationCount = evaluations.count();
        assertThatThrownBy(() -> sessionService.create(booking.getId(), outsider)).isInstanceOf(AccessDeniedException.class);
        assertThatThrownBy(() -> evaluationService.create(group.getId(), new EvaluationCreateRequest(85, 90, 80, "feedback"), outsider))
                .isInstanceOf(AccessDeniedException.class);
        assertThat(sessions.findByBookingId(booking.getId())).isEmpty();
        assertThat(evaluations.count()).isEqualTo(evaluationCount);
        assertThat(audits.count()).isEqualTo(auditCount);
    }

    private User actor(String name) {
        return switch (name) {
            case "leader" -> leader;
            case "slotInstructor" -> instructor;
            case "supervisor" -> supervisor;
            case "otherInstructor" -> user(name, Role.INSTRUCTOR);
            case "otherLeader" -> {
                User other = user(name, Role.GROUP_LEADER);
                join(group("OTHER", supervisor, true), other, true);
                yield other;
            }
            case "removedLeader", "nonLeader" -> {
                User other = user(name, Role.GROUP_LEADER);
                join(group, other, name.equals("removedLeader"));
                if (name.equals("removedLeader")) {
                    var member = groupMemberRepository.findByGroupIdAndUserId(group.getId(), other.getId()).orElseThrow();
                    member.setStatus(MemberStatus.REMOVED);
                    groupMemberRepository.save(member);
                }
                yield other;
            }
            case "admin" -> user(name, Role.ADMIN);
            case "council" -> user(name, Role.COUNCIL);
            case "student" -> user(name, Role.STUDENT);
            default -> throw new IllegalArgumentException(name);
        };
    }

    private MeetingSession session(SessionStatus status) {
        return sessions.save(MeetingSession.builder().booking(booking).sessionStatus(status).rawNotes("original notes").build());
    }

    private String meetingUrl(MeetingSession session) {
        return "/api/v1/meetings/" + session.getId();
    }

    private Map<String, Object> scores() {
        return Map.of("topicFitScore", 85, "productQualityScore", 90, "communicationScore", 80, "feedback", "feedback");
    }

    private ResultActions putJson(String url, User as, Object payload) throws Exception {
        return mockMvc.perform(put(url).header("Authorization", bearer(as)).contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(payload)));
    }
}
