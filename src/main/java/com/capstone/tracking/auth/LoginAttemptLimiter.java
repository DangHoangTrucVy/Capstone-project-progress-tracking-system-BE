package com.capstone.tracking.auth;

import com.capstone.tracking.common.exception.ApiException;
import org.springframework.http.HttpStatus;

/**
 * Brute-force guard for {@code POST /auth/login}: after {@link #MAX_FAILURES} wrong passwords for one email within
 * {@link #WINDOW_MINUTES} minutes, further attempts get 429 until the window expires. A successful login resets it.
 * Backed by Redis when {@code app.redis.enabled=true} (shared by every instance), otherwise in memory.
 */
public interface LoginAttemptLimiter {

    int MAX_FAILURES = 5;
    long WINDOW_MINUTES = 15;

    /** Throws 429 when the email is currently locked out. */
    void checkAllowed(String email);

    void recordFailure(String email);

    void reset(String email);

    static ApiException tooManyAttempts() {
        return new ApiException(HttpStatus.TOO_MANY_REQUESTS, "TOO_MANY_LOGIN_ATTEMPTS",
                "Too many failed login attempts. Try again in " + WINDOW_MINUTES + " minutes");
    }
}
