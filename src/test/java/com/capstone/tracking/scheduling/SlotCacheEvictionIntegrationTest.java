package com.capstone.tracking.scheduling;

import com.capstone.tracking.group.GroupMember;
import com.capstone.tracking.group.GroupMemberRepository;
import com.capstone.tracking.group.GroupStatus;
import com.capstone.tracking.group.MemberStatus;
import com.capstone.tracking.group.StudentGroup;
import com.capstone.tracking.group.StudentGroupRepository;
import com.capstone.tracking.security.JwtTokenProvider;
import com.capstone.tracking.user.Role;
import com.capstone.tracking.user.User;
import com.capstone.tracking.user.UserRepository;
import com.capstone.tracking.user.UserStatus;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.temporal.ChronoUnit;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** With caching on (in-memory here, Redis in production-like setups), a booking must not leave stale slot counters. */
@SpringBootTest(properties = "spring.cache.type=simple")
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
class SlotCacheEvictionIntegrationTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private UserRepository userRepository;
    @Autowired private StudentGroupRepository studentGroupRepository;
    @Autowired private GroupMemberRepository groupMemberRepository;
    @Autowired private ScheduleSlotRepository scheduleSlotRepository;
    @Autowired private JwtTokenProvider jwtTokenProvider;

    @Test
    void bookingEvictsCachedSlotAndSearchResults() throws Exception {
        User instructor = user("cache-gv", Role.INSTRUCTOR);
        User leader = user("cache-leader", Role.GROUP_LEADER);
        StudentGroup group = studentGroupRepository.save(StudentGroup.builder().groupCode("CACHE-G1").semester("Fall2026")
                .supervisor(instructor).status(GroupStatus.ACTIVE).build());
        groupMemberRepository.save(GroupMember.builder().group(group).user(leader).isLeader(true)
                .joinedAt(Instant.now()).status(MemberStatus.ACTIVE).build());
        Instant start = Instant.now().plus(3, ChronoUnit.DAYS);
        ScheduleSlot slot = scheduleSlotRepository.save(ScheduleSlot.builder().instructor(instructor).startTime(start)
                .endTime(start.plus(1, ChronoUnit.HOURS)).durationMinutes(60).capacityGroups(2)
                .locationType(LocationType.ONLINE).build());
        String byInstructor = "/api/v1/slots?instructorId=" + instructor.getId();

        // Warm both caches.
        mockMvc.perform(get("/api/v1/slots/" + slot.getId()).header("Authorization", bearer(leader)))
                .andExpect(jsonPath("$.bookedCount").value(0));
        mockMvc.perform(get(byInstructor).header("Authorization", bearer(leader)))
                .andExpect(jsonPath("$.content[0].bookedCount").value(0))
                .andExpect(jsonPath("$.totalElements").value(1));

        mockMvc.perform(post("/api/v1/slots/" + slot.getId() + "/book").header("Authorization", bearer(leader))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"groupId\":\"" + group.getId() + "\"}"))
                .andExpect(status().isOk());

        mockMvc.perform(get("/api/v1/slots/" + slot.getId()).header("Authorization", bearer(leader)))
                .andExpect(jsonPath("$.bookedCount").value(1));
        mockMvc.perform(get(byInstructor).header("Authorization", bearer(leader)))
                .andExpect(jsonPath("$.content[0].bookedCount").value(1));
    }

    private User user(String name, Role role) {
        return userRepository.save(User.builder().email(name + "@fpt.edu.vn").fullName(name).passwordHash("x")
                .role(role).status(UserStatus.ACTIVE).build());
    }

    private String bearer(User user) {
        return "Bearer " + jwtTokenProvider.generateAccessToken(user.getId(), user.getEmail(), user.getRole().name());
    }
}
