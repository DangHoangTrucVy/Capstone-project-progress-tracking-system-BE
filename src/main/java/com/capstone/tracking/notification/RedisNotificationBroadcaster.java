package com.capstone.tracking.notification;

import com.capstone.tracking.notification.dto.NotificationResponse;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.core.RedisOperations;
import org.springframework.data.redis.listener.ChannelTopic;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

/**
 * Several instances: every notification is published on a Redis channel and every instance (including this one)
 * pushes it to the streams it holds, so the recipient gets it whichever instance their browser is connected to.
 */
@Slf4j
@Component
@ConditionalOnProperty(prefix = "app.redis", name = "enabled", havingValue = "true")
public class RedisNotificationBroadcaster implements NotificationBroadcaster {

    static final String CHANNEL = "capstone:notifications";

    /** The message on the channel. */
    record Envelope(UUID recipientId, NotificationResponse notification) {
    }

    private final RedisOperations<String, String> redis;
    private final ObjectMapper objectMapper;
    private final NotificationStreamRegistry streams;

    public RedisNotificationBroadcaster(RedisOperations<String, String> redis, ObjectMapper objectMapper, NotificationStreamRegistry streams) {
        this.redis = redis;
        this.objectMapper = objectMapper;
        this.streams = streams;
    }

    @Override
    public void broadcast(UUID recipientId, NotificationResponse notification) {
        try {
            redis.convertAndSend(CHANNEL, objectMapper.writeValueAsString(new Envelope(recipientId, notification)));
        } catch (JsonProcessingException | RuntimeException e) {
            // Redis down: at least reach the streams on this instance; the rest see it on their next list call.
            log.warn("Could not publish notification {} to Redis, delivering locally only", notification.id(), e);
            streams.push(recipientId, notification);
        }
    }

    /** Called for every message on the channel, from any instance. */
    void onMessage(String json) {
        try {
            Envelope envelope = objectMapper.readValue(json, Envelope.class);
            streams.push(envelope.recipientId(), envelope.notification());
        } catch (JsonProcessingException e) {
            log.warn("Ignoring malformed notification message on {}", CHANNEL, e);
        }
    }

    @Configuration
    @ConditionalOnProperty(prefix = "app.redis", name = "enabled", havingValue = "true")
    static class Subscription {

        @Bean
        RedisMessageListenerContainer notificationListenerContainer(RedisConnectionFactory connectionFactory,
                                                                    RedisNotificationBroadcaster broadcaster) {
            RedisMessageListenerContainer container = new RedisMessageListenerContainer();
            container.setConnectionFactory(connectionFactory);
            container.addMessageListener((message, pattern) ->
                    broadcaster.onMessage(new String(message.getBody(), StandardCharsets.UTF_8)), new ChannelTopic(CHANNEL));
            return container;
        }
    }
}
