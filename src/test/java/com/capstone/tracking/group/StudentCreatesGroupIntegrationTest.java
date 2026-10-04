package com.capstone.tracking.group;

import com.capstone.tracking.WorkflowTestSupport;
import com.capstone.tracking.user.Role;
import com.capstone.tracking.user.User;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** YC06/YC07: an eligible student without a group creates one and becomes its Leader. */
class StudentCreatesGroupIntegrationTest extends WorkflowTestSupport {

    @Test
    void studentCreatesGroupAndBecomesLeader() throws Exception {
        User student = user("creator", Role.STUDENT);
        String id = body(postJson("/api/v1/groups", student, Map.of("semester", "Fall2026"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.memberCount").value(1))
                .andExpect(jsonPath("$.minMembers").value(3))
                .andExpect(jsonPath("$.meetsMinimum").value(false))
                .andExpect(jsonPath("$.full").value(false))
                .andExpect(jsonPath("$.groupCode").isNotEmpty())).get("id").asText();

        getAs("/api/v1/auth/me", student).andExpect(status().isOk()).andExpect(jsonPath("$.role").value("GROUP_LEADER"));
        getAs("/api/v1/groups/" + id, student).andExpect(status().isOk())
                .andExpect(jsonPath("$.members[0].isLeader").value(true));
        assertThat(groupMemberRepository.existsByGroupIdAndUserIdAndIsLeaderTrueAndStatus(
                java.util.UUID.fromString(id), student.getId(), MemberStatus.ACTIVE)).isTrue();

        // One official group at a time: the new Leader cannot lead (or create) a second one.
        postJson("/api/v1/groups", student, Map.of("semester", "Fall2026")).andExpect(status().isConflict());
    }

    @Test
    void ineligibleStudentCannotCreateGroupButCanSeeWhy() throws Exception {
        User student = user("blocked", Role.STUDENT);
        student.setEligible(false);
        student.setIneligibleReason("Chua du tin chi");
        userRepository.save(student);

        postJson("/api/v1/groups", student, Map.of("semester", "Fall2026"))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.errorCode").value("NOT_ELIGIBLE"));
        getAs("/api/v1/eligibility/me", student).andExpect(status().isOk())
                .andExpect(jsonPath("$.eligible").value(false))
                .andExpect(jsonPath("$.reason").value("Chua du tin chi"));
    }

    @Test
    void studentCannotPickTopicOrSupervisorAndStaffCannotCreate() throws Exception {
        User student = user("creator2", Role.STUDENT);
        User instructor = user("instructor", Role.INSTRUCTOR);
        postJson("/api/v1/groups", student,
                Map.of("semester", "Fall2026", "supervisorId", instructor.getId())).andExpect(status().isBadRequest());
        postJson("/api/v1/groups", instructor, Map.of("semester", "Fall2026")).andExpect(status().isForbidden());
    }

    @Test
    void adminCanStillProvisionAnEmptyGroup() throws Exception {
        User admin = user("provision-admin", Role.ADMIN);
        postJson("/api/v1/groups", admin, Map.of("groupCode", "PROV-" + suffix, "semester", "Fall2026"))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.memberCount").value(0));
        postJson("/api/v1/groups", admin, Map.of("groupCode", "PROV-" + suffix, "semester", "Fall2026"))
                .andExpect(status().isConflict());
    }
}
