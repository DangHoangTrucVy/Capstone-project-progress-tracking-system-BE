package com.capstone.tracking.group;

import com.capstone.tracking.WorkflowTestSupport;
import com.capstone.tracking.user.*;
import org.junit.jupiter.api.Test;
import java.util.Map;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import org.springframework.http.MediaType;

class StudentCreatesGroupIntegrationTest extends WorkflowTestSupport {
    @Test
    void adminCreatesGroupAndAssignsLeaderWhileStudentCannotSelfCreate() throws Exception {
        User admin = user("provision-admin", Role.ADMIN);
        User student = user("provision-student", Role.STUDENT);
        Map<String,Object> payload = Map.of("groupCode", "PROV-" + suffix, "semester", "Fall2026");
        postJson("/api/v1/groups", student, payload).andExpect(status().isForbidden());
        postJson("/api/v1/groups", user("instructor", Role.INSTRUCTOR), payload).andExpect(status().isForbidden());
        String id = body(postJson("/api/v1/groups", admin, payload).andExpect(status().isCreated())
                .andExpect(jsonPath("$.memberCount").value(0))).get("id").asText();
        postJson("/api/v1/groups/" + id + "/members", admin, Map.of("userId", student.getId(), "isLeader", true))
                .andExpect(status().isCreated());
        getAs("/api/v1/auth/me", student).andExpect(status().isOk()).andExpect(jsonPath("$.role").value("GROUP_LEADER"));
        postJson("/api/v1/groups", student, Map.of("groupCode", "SECOND-" + suffix, "semester", "Fall2026"))
                .andExpect(status().isForbidden());
        postJson("/api/v1/groups/" + id + "/members", admin,
                Map.of("userId", user("second-leader", Role.STUDENT).getId(), "isLeader", true))
                .andExpect(status().isConflict());
    }
}
