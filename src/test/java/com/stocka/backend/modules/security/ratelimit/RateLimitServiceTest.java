package com.stocka.backend.modules.security.ratelimit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("RateLimitService")
class RateLimitServiceTest {

    private static final RateLimitPolicy POLICY =
            new RateLimitPolicy("test.policy", 2, 2, Duration.ofMinutes(1));

    private RateLimitService service;

    @BeforeEach
    void setUp() {
        service = new RateLimitService();
    }

    @Test
    @DisplayName("returns 0 while quota is available")
    void should_return_zero_while_quota_available() {
        assertThat(service.tryAcquire(POLICY, "1.2.3.4")).isZero();
        assertThat(service.tryAcquire(POLICY, "1.2.3.4")).isZero();
    }

    @Test
    @DisplayName("returns positive retry-after seconds once quota is exhausted")
    void should_return_retry_after_when_exceeded() {
        service.tryAcquire(POLICY, "1.2.3.4");
        service.tryAcquire(POLICY, "1.2.3.4");
        long retryAfter = service.tryAcquire(POLICY, "1.2.3.4");
        assertThat(retryAfter).isPositive();
    }

    @Test
    @DisplayName("isolates buckets per key")
    void should_isolate_buckets_per_key() {
        service.tryAcquire(POLICY, "1.2.3.4");
        service.tryAcquire(POLICY, "1.2.3.4");
        assertThat(service.tryAcquire(POLICY, "1.2.3.4")).isPositive();
        assertThat(service.tryAcquire(POLICY, "5.6.7.8")).isZero();
    }

    @Test
    @DisplayName("isolates buckets per policy name")
    void should_isolate_buckets_per_policy() {
        RateLimitPolicy other = new RateLimitPolicy("other.policy", 2, 2, Duration.ofMinutes(1));
        service.tryAcquire(POLICY, "1.2.3.4");
        service.tryAcquire(POLICY, "1.2.3.4");
        assertThat(service.tryAcquire(POLICY, "1.2.3.4")).isPositive();
        assertThat(service.tryAcquire(other, "1.2.3.4")).isZero();
    }

    @Test
    @DisplayName("checkOrThrow throws RateLimitedException with retry-after seconds populated")
    void should_throw_when_exceeded() {
        service.checkOrThrow(POLICY, "1.2.3.4");
        service.checkOrThrow(POLICY, "1.2.3.4");

        assertThatThrownBy(() -> service.checkOrThrow(POLICY, "1.2.3.4"))
                .isInstanceOf(RateLimitedException.class)
                .satisfies(ex -> {
                    RateLimitedException rle = (RateLimitedException) ex;
                    assertThat(rle.getRetryAfterSeconds()).isPositive();
                });
    }

    @Test
    @DisplayName("treats null/blank key as the same anonymous bucket")
    void should_normalize_blank_key() {
        assertThat(service.tryAcquire(POLICY, null)).isZero();
        assertThat(service.tryAcquire(POLICY, "  ")).isZero();
        assertThat(service.tryAcquire(POLICY, "")).isPositive();
    }

    @Test
    @DisplayName("reset() clears all buckets")
    void should_reset_buckets() {
        service.tryAcquire(POLICY, "1.2.3.4");
        service.tryAcquire(POLICY, "1.2.3.4");
        assertThat(service.tryAcquire(POLICY, "1.2.3.4")).isPositive();

        service.reset();

        assertThat(service.tryAcquire(POLICY, "1.2.3.4")).isZero();
    }
}
