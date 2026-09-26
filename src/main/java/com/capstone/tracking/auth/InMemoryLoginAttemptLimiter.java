package com.capstone.tracking.auth;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** Same policy as the Redis limiter, for a single instance without Redis (tests, Railway today). */
@Component
@ConditionalOnProperty(prefix = "app.redis", name = "enabled", havingValue = "false", matchIfMissing = true)
public class InMemoryLoginAttemptLimiter implements LoginAttemptLimiter {

    private record Window(int failures, Instant expiresAt) {
    }

    private final Map<String, Window> windows = new ConcurrentHashMap<>();

    @Override
    public void checkAllowed(String email) {
        Window window = current(email);
        if (window != null && window.failures() >= MAX_FAILURES) {
            throw LoginAttemptLimiter.tooManyAttempts();
        }
    }

    @Override
    public void recordFailure(String email) {
        windows.compute(key(email), (k, w) -> w == null || w.expiresAt().isBefore(Instant.now())
                ? new Window(1, Instant.now().plus(Duration.ofMinutes(WINDOW_MINUTES)))
                : new Window(w.failures() + 1, w.expiresAt()));
    }

    @Override
    public void reset(String email) {
        windows.remove(key(email));
    }

    private Window current(String email) {
        Window window = windows.get(key(email));
        if (window != null && window.expiresAt().isBefore(Instant.now())) {
            windows.remove(key(email), window);
            return null;
        }
        return window;
    }

    private static String key(String email) {
        return email.toLowerCase(Locale.ROOT);
    }
}
