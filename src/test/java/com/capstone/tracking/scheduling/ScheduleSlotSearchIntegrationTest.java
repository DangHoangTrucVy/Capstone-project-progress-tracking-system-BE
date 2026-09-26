package com.capstone.tracking.scheduling;

import com.capstone.tracking.security.JwtTokenProvider;
import com.capstone.tracking.user.Role;
import com.capstone.tracking.user.User;
import com.capstone.tracking.user.UserRepository;
import com.capstone.tracking.user.UserStatus;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Map;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
class ScheduleSlotSearchIntegrationTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private UserRepository userRepository;
    @Autowired private PasswordEncoder passwordEncoder;
    @Autowired private JwtTokenProvider jwtTokenProvider;

    private User instructor;
    private String instructorToken;

    @BeforeEach
    void setUp() {
        instructor = userRepository.save(User.builder()
                .email("slot.search.gv@fpt.edu.vn")
                .fullName("GV Slot Search")
                .passwordHash(passwordEncoder.encode("Password123"))
                .role(Role.INSTRUCTOR)
                .status(UserStatus.ACTIVE)
                .build());
        instructorToken = jwtTokenProvider.generateAccessToken(instructor.getId(), instructor.getEmail(), instructor.getRole().name());
    }

    @Test
    void searchSlotsWithoutFiltersSucceedsWithoutJdbcException() throws Exception {
        // Create 2 slots for testing
        Instant now = Instant.now().truncatedTo(ChronoUnit.SECONDS);
        createSlot(now.plus(1, ChronoUnit.DAYS), now.plus(1, ChronoUnit.DAYS).plus(2, ChronoUnit.HOURS));
        createSlot(now.plus(2, ChronoUnit.DAYS), now.plus(2, ChronoUnit.DAYS).plus(2, ChronoUnit.HOURS));

        // GET /api/v1/slots with no query params (all null filters)
        // Previously triggered: "could not determine data type of parameter $1"
        mockMvc.perform(get("/api/v1/slots")
                        .header("Authorization", "Bearer " + instructorToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content").isArray())
                .andExpect(jsonPath("$.totalElements").value(org.hamcrest.Matchers.greaterThanOrEqualTo(2)));
    }

    @Test
    void searchSlotsWithFiltersWorks() throws Exception {
        Instant now = Instant.now().truncatedTo(ChronoUnit.SECONDS);
        Instant start1 = now.plus(1, ChronoUnit.DAYS);
        Instant end1 = start1.plus(2, ChronoUnit.HOURS);
        createSlot(start1, end1);

        // Filter by instructorId
        mockMvc.perform(get("/api/v1/slots")
                        .param("instructorId", instructor.getId().toString())
                        .header("Authorization", "Bearer " + instructorToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].instructorId").value(instructor.getId().toString()));

        // Filter by status
        mockMvc.perform(get("/api/v1/slots")
                        .param("status", "AVAILABLE")
                        .header("Authorization", "Bearer " + instructorToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content").isArray());

        // Filter by date range
        mockMvc.perform(get("/api/v1/slots")
                        .param("fromDate", now.toString())
                        .param("toDate", now.plus(5, ChronoUnit.DAYS).toString())
                        .header("Authorization", "Bearer " + instructorToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content").isArray());
    }

    @Test
    void createAndGetSlotByIdAndVerifyInList() throws Exception {
        Instant now = Instant.now().truncatedTo(ChronoUnit.SECONDS);
        Instant start = now.plus(3, ChronoUnit.DAYS);
        Instant end = start.plus(1, ChronoUnit.HOURS);

        var payload = Map.of(
                "startTime", start.toString(),
                "endTime", end.toString(),
                "durationMinutes", 45,
                "capacity", 3,
                "locationType", "ONLINE",
                "meetingUrl", "https://meet.google.com/abc-xyz"
        );

        // Test 2: POST /api/v1/slots -> 201 Created
        String responseContent = mockMvc.perform(post("/api/v1/slots")
                        .header("Authorization", "Bearer " + instructorToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(payload)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").isNotEmpty())
                .andExpect(jsonPath("$.instructorId").value(instructor.getId().toString()))
                .andExpect(jsonPath("$.instructorName").value("GV Slot Search"))
                .andExpect(jsonPath("$.durationMinutes").value(45))
                .andExpect(jsonPath("$.capacity").value(3))
                .andExpect(jsonPath("$.bookedCount").value(0))
                .andExpect(jsonPath("$.status").value("AVAILABLE"))
                .andReturn().getResponse().getContentAsString();

        String slotId = objectMapper.readTree(responseContent).get("id").asText();

        // Test 3: GET /api/v1/slots/{id} -> 200 OK
        mockMvc.perform(get("/api/v1/slots/" + slotId)
                        .header("Authorization", "Bearer " + instructorToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(slotId))
                .andExpect(jsonPath("$.instructorName").value("GV Slot Search"))
                .andExpect(jsonPath("$.capacity").value(3))
                .andExpect(jsonPath("$.meetingUrl").value("https://meet.google.com/abc-xyz"));

        // Test 4: GET /api/v1/slots -> contains created slot
        mockMvc.perform(get("/api/v1/slots")
                        .param("instructorId", instructor.getId().toString())
                        .header("Authorization", "Bearer " + instructorToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[?(@.id == '" + slotId + "')]").exists());
    }

    @Test
    void createSlotWithEndTimeBeforeStartTimeReturnsBadRequest() throws Exception {
        Instant now = Instant.now().truncatedTo(ChronoUnit.SECONDS);
        Instant start = now.plus(2, ChronoUnit.DAYS);
        Instant end = start.minus(1, ChronoUnit.HOURS); // invalid: end before start

        var payload = Map.of(
                "startTime", start.toString(),
                "endTime", end.toString(),
                "durationMinutes", 30,
                "capacity", 1,
                "locationType", "OFFLINE"
        );

        mockMvc.perform(post("/api/v1/slots")
                        .header("Authorization", "Bearer " + instructorToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(payload)))
                .andExpect(status().isBadRequest());
    }

    @Test
    void createOverlappingSlotReturnsConflict() throws Exception {
        Instant now = Instant.now().truncatedTo(ChronoUnit.SECONDS);
        Instant start = now.plus(4, ChronoUnit.DAYS);
        Instant end = start.plus(2, ChronoUnit.HOURS);

        createSlot(start, end);

        // Try creating an overlapping slot
        var overlappingPayload = Map.of(
                "startTime", start.plus(30, ChronoUnit.MINUTES).toString(),
                "endTime", end.plus(30, ChronoUnit.MINUTES).toString(),
                "durationMinutes", 30,
                "capacity", 1,
                "locationType", "ONLINE",
                "meetingUrl", "https://meet.google.com/overlap"
        );

        mockMvc.perform(post("/api/v1/slots")
                        .header("Authorization", "Bearer " + instructorToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(overlappingPayload)))
                .andExpect(status().isConflict());
    }

    private void createSlot(Instant start, Instant end) throws Exception {
        var payload = Map.of(
                "startTime", start.toString(),
                "endTime", end.toString(),
                "durationMinutes", 30,
                "capacity", 2,
                "locationType", "ONLINE",
                "meetingUrl", "https://meet.google.com/test-slot"
        );
        mockMvc.perform(post("/api/v1/slots")
                        .header("Authorization", "Bearer " + instructorToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(payload)))
                .andExpect(status().isCreated());
    }
}
