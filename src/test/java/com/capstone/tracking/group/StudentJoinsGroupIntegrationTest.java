package com.capstone.tracking.group;

import com.capstone.tracking.WorkflowTestSupport;
import com.capstone.tracking.user.Role;
import com.capstone.tracking.user.User;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class StudentJoinsGroupIntegrationTest extends WorkflowTestSupport {

    @Test
    void legacySelfJoinIsGoneAndAdminCannotAddOneStudentToTwoGroups() throws Exception {
        User admin = user("join-admin", Role.ADMIN);
        User student = user("join-student", Role.STUDENT);
        StudentGroup first = group("JOIN-A", admin, false);
        StudentGroup second = group("JOIN-B", admin, false);
        // Joining now goes through Apply -> Invite -> Accept; the old direct endpoint no longer exists.
        postJson("/api/v1/groups/" + first.getId() + "/join", student, Map.of()).andExpect(status().is4xxClientError());
        Map<String, Object> member = Map.of("userId", student.getId(), "isLeader", false);
        postJson("/api/v1/groups/" + first.getId() + "/members", admin, member).andExpect(status().isCreated());
        postJson("/api/v1/groups/" + second.getId() + "/members", admin, member).andExpect(status().isConflict());
        getAs("/api/v1/auth/me", student).andExpect(status().isOk());
    }
}
