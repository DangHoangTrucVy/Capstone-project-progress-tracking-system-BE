package com.capstone.tracking;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Swagger must describe what the endpoints really answer: 201 on creation, the .xlsx file on export. */
class OpenApiDocsIntegrationTest extends TestSupport {

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
                {"/api/v1/semesters", "post", "SemesterResponse"},
                {"/api/v1/subjects/{subjectId}/groups", "post", "GroupResponse"},
                {"/api/v1/groups/{groupId}/applications", "post", "JoinRequestResponse"},
                {"/api/v1/groups/{groupId}/invitations", "post", "JoinRequestResponse"},
                {"/api/v1/scopes/{scopeId}/groups", "post", "GroupResponse"}}) {
            JsonNode responses = paths.get(op[0]).get(op[1]).get("responses");
            assertThat(responses.has("201")).as(op[0]).isTrue();
            assertThat(responses.has("200")).as(op[0]).isFalse();
            assertThat(responses.get("201").toString()).as(op[0]).contains(op[2]);
        }
        JsonNode accept = paths.get("/api/v1/requests/{requestId}/accept").get("post").get("responses");
        assertThat(accept.has("200")).isTrue();
        assertThat(accept.has("201")).isFalse();
    }

    @Test
    @DisplayName("Error answers are documented with the ErrorResponse body; public sign-in has no 401/403 from the token")
    void errorResponses() throws Exception {
        JsonNode paths = paths();
        JsonNode invite = paths.get("/api/v1/groups/{groupId}/invitations").get("post").get("responses");
        for (String code : new String[] {"400", "401", "403", "404", "409"}) {
            assertThat(invite.has(code)).as(code).isTrue();
            assertThat(invite.get(code).toString()).as(code).contains("#/components/schemas/ErrorResponse");
        }
        JsonNode list = paths.get("/api/v1/majors").get("get").get("responses");
        assertThat(list.has("401")).isTrue();
        assertThat(list.has("404")).isFalse();
        assertThat(list.has("409")).isFalse();
        assertThat(paths.has("/api/v1/auth/login")).isTrue();
        assertThat(paths.has("/api/v1/imports/lecturers")).isFalse();
        assertThat(paths.has("/api/v1/semesters/{semester}/audit")).isFalse();
        JsonNode login = paths.get("/api/v1/auth/google").get("post").get("responses");
        assertThat(login.has("400")).isTrue();
        assertThat(login.has("401")).isTrue();
        assertThat(login.has("403")).isTrue();
    }

    @Test
    @DisplayName("Removed compatibility operations are absent from OpenAPI and cannot be called")
    void obsoleteOperationsAreRemoved() throws Exception {
        var w = world(1, 0);
        JsonNode paths = paths();
        assertThat(paths.get("/api/v1/users").has("post")).isFalse();
        assertThat(paths.get("/api/v1/semesters/{semester}/subjects").has("post")).isFalse();
        assertThat(paths.get("/api/v1/subjects/{subjectId}/groups").has("get")).isFalse();
        assertThat(paths.has("/api/v1/subjects/{subjectId}/students")).isFalse();
        assertThat(paths.get("/api/v1/subjects/{subjectId}").has("delete")).isFalse();
        assertThat(paths.has("/api/v1/semesters/{semester}/students")).isTrue();
        assertThat(paths.has("/api/v1/semesters/{semester}/groups")).isTrue();
        postJson("/api/v1/users", w.admin(), java.util.Map.of()).andExpect(status().isMethodNotAllowed());
        getAs("/api/v1/subjects/" + w.subjectId() + "/students", w.admin()).andExpect(status().isNotFound());
        getAs("/api/v1/subjects/" + w.subjectId() + "/groups", w.admin()).andExpect(status().isMethodNotAllowed());
        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                .delete("/api/v1/subjects/" + w.subjectId()).with(
                        org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user(w.admin())))
                .andExpect(status().isMethodNotAllowed());
    }

    @Test
    @DisplayName("The handover export is documented as an .xlsx file, not JSON")
    void exportIsXlsx() throws Exception {
        JsonNode content = paths().get("/api/v1/semesters/{semester}/export").get("get").get("responses").get("200")
                .get("content");
        assertThat(content.has("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet")).isTrue();
        assertThat(content.has("application/json")).isFalse();
    }
}
