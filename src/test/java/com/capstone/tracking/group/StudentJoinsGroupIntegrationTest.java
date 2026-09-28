package com.capstone.tracking.group;

import com.capstone.tracking.WorkflowTestSupport;
import com.capstone.tracking.user.*;
import org.junit.jupiter.api.Test;
import java.util.Map;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import org.springframework.http.MediaType;

class StudentJoinsGroupIntegrationTest extends WorkflowTestSupport {
    @Test
    void studentsCannotSelfJoinAndAdminCannotAddOneStudentToTwoGroups() throws Exception {
        User admin = user("join-admin", Role.ADMIN);
        User student = user("join-student", Role.STUDENT);
        StudentGroup first = group("JOIN-A", admin, false);
        StudentGroup second = group("JOIN-B", admin, false);
        postJson("/api/v1/groups/" + first.getId() + "/join", student, Map.of()).andExpect(status().isForbidden());
        Map<String,Object> member = Map.of("userId", student.getId(), "isLeader", false);
        postJson("/api/v1/groups/" + first.getId() + "/members", admin, member).andExpect(status().isCreated());
        postJson("/api/v1/groups/" + second.getId() + "/members", admin, member).andExpect(status().isConflict());
        getAs("/api/v1/auth/me", student).andExpect(status().isForbidden());
    }
}
