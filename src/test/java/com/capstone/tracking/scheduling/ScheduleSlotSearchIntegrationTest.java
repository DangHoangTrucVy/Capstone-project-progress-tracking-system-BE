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
