package com.capstone.tracking.group;

import com.capstone.tracking.WorkflowTestSupport;
import com.capstone.tracking.user.*;
import org.junit.jupiter.api.Test;
import java.util.Map;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import org.springframework.http.MediaType;

class GroupLeaderMemberManagementIntegrationTest extends WorkflowTestSupport {
    @Test
    void onlyAdminAddsMembersLeaderKicksAndSeesOnlyOwnGroup() throws Exception {
        User admin = user("roster-admin", Role.ADMIN);
        User leader = user("roster-leader", Role.GROUP_LEADER);
        StudentGroup a = group("ROSTER-A", admin, false);
        StudentGroup b = group("ROSTER-B", admin, false);
        join(a, leader, true);
        User member = user("roster-member", Role.STUDENT);
        Map<String,Object> payload = Map.of("userId", member.getId(), "isLeader", false);
        postJson("/api/v1/groups/" + a.getId() + "/members", leader, payload).andExpect(status().isForbidden());
        postJson("/api/v1/groups/" + b.getId() + "/members", leader, payload).andExpect(status().isForbidden());
        String memberId = body(postJson("/api/v1/groups/" + a.getId() + "/members", admin, payload)
                .andExpect(status().isCreated())).get("id").asText();
        // YC18: before Locked the leader kicks a member of their own group, but not in someone else's group.
        mockMvc.perform(delete("/api/v1/groups/" + b.getId() + "/members/" + memberId).header("Authorization", bearer(leader)))
                .andExpect(status().isForbidden());
        mockMvc.perform(delete("/api/v1/groups/" + a.getId() + "/members/" + memberId).header("Authorization", bearer(leader)))
                .andExpect(status().isNoContent());
        mockMvc.perform(put("/api/v1/groups/" + a.getId()).header("Authorization", bearer(leader))
                .contentType(MediaType.APPLICATION_JSON).content("{\"status\":\"ACTIVE\"}"))
                .andExpect(status().isForbidden());
        getAs("/api/v1/groups/" + a.getId(), leader).andExpect(status().isOk());
        getAs("/api/v1/groups/" + b.getId(), leader).andExpect(status().isForbidden());
        getAs("/api/v1/groups", leader).andExpect(jsonPath("$.totalElements").value(1));
        getAs("/api/v1/users", leader).andExpect(status().isForbidden());
    }
}
