package com.capstone.tracking.group;

import com.capstone.tracking.WorkflowTestSupport;
import com.capstone.tracking.user.*;
import org.junit.jupiter.api.Test;
import java.util.Map;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import org.springframework.http.MediaType;

class ReAddRemovedGroupMemberIntegrationTest extends WorkflowTestSupport {
    @Test
    void adminCanRemoveAndReAddWithoutDuplicateRows() throws Exception {
        User admin = user("readd-admin", Role.ADMIN);
        StudentGroup g = group("READD", admin, false);
        User student = user("readd-student", Role.STUDENT);
        String url = "/api/v1/groups/" + g.getId() + "/members";
        Map<String,Object> payload = Map.of("userId", student.getId(), "isLeader", true);
        String id = body(postJson(url, admin, payload).andExpect(status().isCreated())).get("id").asText();
        mockMvc.perform(delete(url + "/" + id).header("Authorization", bearer(admin))).andExpect(status().isNoContent());
        assertThat(userRepository.findById(student.getId()).orElseThrow().getRole()).isEqualTo(Role.STUDENT);
        postJson(url, admin, payload).andExpect(status().isCreated()).andExpect(jsonPath("$.id").value(id));
        assertThat(groupMemberRepository.findByGroupIdAndStatus(g.getId(), MemberStatus.ACTIVE)).hasSize(1);
        postJson(url, admin, payload).andExpect(status().isConflict());
    }
}
