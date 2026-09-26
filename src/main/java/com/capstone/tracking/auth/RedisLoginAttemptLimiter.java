package com.capstone.tracking.auth;

import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.Locale;

/** Fixed window per email: INCR a counter whose TTL starts at the first failure. */
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(prefix = "app.redis", name = "enabled", havingValue = "true")
public class RedisLoginAttemptLimiter implements LoginAttemptLimiter {

    private final StringRedisTemplate redis;

    @Override
    public void checkAllowed(String email) {
        String count = redis.opsForValue().get(key(email));
        if (count != null && Long.parseLong(count) >= MAX_FAILURES) {
            throw LoginAttemptLimiter.tooManyAttempts();
        }
    }

    @Override
    public void recordFailure(String email) {
        String key = key(email);
        Long count = redis.opsForValue().increment(key);
        if (count != null && count == 1) {
            redis.expire(key, Duration.ofMinutes(WINDOW_MINUTES));
        }
    }

    @Override
    public void reset(String email) {
        redis.delete(key(email));
    }

    private static String key(String email) {
        return "login:fail:" + email.toLowerCase(Locale.ROOT);
    }
}
