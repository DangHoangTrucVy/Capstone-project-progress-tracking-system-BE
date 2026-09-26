package com.capstone.tracking.group;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class ReAddRemovedGroupMemberIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private GroupMemberRepository groupMemberRepository;

    @Test
    void addRemoveAndReAddMemberFlowWorksWithout500OrDuplicates() throws Exception {
        // 1. Leader creates group
        String leaderToken = register("readd-leader@fpt.edu.vn", "Leader");
        JsonNode group = json(mockMvc.perform(post("/api/v1/groups")
                        .header("Authorization", "Bearer " + leaderToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("groupCode", "RAD-1", "semester", "Spring2026"))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.memberCount").value(1))
                .andReturn().getResponse().getContentAsString());
        String groupId = group.get("id").asText();

        // 2. Register Student A
        String studentToken = register("student-a@fpt.edu.vn", "Student A");
        String studentId = json(mockMvc.perform(get("/api/v1/auth/me").header("Authorization", "Bearer " + studentToken))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString()).get("id").asText();

        // 3. Add Student A -> Success (201 Created)
        JsonNode addResult = json(mockMvc.perform(post("/api/v1/groups/" + groupId + "/members")
                        .header("Authorization", "Bearer " + leaderToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("userId", studentId, "isLeader", false))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.userId").value(studentId))
                .andReturn().getResponse().getContentAsString());
        String memberId = addResult.get("id").asText();

        // Check active count = 2
        mockMvc.perform(get("/api/v1/groups/" + groupId).header("Authorization", "Bearer " + leaderToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.memberCount").value(2))
                .andExpect(jsonPath("$.members.length()").value(2));

        // 4. Add Student A again when already active -> 409 Conflict (cleanly rejected, not 500)
        mockMvc.perform(post("/api/v1/groups/" + groupId + "/members")
                        .header("Authorization", "Bearer " + leaderToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("userId", studentId, "isLeader", false))))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.errorCode").value("CONFLICT"));

        // 5. Remove Student A -> Success (204 No Content)
        mockMvc.perform(delete("/api/v1/groups/" + groupId + "/members/" + memberId)
                        .header("Authorization", "Bearer " + leaderToken))
                .andExpect(status().isNoContent());

        // Verify status in database is REMOVED
        GroupMember removedEntity = groupMemberRepository.findById(UUID.fromString(memberId)).orElseThrow();
        assertEquals(MemberStatus.REMOVED, removedEntity.getStatus());

        // Check active count = 1
        mockMvc.perform(get("/api/v1/groups/" + groupId).header("Authorization", "Bearer " + leaderToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.memberCount").value(1))
                .andExpect(jsonPath("$.members.length()").value(1));

        // 6. Add Student A again -> SUCCESS (201 Created, reactivated, previously caused 500 duplicate key error)
        mockMvc.perform(post("/api/v1/groups/" + groupId + "/members")
                        .header("Authorization", "Bearer " + leaderToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("userId", studentId, "isLeader", false))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.userId").value(studentId))
                .andExpect(jsonPath("$.status").value("ACTIVE"));

        // Verify in database: status is ACTIVE and there is still exactly 1 record for this (groupId, studentId)
        GroupMember reactivatedEntity = groupMemberRepository.findById(UUID.fromString(memberId)).orElseThrow();
        assertEquals(MemberStatus.ACTIVE, reactivatedEntity.getStatus());

        // 7. Verify Student A appears exactly once in active members list
        mockMvc.perform(get("/api/v1/groups/" + groupId).header("Authorization", "Bearer " + leaderToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.memberCount").value(2))
                .andExpect(jsonPath("$.members.length()").value(2));
    }

    private JsonNode json(String body) throws Exception {
        return objectMapper.readTree(body);
    }

    private String register(String email, String fullName) throws Exception {
        var payload = Map.of("email", email, "fullName", fullName, "password", "Password123");
        return json(mockMvc.perform(post("/api/v1/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(payload)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString()).get("accessToken").asText();
    }
}
