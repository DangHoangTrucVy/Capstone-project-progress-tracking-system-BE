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
import com.capstone.tracking.roster.SemesterStudent;
import com.capstone.tracking.roster.SemesterStudentRepository;
import com.capstone.tracking.security.JwtTokenProvider;
import com.capstone.tracking.semester.Semester;
import com.capstone.tracking.semester.SemesterRepository;
import com.capstone.tracking.subject.FormationScope;
import com.capstone.tracking.subject.FormationScopeRepository;
import com.capstone.tracking.subject.ScopeService;
import com.capstone.tracking.team.GroupMemberRepository;
import com.capstone.tracking.team.JoinRequestRepository;
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

/**
 * Base of the integration tests: HTTP helpers acting as a given user, and {@link #world} to build a semester with a
 * Moderator, a subject and two majors of students whose scopes are open.
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
    @Autowired protected ScopeService scopeService;
    @Autowired protected com.capstone.tracking.subject.SubjectService subjectService;
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

    /** A semester with its Moderator, the subject CAP (term 8) and two majors; both scopes are open. */
    public record World(Semester semester, User admin, User moderator, Major major, Major otherMajor, UUID subjectId,
                        FormationScope scope, FormationScope otherScope, List<User> students, List<User> others) {
        public String code() {
            return semester.getCode();
        }
    }

    protected World world(int students, int otherMajorStudents) throws Exception {
        User admin = user("admin", Role.ADMIN);
        User moderator = user("moderator", Role.LECTURER);
        Semester semester = new Semester(freeSemesterCode(), "Test semester", LocalDate.now().minusDays(30),
                LocalDate.now().plusDays(60));
        semester.setFormationOpensAt(Instant.now().minus(Duration.ofHours(1)));
        semester.setDefaultDeadline(Instant.now().plus(Duration.ofDays(7)));
        semester.setModeratorId(moderator.getId());
        semester = semesterRepository.save(semester);
        Major major = major();
        Major otherMajor = major();
        List<User> a = enrol(semester, major, students);
        List<User> b = enrol(semester, otherMajor, otherMajorStudents);
        UUID subjectId = subjectService.create(semester.getCode(),
                new com.capstone.tracking.subject.SubjectService.SubjectRequest("CAP", "Capstone Project", 8, null),
                moderator).getId();
        FormationScope scope = scopeRepository.findBySubjectIdAndMajorId(subjectId, major.getId()).orElseThrow();
        FormationScope other = scopeRepository.findBySubjectIdAndMajorId(subjectId, otherMajor.getId()).orElse(null);
        scopeService.tick(scope.getId());
        if (other != null) {
            scopeService.tick(other.getId());
        }
        return new World(semester, admin, moderator, major, otherMajor, subjectId,
                scopeRepository.findById(scope.getId()).orElseThrow(),
                other == null ? null : scopeRepository.findById(other.getId()).orElseThrow(), a, b);
    }

    protected Major major() {
        String code = ("M" + suffix + COUNTER.incrementAndGet()).toUpperCase();
        return majorRepository.save(Major.builder().code(code).name("Major " + code).build());
    }

    protected List<User> enrol(Semester semester, Major major, int count) {
        List<User> list = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            User s = student("st" + COUNTER.incrementAndGet());
            semesterStudentRepository.save(SemesterStudent.builder().semesterCode(semester.getCode()).user(s)
                    .major(major).currentTerm(8).build());
            list.add(s);
        }
        return list;
    }

    /** Moves the scope past its deadline and runs the automatic transition (3.4). */
    protected FormationScope passDeadline(FormationScope scope) {
        FormationScope s = scopeRepository.findById(scope.getId()).orElseThrow();
        s.setDeadlineOverride(Instant.now().minusSeconds(1));
        scopeRepository.save(s);
        scopeService.tick(scope.getId());
        return scopeRepository.findById(scope.getId()).orElseThrow();
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

    // ------------------------------------------------------------------ groups

    /** The student creates a group in the world's subject; returns its id. */
    protected UUID createGroup(World w, User leader, String name) throws Exception {
        return id(postJson("/api/v1/subjects/" + w.subjectId() + "/groups", leader, Map.of("name", name))
                .andExpect(status().isCreated()));
    }

    /** Applies and has the Leader accept: the student joins. */
    protected void join(UUID groupId, User leader, User student) throws Exception {
        UUID request = id(postJson("/api/v1/groups/" + groupId + "/applications", student, Map.of())
                .andExpect(status().isCreated()));
        postJson("/api/v1/requests/" + request + "/accept", leader, Map.of()).andExpect(status().isOk());
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
