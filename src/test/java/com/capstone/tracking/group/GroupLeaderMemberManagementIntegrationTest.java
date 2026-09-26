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

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class GroupLeaderMemberManagementIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Test
    void leaderOfGroupACanManageGroupAButForbiddenOnGroupBAndHasNoAdminAccess() throws Exception {
        // 1. Leader A creates Group A
        String leaderAToken = register("leader-a@fpt.edu.vn", "Leader A");
        JsonNode groupA = json(mockMvc.perform(post("/api/v1/groups")
                        .header("Authorization", "Bearer " + leaderAToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("groupCode", "GA-1", "semester", "Spring2026"))))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString());
        String groupAId = groupA.get("id").asText();

        // 2. Leader B creates Group B
        String leaderBToken = register("leader-b@fpt.edu.vn", "Leader B");
        JsonNode groupB = json(mockMvc.perform(post("/api/v1/groups")
                        .header("Authorization", "Bearer " + leaderBToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("groupCode", "GB-1", "semester", "Spring2026"))))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString());
        String groupBId = groupB.get("id").asText();

        // 3. Register students
        String student1Token = register("member-s1@fpt.edu.vn", "Student 1");
        String student1Id = getMyUserId(student1Token);

        String student2Token = register("member-s2@fpt.edu.vn", "Student 2");
        String student2Id = getMyUserId(student2Token);

        // --- ADD MEMBER TESTS ---
        // Leader A adds Student 1 to Group A -> allowed (201)
        JsonNode addS1Result = json(mockMvc.perform(post("/api/v1/groups/" + groupAId + "/members")
                        .header("Authorization", "Bearer " + leaderAToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("userId", student1Id, "isLeader", false))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.userId").value(student1Id))
                .andReturn().getResponse().getContentAsString());
        String s1MemberId = addS1Result.get("id").asText();

        // Leader B adds Student 2 to Group B -> allowed (201)
        JsonNode addS2Result = json(mockMvc.perform(post("/api/v1/groups/" + groupBId + "/members")
                        .header("Authorization", "Bearer " + leaderBToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("userId", student2Id, "isLeader", false))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.userId").value(student2Id))
                .andReturn().getResponse().getContentAsString());
        String s2MemberId = addS2Result.get("id").asText();

        // Leader A tries to add Student to Group B -> forbidden (403)
        mockMvc.perform(post("/api/v1/groups/" + groupBId + "/members")
                        .header("Authorization", "Bearer " + leaderAToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("userId", student1Id, "isLeader", false))))
                .andExpect(status().isForbidden());

        // --- UPDATE GROUP TESTS ---
        // Leader A updates Group A -> allowed (200)
        mockMvc.perform(put("/api/v1/groups/" + groupAId)
                        .header("Authorization", "Bearer " + leaderAToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("status", "ACTIVE"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ACTIVE"));

        // Leader A tries to update Group B -> forbidden (403)
        mockMvc.perform(put("/api/v1/groups/" + groupBId)
                        .header("Authorization", "Bearer " + leaderAToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("status", "ACTIVE"))))
                .andExpect(status().isForbidden());

        // --- REMOVE MEMBER TESTS ---
        // Leader A tries to remove Student 2 from Group B -> forbidden (403)
        mockMvc.perform(delete("/api/v1/groups/" + groupBId + "/members/" + s2MemberId)
                        .header("Authorization", "Bearer " + leaderAToken))
                .andExpect(status().isForbidden());

        // Leader A removes Student 1 from Group A -> allowed (204)
        mockMvc.perform(delete("/api/v1/groups/" + groupAId + "/members/" + s1MemberId)
                        .header("Authorization", "Bearer " + leaderAToken))
                .andExpect(status().isNoContent());

        // --- GLOBAL ADMIN PERMISSION RESTRICTION ---
        // Leader A does not have admin permissions (e.g. creating user via admin endpoint)
        mockMvc.perform(post("/api/v1/users")
                        .header("Authorization", "Bearer " + leaderAToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "email", "newadmin@fpt.edu.vn",
                                "fullName", "Should Fail",
                                "password", "Password123",
                                "role", "STUDENT"))))
                .andExpect(status().isForbidden());
    }

    private String getMyUserId(String token) throws Exception {
        return json(mockMvc.perform(get("/api/v1/auth/me")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString())
                .get("id").asText();
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
