package com.capstone.tracking.notification;

import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.capstone.tracking.TestSupport;
import com.capstone.tracking.user.User;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * Domain events become in-app notifications (default in-process sink), delivered after the transaction commits, to
 * the people the event names and never to the actor.
 */
class NotificationFlowIntegrationTest extends TestSupport {

    @Test
    void theLeaderHearsOfAnApplicationAndTheApplicantOfTheAnswer() throws Exception {
        World w = world(2, 0);
        User leader = w.students().get(0);
        User applicant = w.students().get(1);
        UUID group = createGroup(w, leader, "Notif team");
        long applicantUnread = body(getAs("/api/v1/notifications/unread-count", applicant)).get("unread").asLong();
        UUID request = id(postJson("/api/v1/groups/" + group + "/applications", applicant, Map.of("message", "Cho em vào"))
                .andExpect(status().isCreated()));

        getAs("/api/v1/notifications", leader).andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].type").value("APPLICATION_RECEIVED"))
                .andExpect(jsonPath("$.content[0].message", containsString(applicant.getFullName())))
                .andExpect(jsonPath("$.content[0].groupId").value(group.toString()))
                .andExpect(jsonPath("$.content[0].read").value(false));
        // The applicant is not told about their own action.
        getAs("/api/v1/notifications/unread-count", applicant).andExpect(jsonPath("$.unread").value(applicantUnread));

        postJson("/api/v1/requests/" + request + "/reject", leader, Map.of("reason", "Đủ người làm backend"))
                .andExpect(status().isOk());
        getAs("/api/v1/notifications?unreadOnly=true", applicant)
                .andExpect(jsonPath("$.content[0].type").value("APPLICATION_REJECTED"))
                .andExpect(jsonPath("$.content[0].details").value("Đủ người làm backend"));
    }

    @Test
    void notificationsCanBeMarkedReadOnlyByTheirRecipient() throws Exception {
        World w = world(2, 0);
        User leader = w.students().get(0);
        User applicant = w.students().get(1);
        UUID group = createGroup(w, leader, "Read team");
        postJson("/api/v1/groups/" + group + "/applications", applicant, Map.of()).andExpect(status().isCreated());
        String id = body(getAs("/api/v1/notifications", leader)).get("content").get(0).get("id").asText();

        mockMvc.perform(put("/api/v1/notifications/" + id + "/read").header("Authorization", bearer(applicant)))
                .andExpect(status().isNotFound());
        mockMvc.perform(put("/api/v1/notifications/" + id + "/read").header("Authorization", bearer(leader)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.read").value(true));
        mockMvc.perform(put("/api/v1/notifications/read-all").header("Authorization", bearer(leader)))
                .andExpect(status().isOk());
        getAs("/api/v1/notifications/unread-count", leader).andExpect(jsonPath("$.unread").value(0));
    }
}
