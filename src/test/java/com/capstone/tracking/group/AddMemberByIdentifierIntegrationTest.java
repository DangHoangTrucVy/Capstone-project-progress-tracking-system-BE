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

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class AddMemberByIdentifierIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Test
    void addMemberByEmailOrStudentCodeFlow() throws Exception {
        String leaderToken = register("leader-ident@fpt.edu.vn", "Leader");
        JsonNode group = json(mockMvc.perform(post("/api/v1/groups")
                        .header("Authorization", "Bearer " + leaderToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("groupCode", "GI-1", "semester", "Spring2026"))))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString());
        String groupId = group.get("id").asText();

        // Register student 1 with full email
        register("s1-ident@fpt.edu.vn", "Student 1");

        // Register student 2 with student code prefix
        register("SE180002@fpt.edu.vn", "Student 2");

        // 1. Add by email -> success (201)
        mockMvc.perform(post("/api/v1/groups/" + groupId + "/members")
                        .header("Authorization", "Bearer " + leaderToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("email", "s1-ident@fpt.edu.vn", "isLeader", false))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.userEmail").value("s1-ident@fpt.edu.vn"));

        // 2. Add by student code prefix -> success (201)
        mockMvc.perform(post("/api/v1/groups/" + groupId + "/members")
                        .header("Authorization", "Bearer " + leaderToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("identifier", "SE180002", "isLeader", false))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.userEmail").value("se180002@fpt.edu.vn"));

        // 3. Add by non-existent student code/email -> 404
        mockMvc.perform(post("/api/v1/groups/" + groupId + "/members")
                        .header("Authorization", "Bearer " + leaderToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("identifier", "NONEXISTENT", "isLeader", false))))
                .andExpect(status().isNotFound());

        // 4. Add existing student again -> 409 Conflict
        mockMvc.perform(post("/api/v1/groups/" + groupId + "/members")
                        .header("Authorization", "Bearer " + leaderToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("identifier", "SE180002", "isLeader", false))))
                .andExpect(status().isConflict());

        // 5. Existing UUID support still works
        String s3Token = register("s3-ident@fpt.edu.vn", "Student 3");
        String s3Id = json(mockMvc.perform(get("/api/v1/auth/me").header("Authorization", "Bearer " + s3Token))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString()).get("id").asText();

        mockMvc.perform(post("/api/v1/groups/" + groupId + "/members")
                        .header("Authorization", "Bearer " + leaderToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("userId", s3Id, "isLeader", false))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.userEmail").value("s3-ident@fpt.edu.vn"));
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
