package com.capstone.tracking;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.capstone.tracking.notification.NotificationRepository;
import com.capstone.tracking.notification.NotificationStreamRegistry;
import com.capstone.tracking.user.User;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.mock.mockito.SpyBean;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * The infrastructure tier against real services: SQS on Floci, login rate limit and notification fan-out on Redis.
 * Runs only with INFRA_TESTS=true (CI starts Floci and Redis as service containers; locally:
 * {@code docker compose up -d floci redis}). Endpoints come from AWS_ENDPOINT_URL / REDIS_HOST, defaulting to localhost.
 */
@EnabledIfEnvironmentVariable(named = "INFRA_TESTS", matches = "true")
class InfrastructureIntegrationTest extends TestSupport {

    private static final String RUN = UUID.randomUUID().toString().substring(0, 8);

    @DynamicPropertySource
    static void infrastructure(DynamicPropertyRegistry registry) {
        String awsEndpoint = System.getenv().getOrDefault("AWS_ENDPOINT_URL", "http://localhost:4566");
        registry.add("app.aws.endpoint", () -> awsEndpoint);
        registry.add("app.aws.region", () -> "ap-southeast-1");
        registry.add("app.aws.access-key-id", () -> "test");
        registry.add("app.aws.secret-access-key", () -> "test");
        registry.add("app.messaging.type", () -> "sqs");
        registry.add("app.messaging.sqs.queue-name", () -> "team-formation-test-" + RUN);
        registry.add("app.messaging.sqs.create-queue", () -> "true");
        registry.add("app.messaging.sqs.wait-time-seconds", () -> "1");
        registry.add("app.redis.enabled", () -> "true");
        registry.add("spring.cache.type", () -> "redis");
        registry.add("spring.data.redis.host", () -> System.getenv().getOrDefault("REDIS_HOST", "localhost"));
    }

    @Autowired private NotificationRepository notificationRepository;
    @Autowired private StringRedisTemplate redis;
    @Autowired private com.capstone.tracking.auth.LoginAttemptLimiter loginLimiter;
    @SpyBean private NotificationStreamRegistry streams;

    @Test
    void domainEventsTravelThroughSqsToNotifications() throws Exception {
        World w = world(2, 0);
        User leader = w.students().get(0);
        UUID group = createGroup(w, leader, "Queued");
        long before = notificationRepository.countByRecipientIdAndReadAtIsNull(leader.getId());
        postJson("/api/v1/groups/" + group + "/applications", w.students().get(1), Map.of()).andExpect(status().isCreated());

        // Delivered asynchronously by the SQS poller.
        long deadline = System.currentTimeMillis() + 30_000;
        while (notificationRepository.countByRecipientIdAndReadAtIsNull(leader.getId()) == before
                && System.currentTimeMillis() < deadline) {
            Thread.sleep(250);
        }
        assertThat(notificationRepository.countByRecipientIdAndReadAtIsNull(leader.getId())).isEqualTo(before + 1);
    }

    @Test
    void failedLoginsAreCountedInRedis() throws Exception {
        User lecturer = user("infra-lecturer", com.capstone.tracking.user.Role.LECTURER);
        loginLimiter.recordFailure(lecturer.getEmail());
        assertThat(redis.opsForValue().get("login:fail:" + lecturer.getEmail())).isEqualTo("1");
        assertThat(redis.getExpire("login:fail:" + lecturer.getEmail())).isPositive();
    }

    /** SQS consumer writes the notification, Redis pub/sub fans it out, every instance pushes to its SSE streams. */
    @Test
    void notificationsReachStreamsThroughRedisPubSub() throws Exception {
        World w = world(2, 0);
        User leader = w.students().get(0);
        UUID group = createGroup(w, leader, "Realtime");
        postJson("/api/v1/groups/" + group + "/applications", w.students().get(1), Map.of()).andExpect(status().isCreated());
        verify(streams, timeout(20_000)).push(eq(leader.getId()), any());
    }
}
