package com.capstone.tracking;

import com.capstone.tracking.artifact.ArtifactSubmissionRepository;
import com.capstone.tracking.group.GroupMember;
import com.capstone.tracking.group.GroupMemberRepository;
import com.capstone.tracking.group.GroupStatus;
import com.capstone.tracking.group.MemberStatus;
import com.capstone.tracking.group.StudentGroup;
import com.capstone.tracking.group.StudentGroupRepository;
import com.capstone.tracking.notification.NotificationRepository;
import com.capstone.tracking.scheduling.LocationType;
import com.capstone.tracking.scheduling.ScheduleSlot;
import com.capstone.tracking.scheduling.ScheduleSlotRepository;
import com.capstone.tracking.security.JwtTokenProvider;
import com.capstone.tracking.user.Role;
import com.capstone.tracking.user.User;
import com.capstone.tracking.user.UserRepository;
import com.capstone.tracking.user.UserStatus;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import software.amazon.awssdk.services.s3.S3Client;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The infrastructure tier against real services: S3 and SQS on Floci, cache and login rate limit on Redis.
 * Runs only with INFRA_TESTS=true (CI starts Floci and Redis as service containers; locally:
 * {@code docker compose up -d floci redis}). Endpoints come from AWS_ENDPOINT_URL / REDIS_HOST, defaulting to localhost.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@EnabledIfEnvironmentVariable(named = "INFRA_TESTS", matches = "true")
class InfrastructureIntegrationTest {

    private static final String RUN = UUID.randomUUID().toString().substring(0, 8);

    @DynamicPropertySource
    static void infrastructure(DynamicPropertyRegistry registry) {
        String awsEndpoint = System.getenv().getOrDefault("AWS_ENDPOINT_URL", "http://localhost:4566");
        registry.add("app.aws.endpoint", () -> awsEndpoint);
        registry.add("app.aws.region", () -> "ap-southeast-1");
        registry.add("app.aws.access-key-id", () -> "test");
        registry.add("app.aws.secret-access-key", () -> "test");
        registry.add("app.storage.type", () -> "s3");
        registry.add("app.storage.s3.bucket", () -> "capstone-test-" + RUN);
        registry.add("app.storage.s3.create-bucket", () -> "true");
        registry.add("app.messaging.type", () -> "sqs");
        registry.add("app.messaging.sqs.queue-name", () -> "capstone-test-" + RUN);
        registry.add("app.messaging.sqs.create-queue", () -> "true");
        registry.add("app.messaging.sqs.wait-time-seconds", () -> "1");
        registry.add("app.redis.enabled", () -> "true");
        registry.add("spring.cache.type", () -> "redis");
        registry.add("spring.data.redis.host", () -> System.getenv().getOrDefault("REDIS_HOST", "localhost"));
    }

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private UserRepository userRepository;
    @Autowired private StudentGroupRepository studentGroupRepository;
    @Autowired private GroupMemberRepository groupMemberRepository;
    @Autowired private ScheduleSlotRepository scheduleSlotRepository;
    @Autowired private ArtifactSubmissionRepository artifactSubmissionRepository;
    @Autowired private NotificationRepository notificationRepository;
    @Autowired private S3Client s3;
    @Autowired private StringRedisTemplate redis;
    @Autowired private JwtTokenProvider jwtTokenProvider;

    private final String suffix = UUID.randomUUID().toString().substring(0, 8);
    private User supervisor;
    private User leader;
    private StudentGroup group;

    @BeforeEach
    void setUp() {
        supervisor = user("infra-gv", Role.INSTRUCTOR);
        leader = user("infra-leader", Role.GROUP_LEADER);
        group = studentGroupRepository.save(StudentGroup.builder().groupCode("INFRA-" + suffix).semester("InfraTest")
                .supervisor(supervisor).status(GroupStatus.ACTIVE).build());
        groupMemberRepository.save(GroupMember.builder().group(group).user(leader).isLeader(true)
                .joinedAt(Instant.now()).status(MemberStatus.ACTIVE).build());
    }

    @Test
    void uploadedDocumentsAreStoredInS3() throws Exception {
        byte[] bytes = "report stored on floci s3".getBytes(StandardCharsets.UTF_8);
        String body = mockMvc.perform(multipart("/api/v1/groups/" + group.getId() + "/documents")
                        .file(new MockMultipartFile("file", "report.pdf", "application/pdf", bytes))
                        .param("title", "S3 report").header("Authorization", bearer(leader)))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        UUID id = UUID.fromString(objectMapper.readTree(body).get("id").asText());

        String key = artifactSubmissionRepository.findById(id).orElseThrow().getStorageKey();
        assertThat(s3.headObject(b -> b.bucket("capstone-test-" + RUN).key(key)).contentLength()).isEqualTo(bytes.length);

        mockMvc.perform(get("/api/v1/documents/" + id + "/file").header("Authorization", bearer(supervisor)))
                .andExpect(status().isOk())
                .andExpect(content().bytes(bytes));
    }

    @Test
    void domainEventsTravelThroughSqsToNotifications() throws Exception {
        mockMvc.perform(multipart("/api/v1/groups/" + group.getId() + "/documents")
                        .param("title", "Queued").param("url", "https://github.com/x").header("Authorization", bearer(leader)))
                .andExpect(status().isCreated());

        // Delivered asynchronously by the SQS poller.
        long deadline = System.currentTimeMillis() + 30_000;
        while (notificationRepository.countByRecipientIdAndReadAtIsNull(supervisor.getId()) == 0
                && System.currentTimeMillis() < deadline) {
            Thread.sleep(250);
        }
        assertThat(notificationRepository.countByRecipientIdAndReadAtIsNull(supervisor.getId())).isEqualTo(1);
    }

    @Test
    void failedLoginsAreCountedInRedis() throws Exception {
        mockMvc.perform(post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"" + leader.getEmail() + "\",\"password\":\"wrong\"}"))
                .andExpect(status().isUnauthorized());
        assertThat(redis.opsForValue().get("login:fail:" + leader.getEmail())).isEqualTo("1");
        assertThat(redis.getExpire("login:fail:" + leader.getEmail())).isPositive();
    }

    @Test
    void slotReadsAreCachedInRedisAndEvictedAfterBooking() throws Exception {
        Instant start = Instant.now().plus(5, ChronoUnit.DAYS);
        ScheduleSlot slot = scheduleSlotRepository.save(ScheduleSlot.builder().instructor(supervisor).startTime(start)
                .endTime(start.plus(1, ChronoUnit.HOURS)).durationMinutes(60).capacityGroups(2)
                .locationType(LocationType.ONLINE).build());
        String cacheKey = "capstone::slot::" + slot.getId();

        mockMvc.perform(get("/api/v1/slots/" + slot.getId()).header("Authorization", bearer(leader))).andExpect(status().isOk());
        assertThat(redis.hasKey(cacheKey)).isTrue();

        mockMvc.perform(post("/api/v1/slots/" + slot.getId() + "/book").header("Authorization", bearer(leader))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"groupId\":\"" + group.getId() + "\"}"))
                .andExpect(status().isOk());
        assertThat(redis.hasKey(cacheKey)).isFalse();
    }

    private User user(String name, Role role) {
        return userRepository.save(User.builder().email(name + "-" + suffix + "@fpt.edu.vn").fullName(name)
                .passwordHash("x").role(role).status(UserStatus.ACTIVE).build());
    }

    private String bearer(User user) {
        return "Bearer " + jwtTokenProvider.generateAccessToken(user.getId(), user.getEmail(), user.getRole().name());
    }
}
