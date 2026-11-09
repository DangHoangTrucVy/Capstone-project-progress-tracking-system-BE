package com.capstone.tracking;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.capstone.tracking.major.Major;
import com.capstone.tracking.major.MajorRepository;
import com.capstone.tracking.notification.email.EmailOutboxDispatcher;
import com.capstone.tracking.notification.email.EmailSender;
import com.capstone.tracking.roster.EnrollmentRepository;
import com.capstone.tracking.roster.RosterSync;
import com.capstone.tracking.roster.SemesterStudent;
import com.capstone.tracking.roster.SemesterStudentRepository;
import com.capstone.tracking.security.JwtTokenProvider;
import com.capstone.tracking.semester.FormationScope;
import com.capstone.tracking.semester.FormationScopeRepository;
import com.capstone.tracking.semester.PhaseService;
import com.capstone.tracking.semester.Semester;
import com.capstone.tracking.semester.SemesterRepository;
import com.capstone.tracking.team.GroupMemberRepository;
import com.capstone.tracking.team.JoinRequestRepository;
import com.capstone.tracking.team.StudentGroup;
import com.capstone.tracking.team.StudentGroupRepository;
import com.capstone.tracking.user.Role;
import com.capstone.tracking.user.User;
import com.capstone.tracking.user.UserRepository;
import com.capstone.tracking.user.UserStatus;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Base of the integration tests: HTTP helpers acting as a given user, and {@link #world} to build a semester in the
 * FORMING phase with a Moderator, two majors of students and the rooms the Moderator created for them (v3.0).
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
public abstract class TestSupport {

    private static final AtomicInteger COUNTER = new AtomicInteger();

    @Autowired protected MockMvc mockMvc;
    @Autowired protected ObjectMapper objectMapper;
    @Autowired protected UserRepository userRepository;
    @Autowired protected JwtTokenProvider jwtTokenProvider;
    @Autowired protected SemesterRepository semesterRepository;
    @Autowired protected MajorRepository majorRepository;
    @Autowired protected SemesterStudentRepository semesterStudentRepository;
    @Autowired protected EnrollmentRepository enrollmentRepository;
    @Autowired protected FormationScopeRepository scopeRepository;
    @Autowired protected StudentGroupRepository groupRepository;
    @Autowired protected GroupMemberRepository memberRepository;
    @Autowired protected JoinRequestRepository joinRequestRepository;
    @Autowired protected PhaseService phaseService;
    @Autowired protected RosterSync rosterSync;
    @Autowired protected TransactionTemplate tx;
    @Autowired protected EmailOutboxDispatcher emailOutboxDispatcher;
    @MockBean protected EmailSender emailSender;

    protected final String suffix = UUID.randomUUID().toString().substring(0, 8);

    // ------------------------------------------------------------------ users

    protected User user(String name, Role role) {
        return userRepository.save(User.builder().email(name + "-" + suffix + "@gmail.com").fullName(name + " " + suffix)
                .passwordHash("x").role(role).status(UserStatus.ACTIVE).build());
    }

    protected User student(String name) {
        User u = user(name, Role.STUDENT);
        u.setStudentCode(("S" + suffix + COUNTER.incrementAndGet()).toUpperCase());
        return userRepository.save(u);
    }

    // ------------------------------------------------------------------ the world

    /**
     * A semester in FORMING (deadline in 7 days) with its Moderator and two majors; the main major has
     * {@code ceil(students / 3)} rooms (at least 2), the other major at least one.
     */
    public record World(Semester semester, User admin, User moderator, Major major, Major otherMajor,
                        FormationScope scope, FormationScope otherScope, List<User> students, List<User> others,
                        List<UUID> rooms, List<UUID> otherRooms) {
        public String code() {
            return semester.getCode();
        }
    }

    protected World world(int students, int otherMajorStudents) throws Exception {
        World w = openingWorld(students, otherMajorStudents);
        postJson("/api/v1/semesters/" + w.code() + "/start-formation", w.moderator(), Map.of())
                .andExpect(status().isOk());
        return refresh(w);
    }

    /** The same world, still in OPENING (rooms created, formation not started). */
    protected World openingWorld(int students, int otherMajorStudents) throws Exception {
        User admin = user("admin", Role.ADMIN);
        User moderator = user("moderator", Role.LECTURER);
        Semester semester = new Semester(freeSemesterCode(), "Test semester", LocalDate.now().minusDays(30),
                LocalDate.now().plusDays(60));
        semester.setFormationOpensAt(Instant.now().minus(Duration.ofHours(1)));
        semester.setDeadline(Instant.now().plus(Duration.ofDays(7)));
        semester.setModeratorId(moderator.getId());
        semester.setRequestTtlMinutes(2880);
        semester = semesterRepository.save(semester);
        Major major = major();
        Major otherMajor = major();
        List<User> a = enrol(semester, major, students);
        List<User> b = enrol(semester, otherMajor, otherMajorStudents);
        FormationScope scope = scopeRepository.findBySemesterCodeAndMajorId(semester.getCode(), major.getId()).orElse(null);
        FormationScope other = scopeRepository.findBySemesterCodeAndMajorId(semester.getCode(), otherMajor.getId()).orElse(null);
        List<UUID> rooms = students == 0 ? List.of() : createRooms(semester.getCode(), moderator, major, Math.max(2, (students + 2) / 3));
        List<UUID> otherRooms = otherMajorStudents == 0 ? List.of()
                : createRooms(semester.getCode(), moderator, otherMajor, Math.max(1, (otherMajorStudents + 2) / 3));
        return new World(semester, admin, moderator, major, otherMajor, scope, other, a, b, rooms, otherRooms);
    }

    protected World refresh(World w) {
        return new World(semesterRepository.findById(w.code()).orElseThrow(), w.admin(), w.moderator(), w.major(),
                w.otherMajor(), w.scope(), w.otherScope(), w.students(), w.others(), w.rooms(), w.otherRooms());
    }

    protected List<UUID> createRooms(String semester, User moderator, Major major, int count) throws Exception {
        JsonNode created = body(postJson("/api/v1/semesters/" + semester + "/rooms", moderator,
                Map.of("majorId", major.getId(), "count", count)).andExpect(status().isCreated()));
        List<UUID> ids = new ArrayList<>();
        created.forEach(r -> ids.add(UUID.fromString(r.get("id").asText())));
        return ids;
    }

    protected Major major() {
        String code = ("M" + suffix + COUNTER.incrementAndGet()).toUpperCase();
        return majorRepository.save(Major.builder().code(code).name("Major " + code).build());
    }

    protected List<User> enrol(Semester semester, Major major, int count) {
        List<User> list = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            User s = student("st" + COUNTER.incrementAndGet());
            tx.executeWithoutResult(t -> rosterSync.syncStudent(semesterStudentRepository.save(SemesterStudent.builder()
                    .semesterCode(semester.getCode()).user(s).major(major).currentTerm(8).build())));
            list.add(s);
        }
        return list;
    }

    /** The deadline passes and the scheduler locks the rooms (7.1). */
    protected Semester passDeadline(World w) {
        Semester s = semesterRepository.findById(w.code()).orElseThrow();
        s.setDeadline(Instant.now().minusSeconds(1));
        semesterRepository.save(s);
        phaseService.tick(w.code());
        return semesterRepository.findById(w.code()).orElseThrow();
    }

    protected synchronized String freeSemesterCode() {
        for (String season : List.of("SP", "SU", "FA")) {
            for (int year = 0; year < 100; year++) {
                String code = season + String.format("%02d", year);
                if (!semesterRepository.existsById(code)) {
                    return code;
                }
            }
        }
        throw new IllegalStateException("No free semester code left");
    }

    // ------------------------------------------------------------------ rooms

    /** The student walks into the room (an empty room makes them its Leader). */
    protected JsonNode joinRoom(UUID roomId, User student) throws Exception {
        return body(postJson("/api/v1/rooms/" + roomId + "/join", student, Map.of()).andExpect(status().isOk()));
    }

    /** The Leader takes the next empty room of the world's major and renames it; returns the room id. */
    protected UUID createGroup(World w, User leader, String name) throws Exception {
        for (UUID room : w.rooms()) {
            if (memberRepository.countActive(room) == 0) {
                JsonNode joined = joinRoom(room, leader);
                if (!joined.get("leader").asBoolean()) {
                    throw new IllegalStateException("Expected to be the Leader of an empty room");
                }
                putJson("/api/v1/rooms/" + room, leader, Map.of("name", name)).andExpect(status().isOk());
                return room;
            }
        }
        throw new IllegalStateException("No empty room left in the world");
    }

    /** Adds the student to the Leader's room: walks in when it is open, else asks and has the Leader accept. */
    protected void join(UUID roomId, User leader, User student) throws Exception {
        JsonNode outcome = joinRoom(roomId, student);
        if (!outcome.get("joined").asBoolean()) {
            UUID request = UUID.fromString(outcome.get("request").get("id").asText());
            postJson("/api/v1/requests/" + request + "/accept", leader, Map.of()).andExpect(status().isOk());
        }
    }

    /** The Leader locks the room and the student asks to join; returns the request id. */
    protected UUID requestToJoin(UUID roomId, User leader, User student) throws Exception {
        StudentGroup room = groupRepository.findById(roomId).orElseThrow();
        if (!room.isLocked()) {
            postJson("/api/v1/rooms/" + roomId + "/lock", leader, Map.of()).andExpect(status().isOk());
        }
        JsonNode outcome = joinRoom(roomId, student);
        return UUID.fromString(outcome.get("request").get("id").asText());
    }

    // ------------------------------------------------------------------ HTTP

    protected String bearer(User user) {
        return "Bearer " + jwtTokenProvider.generateAccessToken(user.getId(), user.getEmail(), user.getRole().name());
    }

    protected ResultActions postJson(String url, User as, Object body) throws Exception {
        return mockMvc.perform(post(url).header("Authorization", bearer(as))
                .contentType(MediaType.APPLICATION_JSON).content(objectMapper.writeValueAsString(body)));
    }

    protected ResultActions putJson(String url, User as, Object body) throws Exception {
        return mockMvc.perform(put(url).header("Authorization", bearer(as))
                .contentType(MediaType.APPLICATION_JSON).content(objectMapper.writeValueAsString(body)));
    }

    protected ResultActions getAs(String url, User as) throws Exception {
        return mockMvc.perform(get(url).header("Authorization", bearer(as)));
    }

    protected ResultActions deleteAs(String url, User as) throws Exception {
        return mockMvc.perform(delete(url).header("Authorization", bearer(as)));
    }

    protected ResultActions upload(String url, User as, String fileName, String content) throws Exception {
        return mockMvc.perform(multipart(url)
                .file(new MockMultipartFile("file", fileName, "text/csv", content.getBytes(StandardCharsets.UTF_8)))
                .header("Authorization", bearer(as)));
    }

    protected JsonNode body(ResultActions result) throws Exception {
        // JSON responses carry no charset, so MockMvc would decode them as ISO-8859-1 and garble Vietnamese text.
        return objectMapper.readTree(result.andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8));
    }

    protected UUID id(ResultActions result) throws Exception {
        return UUID.fromString(body(result).get("id").asText());
    }

    /** Emails are queued in the outbox and sent by the dispatcher; tests run it now instead of waiting for the timer. */
    protected void dispatchEmails() {
        emailOutboxDispatcher.dispatchPending(Instant.now());
    }
}
