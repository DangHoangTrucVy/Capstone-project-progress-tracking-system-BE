package com.capstone.tracking.notification;

import com.capstone.tracking.notification.dto.NotificationResponse;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.RedisOperations;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

/** The message that crosses instances survives the JSON round trip; with Redis down it still reaches local streams. */
class RedisNotificationBroadcasterTest {

    private final ObjectMapper objectMapper = JsonMapper.builder().findAndAddModules().build();
    @SuppressWarnings("unchecked")
    private final RedisOperations<String, String> redis = mock(RedisOperations.class);
    private final RecordingRegistry streams = new RecordingRegistry();
    private final RedisNotificationBroadcaster broadcaster = new RedisNotificationBroadcaster(redis, objectMapper, streams);

    private final UUID recipient = UUID.randomUUID();
    private final NotificationResponse notification = new NotificationResponse(UUID.randomUUID(),
            DomainEventType.TOPIC_APPROVED, "Hội đồng đã DUYỆT đề tài", UUID.randomUUID(), UUID.randomUUID(),
            "Đạt", Instant.now().truncatedTo(ChronoUnit.MILLIS), false, null, Instant.now().truncatedTo(ChronoUnit.MILLIS));

    @Test
    void publishedMessageIsPushedToStreamsByTheSubscriber() {
        broadcaster.broadcast(recipient, notification);

        ArgumentCaptor<String> json = ArgumentCaptor.forClass(String.class);
        verify(redis).convertAndSend(eq(RedisNotificationBroadcaster.CHANNEL), json.capture());
        assertThat(streams.pushed).isEmpty(); // delivery happens when the message comes back from Redis

        broadcaster.onMessage(json.getValue());
        assertThat(streams.pushed).containsExactly(Map.entry(recipient, notification));
    }

    @Test
    void fallsBackToLocalStreamsWhenRedisIsDown() {
        doThrow(new RedisConnectionFailureException("down")).when(redis).convertAndSend(anyString(), anyString());
        broadcaster.broadcast(recipient, notification);
        assertThat(streams.pushed).containsExactly(Map.entry(recipient, notification));
    }

    /** Records pushes instead of writing to SSE connections. */
    private static final class RecordingRegistry extends NotificationStreamRegistry {
        final List<Map.Entry<UUID, NotificationResponse>> pushed = new ArrayList<>();

        @Override
        public void push(UUID userId, NotificationResponse notification) {
            pushed.add(Map.entry(userId, notification));
        }
    }
}
