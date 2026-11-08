package com.capstone.tracking;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.test.context.TestPropertySource;

@TestPropertySource(properties = {
        "app.formation.default-request-ttl-minutes=15",
        "app.formation.min-deadline-notice-minutes=10"
})
class DemoTimingIntegrationTest extends TestSupport {

    @Test
    void theModeratorConfiguresTheRequestLifetimeInMinutes() throws Exception {
        var w = world(3, 0);
        putJson("/api/v1/semesters/" + w.code() + "/rules", w.moderator(), Map.of("requestTtlMinutes", 10))
                .andExpect(status().isOk()).andExpect(jsonPath("$.requestTtlMinutes").value(10))
                .andExpect(jsonPath("$.minDeadlineNoticeMinutes").value(10));
        var room = createGroup(w, w.students().get(0), "Demo");
        UUID requestId = requestToJoin(room, w.students().get(0), w.students().get(1));
        var request = body(getAs("/api/v1/requests/" + requestId, w.students().get(1)));
        assertThat(Duration.between(Instant.parse(request.get("createdAt").asText()),
                Instant.parse(request.get("expiresAt").asText())))
                .isBetween(Duration.ofMinutes(10).minusSeconds(2), Duration.ofMinutes(10).plusSeconds(1));

        putJson("/api/v1/semesters/" + w.code() + "/rules", w.moderator(), Map.of("requestTtlMinutes", 15))
                .andExpect(status().isOk());
        var invitation = body(postJson("/api/v1/rooms/" + room + "/invitations", w.students().get(0),
                Map.of("studentId", w.students().get(2).getId())).andExpect(status().isCreated()));
        assertThat(Duration.between(Instant.parse(invitation.get("createdAt").asText()),
                Instant.parse(invitation.get("expiresAt").asText())))
                .isBetween(Duration.ofMinutes(15).minusSeconds(2), Duration.ofMinutes(15).plusSeconds(1));
        // Changing the setting does not reset existing requests or their remaining time.
        assertThat(body(getAs("/api/v1/requests/" + requestId, w.students().get(1)))
                .get("expiresAt").asText()).isEqualTo(request.get("expiresAt").asText());
    }

    @Test
    void theDeadlineNeedsTheMinimumNotice() throws Exception {
        var w = world(3, 0);
        assertThat(w.semester().getDeadline()).isAfter(Instant.now().plus(Duration.ofDays(6)));
        putJson("/api/v1/semesters/" + w.code() + "/deadline", w.moderator(),
                Map.of("deadline", Instant.now().plus(Duration.ofMinutes(5)).toString()))
                .andExpect(status().isBadRequest());
        Instant chosen = Instant.now().plus(Duration.ofMinutes(15));
        putJson("/api/v1/semesters/" + w.code() + "/deadline", w.moderator(), Map.of("deadline", chosen.toString()))
                .andExpect(status().isOk());
        assertThat(semesterRepository.findById(w.code()).orElseThrow().getDeadline())
                .isBetween(chosen.minusMillis(1), chosen.plusMillis(1));
    }

    @Test
    void invalidLifetimeAndOthersThanTheModeratorCannotChangeTheRules() throws Exception {
        var w = world(1, 0);
        for (int minutes : new int[] {0, -1, 20161}) {
            putJson("/api/v1/semesters/" + w.code() + "/rules", w.moderator(), Map.of("requestTtlMinutes", minutes))
                    .andExpect(status().isBadRequest());
        }
        putJson("/api/v1/semesters/" + w.code() + "/rules", w.students().get(0), Map.of("requestTtlMinutes", 10))
                .andExpect(status().isForbidden());
        getAs("/api/v1/semesters/" + w.code() + "/rules", w.moderator())
                .andExpect(jsonPath("$.requestTtlMinutes").value(2880));
    }
}
