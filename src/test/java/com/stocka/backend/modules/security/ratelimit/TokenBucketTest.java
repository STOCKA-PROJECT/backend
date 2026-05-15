package com.stocka.backend.modules.security.ratelimit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import java.time.Duration;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

@DisplayName("TokenBucket")
class TokenBucketTest {

    @Nested
    @DisplayName("validation")
    class Validation {

        @Test
        @DisplayName("throws when capacity is non-positive")
        void should_reject_non_positive_capacity() {
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> new TokenBucket(0, 1, Duration.ofMinutes(1)));
        }

        @Test
        @DisplayName("throws when refillTokens is non-positive")
        void should_reject_non_positive_refill_tokens() {
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> new TokenBucket(1, 0, Duration.ofMinutes(1)));
        }

        @Test
        @DisplayName("throws when refillPeriod is null or non-positive")
        void should_reject_invalid_period() {
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> new TokenBucket(1, 1, null));
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> new TokenBucket(1, 1, Duration.ZERO));
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> new TokenBucket(1, 1, Duration.ofMinutes(-1)));
        }
    }

    @Nested
    @DisplayName("tryConsume")
    class TryConsume {

        @Test
        @DisplayName("allows up to capacity calls without elapsed time, then rate-limits")
        void should_allow_until_capacity() {
            long t0 = 1_000_000_000L;
            TokenBucket bucket = new TokenBucket(3, 3, Duration.ofMinutes(1), t0);

            assertThat(bucket.tryConsume(t0)).isZero();
            assertThat(bucket.tryConsume(t0)).isZero();
            assertThat(bucket.tryConsume(t0)).isZero();
            assertThat(bucket.tryConsume(t0)).isPositive();
        }

        @Test
        @DisplayName("returns retry-after milliseconds when bucket is empty")
        void should_return_retry_after_millis_when_empty() {
            long t0 = 5_000_000_000L;
            TokenBucket bucket = new TokenBucket(1, 1, Duration.ofSeconds(60), t0);

            assertThat(bucket.tryConsume(t0)).isZero();
            long retryAfterMs = bucket.tryConsume(t0);
            assertThat(retryAfterMs).isBetween(59_000L, 60_001L);
        }

        @Test
        @DisplayName("refills proportionally to elapsed time")
        void should_refill_after_elapsed_time() {
            long t0 = 0L;
            TokenBucket bucket = new TokenBucket(2, 2, Duration.ofSeconds(1), t0);

            assertThat(bucket.tryConsume(t0)).isZero();
            assertThat(bucket.tryConsume(t0)).isZero();
            assertThat(bucket.tryConsume(t0)).isPositive();

            long oneSecLater = t0 + Duration.ofSeconds(1).toNanos();
            assertThat(bucket.tryConsume(oneSecLater)).isZero();
            assertThat(bucket.tryConsume(oneSecLater)).isZero();
        }

        @Test
        @DisplayName("never overflows beyond capacity")
        void should_cap_refill_at_capacity() {
            long t0 = 0L;
            TokenBucket bucket = new TokenBucket(2, 2, Duration.ofSeconds(1), t0);

            // Sit idle for an hour, then drain — should still cap at 2 tokens.
            long oneHourLater = t0 + Duration.ofHours(1).toNanos();
            assertThat(bucket.tryConsume(oneHourLater)).isZero();
            assertThat(bucket.tryConsume(oneHourLater)).isZero();
            assertThat(bucket.tryConsume(oneHourLater)).isPositive();
        }
    }
}
