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
    @DisplayName("The handover export is documented as an .xlsx file, not JSON")
    void exportIsXlsx() throws Exception {
        JsonNode content = paths().get("/api/v1/reports/export").get("get").get("responses").get("200").get("content");
        assertThat(content.has("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet")).isTrue();
        assertThat(content.has("application/json")).isFalse();
    }
}
