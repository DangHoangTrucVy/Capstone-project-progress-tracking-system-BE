package com.capstone.tracking.notification;

import com.capstone.tracking.notification.dto.NotificationResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Open Server-Sent-Event streams per user, so the Leader's Overview updates its badges the moment a notification is
 * written instead of polling. Streams live in this JVM only: with several instances behind SQS a user connected to
 * another instance still gets the notification on the next list/unread-count call.
 */
@Slf4j
@Component
public class NotificationStreamRegistry {

    private static final long TIMEOUT_MS = 30 * 60 * 1000L;

    private final Map<UUID, List<SseEmitter>> emitters = new ConcurrentHashMap<>();

    public SseEmitter open(UUID userId) {
        SseEmitter emitter = new SseEmitter(TIMEOUT_MS);
        List<SseEmitter> list = emitters.computeIfAbsent(userId, id -> new CopyOnWriteArrayList<>());
        list.add(emitter);
        Runnable remove = () -> list.remove(emitter);
        emitter.onCompletion(remove);
        emitter.onTimeout(remove);
        emitter.onError(e -> remove.run());
        try {
            emitter.send(SseEmitter.event().name("connected").data("ok"));
        } catch (IOException e) {
            remove.run();
        }
        return emitter;
    }

    public void push(UUID userId, NotificationResponse notification) {
        List<SseEmitter> list = emitters.get(userId);
        if (list == null) {
            return;
        }
        for (SseEmitter emitter : list) {
            try {
                emitter.send(SseEmitter.event().name("notification").id(notification.id().toString()).data(notification));
            } catch (IOException | IllegalStateException e) {
                log.debug("Dropping closed notification stream of {}", userId);
                list.remove(emitter);
            }
        }
    }
}
