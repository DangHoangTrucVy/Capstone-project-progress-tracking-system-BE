package com.capstone.tracking.scheduling;

import com.capstone.tracking.security.JwtTokenProvider;
import com.capstone.tracking.user.Role;
import com.capstone.tracking.user.User;
import com.capstone.tracking.user.UserRepository;
import com.capstone.tracking.user.UserStatus;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * API-002 slot search through ScheduleSlotSpecifications. Deliberately NOT @Transactional: a test
 * transaction keeps the Hibernate session open across the MockMvc call and would hide the
 * LazyInitializationException that SlotResponse hit on the lazy instructor in real requests.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class SlotSearchIntegrationTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private UserRepository userRepository;
    @Autowired private ScheduleSlotRepository scheduleSlotRepository;
    @Autowired private PasswordEncoder passwordEncoder;
    @Autowired private JwtTokenProvider jwtTokenProvider;

    private User instructor;
    private ScheduleSlot slot;
    private String token;

    @BeforeEach
    void setUp() {
        instructor = userRepository.save(User.builder()
                .email("slot-search-" + UUID.randomUUID() + "@fpt.edu.vn").fullName("GV Search")
                .passwordHash(passwordEncoder.encode("x")).role(Role.INSTRUCTOR).status(UserStatus.ACTIVE).build());
        Instant start = Instant.now().plus(3, ChronoUnit.DAYS);
        slot = scheduleSlotRepository.save(ScheduleSlot.builder()
                .instructor(instructor).startTime(start).endTime(start.plus(30, ChronoUnit.MINUTES))
                .durationMinutes(30).capacityGroups(2).locationType(LocationType.ONLINE)
                .meetingUrl("https://meet.example/x").status(SlotStatus.AVAILABLE).build());
        token = "Bearer " + jwtTokenProvider.generateAccessToken(instructor.getId(), instructor.getEmail(), instructor.getRole().name());
    }

    @AfterEach
    void tearDown() {
        scheduleSlotRepository.delete(slot);
        userRepository.delete(instructor);
    }

    @Test
    void searchWithoutFiltersReturnsSlotWithInstructorName() throws Exception {
        mockMvc.perform(get("/api/v1/slots").header("Authorization", token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[?(@.id == '" + slot.getId() + "')].instructorName").value("GV Search"));
    }

    @Test
    void searchWithAllFiltersMatchesSlot() throws Exception {
        mockMvc.perform(get("/api/v1/slots").header("Authorization", token)
                        .param("instructorId", instructor.getId().toString())
                        .param("status", "AVAILABLE")
                        .param("fromDate", Instant.now().toString())
                        .param("toDate", Instant.now().plus(7, ChronoUnit.DAYS).toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content.length()").value(1))
                .andExpect(jsonPath("$.content[0].id").value(slot.getId().toString()));
    }

    @Test
    void getByIdReturnsInstructorName() throws Exception {
        mockMvc.perform(get("/api/v1/slots/" + slot.getId()).header("Authorization", token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.instructorName").value("GV Search"));
    }
}
