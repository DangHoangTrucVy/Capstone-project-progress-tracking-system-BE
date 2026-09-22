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

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class StudentJoinsGroupIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Test
    void studentsJoinUntilFullThenGroupIsHiddenFromAvailableList() throws Exception {
        String leaderToken = register("jn-leader@fpt.edu.vn");
        JsonNode group = json(mockMvc.perform(post("/api/v1/groups")
                        .header("Authorization", "Bearer " + leaderToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("groupCode", "JN-1", "semester", "Spring2026"))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.memberCount").value(1))
                .andExpect(jsonPath("$.full").value(false))
                .andReturn().getResponse().getContentAsString());
        String groupId = group.get("id").asText();

        assertTrue(availableCodes(leaderToken).contains("JN-1"));

        String firstJoiner = register("jn-s1@fpt.edu.vn");
        join(groupId, firstJoiner).andExpect(status().isCreated());
        join(groupId, firstJoiner).andExpect(status().isConflict());
        for (int i = 2; i <= 4; i++) {
            join(groupId, register("jn-s" + i + "@fpt.edu.vn")).andExpect(status().isCreated());
        }

        mockMvc.perform(get("/api/v1/groups/" + groupId).header("Authorization", "Bearer " + leaderToken))
                .andExpect(jsonPath("$.memberCount").value(5))
                .andExpect(jsonPath("$.full").value(true));

        join(groupId, register("jn-s5@fpt.edu.vn")).andExpect(status().isConflict());
        assertFalse(availableCodes(leaderToken).contains("JN-1"));
    }

    private org.springframework.test.web.servlet.ResultActions join(String groupId, String token) throws Exception {
        return mockMvc.perform(post("/api/v1/groups/" + groupId + "/join").header("Authorization", "Bearer " + token));
    }

    private String availableCodes(String token) throws Exception {
        return mockMvc.perform(get("/api/v1/groups?available=true&size=100").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
    }

    private JsonNode json(String body) throws Exception {
        return objectMapper.readTree(body);
    }

    private String register(String email) throws Exception {
        var payload = Map.of("email", email, "fullName", "Student", "password", "Password123");
        return json(mockMvc.perform(post("/api/v1/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(payload)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString()).get("accessToken").asText();
    }
}
