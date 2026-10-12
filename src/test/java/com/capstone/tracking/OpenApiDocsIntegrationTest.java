package com.capstone.tracking;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Swagger must describe what the endpoints really answer: 201 on creation, the .xlsx file on export. */
class OpenApiDocsIntegrationTest extends WorkflowTestSupport {

    private JsonNode paths() throws Exception {
        String json = mockMvc.perform(get("/v3/api-docs")).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8);
        return objectMapper.readTree(json).get("paths");
    }

    @Test
    @DisplayName("Endpoints that answer 201 are documented as 201, with their response body")
    void createdEndpoints() throws Exception {
        JsonNode paths = paths();
        for (String[] op : new String[][] {
                {"/api/v1/groups/{groupId}/invites", "post", "JoinRequestResponse"},
                {"/api/v1/groups/{groupId}/leave-requests", "post", "LeaveRequestResponse"},
                {"/api/v1/invites/{id}/accept", "post", "GroupMemberResponse"},
                {"/api/v1/groups", "post", "StudentGroupResponse"},
                {"/api/v1/groups/{groupId}/artifacts", "post", "ArtifactResponse"}}) {
            JsonNode responses = paths.get(op[0]).get(op[1]).get("responses");
            assertThat(responses.has("201")).as(op[0]).isTrue();
            assertThat(responses.has("200")).as(op[0]).isFalse();
            assertThat(responses.get("201").toString()).as(op[0]).contains(op[2]);
        }
        JsonNode feedback = paths.get("/api/v1/artifacts/{id}/feedback").get("put").get("responses");
        assertThat(feedback.has("200")).isTrue();
        assertThat(feedback.has("201")).isFalse();
    }

    @Test
    @DisplayName("Error answers are documented with the ErrorResponse body; public sign-in has no 401/403 from the token")
    void errorResponses() throws Exception {
        JsonNode paths = paths();
        JsonNode invite = paths.get("/api/v1/groups/{groupId}/invites").get("post").get("responses");
        for (String code : new String[] {"400", "401", "403", "404", "409"}) {
            assertThat(invite.has(code)).as(code).isTrue();
            assertThat(invite.get(code).toString()).as(code).contains("#/components/schemas/ErrorResponse");
        }
        JsonNode list = paths.get("/api/v1/groups").get("get").get("responses");
        assertThat(list.has("401")).isTrue();
        assertThat(list.has("404")).isFalse();
        assertThat(list.has("409")).isFalse();
        JsonNode login = paths.get("/api/v1/auth/login").get("post").get("responses");
        assertThat(login.has("400")).isTrue();
        assertThat(login.has("401")).isTrue();
        assertThat(login.has("403")).isFalse();
    }

    @Test
    @DisplayName("The handover export is documented as an .xlsx file, not JSON")
    void exportIsXlsx() throws Exception {
        JsonNode content = paths().get("/api/v1/reports/export").get("get").get("responses").get("200").get("content");
        assertThat(content.has("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet")).isTrue();
        assertThat(content.has("application/json")).isFalse();
    }
}
