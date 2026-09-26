package com.capstone.tracking.artifact;

import com.capstone.tracking.group.GroupMember;
import com.capstone.tracking.group.GroupMemberRepository;
import com.capstone.tracking.group.GroupStatus;
import com.capstone.tracking.group.MemberStatus;
import com.capstone.tracking.group.StudentGroup;
import com.capstone.tracking.group.StudentGroupRepository;
import com.capstone.tracking.milestone.Milestone;
import com.capstone.tracking.milestone.MilestoneRepository;
import com.capstone.tracking.security.JwtTokenProvider;
import com.capstone.tracking.user.Role;
import com.capstone.tracking.user.User;
import com.capstone.tracking.user.UserRepository;
import com.capstone.tracking.user.UserStatus;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;

import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** FR-004.5: documents uploaded as files or submitted as links, per milestone, via /groups/{id}/documents. */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
class DocumentUploadIntegrationTest {

    private static final byte[] PDF_BYTES = "%PDF-1.4 fake report".getBytes(StandardCharsets.UTF_8);

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private UserRepository userRepository;
    @Autowired private StudentGroupRepository studentGroupRepository;
    @Autowired private GroupMemberRepository groupMemberRepository;
    @Autowired private MilestoneRepository milestoneRepository;
    @Autowired private JwtTokenProvider jwtTokenProvider;

    private User member;
    private User outsider;
    private User instructor;
    private StudentGroup group;
    private Milestone srs;
    private Milestone otherSemester;

    @BeforeEach
    void setUp() {
        instructor = user("doc-gv", Role.INSTRUCTOR);
        member = user("doc-member", Role.STUDENT);
        outsider = user("doc-outsider", Role.STUDENT);
        group = studentGroupRepository.save(StudentGroup.builder().groupCode("DOC-G1").semester("Fall2026")
                .supervisor(instructor).status(GroupStatus.ACTIVE).build());
        groupMemberRepository.save(GroupMember.builder().group(group).user(member).isLeader(false)
                .joinedAt(java.time.Instant.now()).status(MemberStatus.ACTIVE).build());
        srs = milestoneRepository.save(Milestone.builder().code("SRS").name("SRS").semester("Fall2026").sequenceNo(1).build());
        otherSemester = milestoneRepository.save(Milestone.builder().code("SRS").name("SRS").semester("Spring2026").sequenceNo(1).build());
    }

    @Test
    void memberUploadsPdfForMilestoneAndCanDownloadIt() throws Exception {
        MockMultipartFile file = new MockMultipartFile("file", "Báo cáo SRS.pdf", "application/pdf", PDF_BYTES);

        String body = mockMvc.perform(multipart("/api/v1/groups/" + group.getId() + "/documents")
                        .file(file)
                        .param("title", "SRS")
                        .param("milestoneId", srs.getId().toString())
                        .header("Authorization", bearer(member)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.sourceType").value("FILE"))
                .andExpect(jsonPath("$.milestoneId").value(srs.getId().toString()))
                .andExpect(jsonPath("$.originalFilename").value("Báo cáo SRS.pdf"))
                .andExpect(jsonPath("$.fileType").value("pdf"))
                .andExpect(jsonPath("$.sizeBytes").value(PDF_BYTES.length))
                .andExpect(jsonPath("$.submittedById").value(member.getId().toString()))
                .andReturn().getResponse().getContentAsString();
        String id = objectMapper.readTree(body).get("id").asText();
        String fileUrl = objectMapper.readTree(body).get("fileUrl").asText();

        mockMvc.perform(get(fileUrl).header("Authorization", bearer(instructor)))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Disposition", containsString("attachment")))
                .andExpect(content().bytes(PDF_BYTES));

        mockMvc.perform(get("/api/v1/documents/" + id + "/file").header("Authorization", bearer(outsider)))
                .andExpect(status().isForbidden());
    }

    @Test
    void linkCanBeSubmittedThroughTheSameMultipartForm() throws Exception {
        mockMvc.perform(multipart("/api/v1/groups/" + group.getId() + "/documents")
                        .param("title", "Source code")
                        .param("url", "https://github.com/example/repo")
                        .param("milestoneId", srs.getId().toString())
                        .header("Authorization", bearer(member)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.sourceType").value("LINK"))
                .andExpect(jsonPath("$.fileUrl").value("https://github.com/example/repo"));

        mockMvc.perform(get("/api/v1/groups/" + group.getId() + "/documents")
                        .param("milestoneId", srs.getId().toString())
                        .header("Authorization", bearer(instructor)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content.length()").value(1));
    }

    @Test
    void resubmittingTheSameTitleCreatesANewVersion() throws Exception {
        for (int expectedVersion = 1; expectedVersion <= 2; expectedVersion++) {
            mockMvc.perform(multipart("/api/v1/groups/" + group.getId() + "/documents")
                            .file(new MockMultipartFile("file", "srs.docx", "application/octet-stream", PDF_BYTES))
                            .param("title", "SRS")
                            .header("Authorization", bearer(member)))
                    .andExpect(status().isCreated())
                    .andExpect(jsonPath("$.version").value(expectedVersion));
        }
    }

    @Test
    void invalidSubmissionsAreRejected() throws Exception {
        String url = "/api/v1/groups/" + group.getId() + "/documents";

        // neither file nor url
        mockMvc.perform(multipart(url).param("title", "x").header("Authorization", bearer(member)))
                .andExpect(status().isBadRequest());
        // both
        mockMvc.perform(multipart(url).file(new MockMultipartFile("file", "a.pdf", "application/pdf", PDF_BYTES))
                        .param("title", "x").param("url", "https://x.dev").header("Authorization", bearer(member)))
                .andExpect(status().isBadRequest());
        // disallowed extension
        mockMvc.perform(multipart(url).file(new MockMultipartFile("file", "virus.exe", "application/octet-stream", PDF_BYTES))
                        .param("title", "x").header("Authorization", bearer(member)))
                .andExpect(status().isBadRequest());
        // milestone of another semester
        mockMvc.perform(multipart(url).param("title", "x").param("url", "https://x.dev")
                        .param("milestoneId", otherSemester.getId().toString()).header("Authorization", bearer(member)))
                .andExpect(status().isBadRequest());
        // not a member
        mockMvc.perform(multipart(url).param("title", "x").param("url", "https://x.dev").header("Authorization", bearer(outsider)))
                .andExpect(status().isForbidden());
        // missing title
        mockMvc.perform(multipart(url).param("url", "https://x.dev").header("Authorization", bearer(member)))
                .andExpect(status().isBadRequest());
    }

    @Test
    void jsonLinkSubmissionStillWorksOnTheArtifactsPath() throws Exception {
        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .post("/api/v1/groups/" + group.getId() + "/artifacts")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"Demo\",\"fileUrl\":\"https://demo.example\",\"milestoneId\":\"" + srs.getId() + "\"}")
                        .header("Authorization", bearer(member)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.sourceType").value("LINK"))
                .andExpect(jsonPath("$.milestoneId").value(srs.getId().toString()));
    }

    private User user(String name, Role role) {
        return userRepository.save(User.builder().email(name + "@fpt.edu.vn").fullName(name).passwordHash("x")
                .role(role).status(UserStatus.ACTIVE).build());
    }

    private String bearer(User user) {
        return "Bearer " + jwtTokenProvider.generateAccessToken(user.getId(), user.getEmail(), user.getRole().name());
    }
}
