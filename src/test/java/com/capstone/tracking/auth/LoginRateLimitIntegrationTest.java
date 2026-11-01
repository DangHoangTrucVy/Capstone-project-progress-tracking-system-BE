package com.capstone.tracking.auth;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.assertThatCode;
import com.capstone.tracking.common.exception.ApiException;
import org.junit.jupiter.api.Test;

/** The reusable limiter still enforces its window; password authentication itself is permanently disabled. */
class LoginRateLimitIntegrationTest {
    @Test
    void failuresReachTheLimitAndResetClearsIt() {
        LoginAttemptLimiter limiter = new InMemoryLoginAttemptLimiter();
        String email = "limit@example.com";
        for (int i = 0; i < LoginAttemptLimiter.MAX_FAILURES; i++) {
            limiter.recordFailure(email);
        }
        assertThatThrownBy(() -> limiter.checkAllowed(email)).isInstanceOf(ApiException.class);
        limiter.reset(email);
        assertThatCode(() -> limiter.checkAllowed(email)).doesNotThrowAnyException();
    }
}
