package com.capstone.tracking.group;

import com.capstone.tracking.WorkflowTestSupport;
import com.capstone.tracking.user.*;
import org.junit.jupiter.api.Test;
import java.util.Map;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import org.springframework.http.MediaType;

class AddMemberByIdentifierIntegrationTest extends WorkflowTestSupport {
    @Test
    void adminAddsByEmailStudentCodeOrIdAndInvalidIdentifiersAreRejected() throws Exception {
        User admin = user("identifier-admin", Role.ADMIN);
        StudentGroup g = group("IDENTIFIER", admin, false);
        User email = user("by-email", Role.STUDENT);
        User code = user("by-code", Role.STUDENT);
        User id = user("by-id", Role.STUDENT);
        String url = "/api/v1/groups/" + g.getId() + "/members";
        postJson(url, admin, Map.of("identifier", email.getEmail(), "isLeader", false)).andExpect(status().isCreated());
        postJson(url, admin, Map.of("identifier", code.getEmail().split("@")[0], "isLeader", false)).andExpect(status().isCreated());
        postJson(url, admin, Map.of("userId", id.getId(), "isLeader", false)).andExpect(status().isCreated());
        postJson(url, admin, Map.of("identifier", "missing-" + suffix, "isLeader", false)).andExpect(status().isNotFound());
        postJson(url, admin, Map.of("identifier", email.getEmail(), "isLeader", false)).andExpect(status().isConflict());
        postJson(url, admin, Map.of("userId", admin.getId(), "isLeader", false)).andExpect(status().isBadRequest());
        postJson(url, admin, Map.of("isLeader", false)).andExpect(status().isBadRequest());
    }
}
