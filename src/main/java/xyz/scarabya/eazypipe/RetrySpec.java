/*
 * Copyright 2017-2026 Alessandro Patriarca.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package xyz.scarabya.eazypipe;

import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Predicate;

/**
 * Retry specification with configurable backoff multiplier, maximum delay, jitter, and exception predicate.
 *
 * @param maxRetries maximum number of retry attempts after the initial failure ({@code >= 0})
 * @param initialDelay delay before the first retry ({@code >= 0})
 * @param backoffMultiplier exponential growth factor applied between attempts ({@code >= 1.0})
 * @param maxDelay upper bound applied before jitter ({@code >= 0})
 * @param jitterFactor symmetric random jitter ratio in {@code [0.0, 1.0]}
 * @param retryOn predicate deciding whether a thrown exception is retryable
 */
public record RetrySpec(
        int maxRetries,
        Duration initialDelay,
        double backoffMultiplier,
        Duration maxDelay,
        double jitterFactor,
        Predicate<Throwable> retryOn) {

    public RetrySpec {
        if (maxRetries < 0) {
            throw new IllegalArgumentException("maxRetries must be >= 0, got " + maxRetries);
        }
        Objects.requireNonNull(initialDelay, "initialDelay must not be null");
        if (initialDelay.isNegative()) {
            throw new IllegalArgumentException("initialDelay must not be negative");
        }
        if (Double.isNaN(backoffMultiplier) || Double.isInfinite(backoffMultiplier) || backoffMultiplier < 1.0) {
            throw new IllegalArgumentException("backoffMultiplier must be finite and >= 1.0");
        }
        Objects.requireNonNull(maxDelay, "maxDelay must not be null");
        if (maxDelay.isNegative()) {
            throw new IllegalArgumentException("maxDelay must not be negative");
        }
        if (Double.isNaN(jitterFactor) || jitterFactor < 0.0 || jitterFactor > 1.0) {
            throw new IllegalArgumentException("jitterFactor must be in [0.0, 1.0]");
        }
        Objects.requireNonNull(retryOn, "retryOn must not be null");
    }

    /**
     * Returns a specification representing zero retries.
     *
     * @return no-retry {@link RetrySpec}
     */
    public static RetrySpec none() {
        return new RetrySpec(0, Duration.ZERO, 1.0, Duration.ZERO, 0.0, t -> false);
    }

    /**
     * Returns a fixed-delay retry specification without jitter.
     *
     * @param maxRetries maximum retry count
     * @param delay constant wait duration between attempts
     * @return a fixed {@link RetrySpec}
     */
    public static RetrySpec fixed(int maxRetries, Duration delay) {
        return new RetrySpec(maxRetries, delay, 1.0, delay, 0.0, t -> t instanceof Exception);
    }

    /**
     * Returns an exponential backoff retry specification (multiplier {@code 2.0}, 20% jitter, 1 minute cap).
     *
     * @param maxRetries maximum retry count
     * @param initialDelay delay before the first retry
     * @return an exponential {@link RetrySpec}
     */
    public static RetrySpec exponential(int maxRetries, Duration initialDelay) {
        Duration cap = Duration.ofMinutes(1).compareTo(initialDelay) < 0 ? initialDelay : Duration.ofMinutes(1);
        return new RetrySpec(maxRetries, initialDelay, 2.0, cap, 0.2, t -> t instanceof Exception);
    }

    /**
     * Creates a new mutable {@link Builder}.
     *
     * @return a new {@link Builder}
     */
    public static Builder builder() {
        return new Builder();
    }

    /**
     * Computes the wait duration for the given 1-based retry attempt ({@code 1 .. maxRetries}).
     *
     * @param attempt 1-based retry attempt number
     * @return delay duration including optional jitter
     */
    public Duration delayForAttempt(int attempt) {
        if (attempt < 1) {
            throw new IllegalArgumentException("attempt must be >= 1, got " + attempt);
        }
        long baseNanos = initialDelay.toNanos();
        if (baseNanos == 0L) {
            return Duration.ZERO;
        }
        double scaled = baseNanos * Math.pow(backoffMultiplier, attempt - 1);
        long capNanos = maxDelay.toNanos();
        long clamped = (scaled >= capNanos || Double.isInfinite(scaled)) ? capNanos : Math.round(scaled);
        if (jitterFactor > 0.0 && clamped > 0L) {
            double multiplier = 1.0 + ThreadLocalRandom.current().nextDouble(-jitterFactor, jitterFactor);
            clamped = Math.max(0L, Math.round(clamped * multiplier));
        }
        return Duration.ofNanos(clamped);
    }

    /**
     * Evaluates whether the given failure should trigger a retry for the specified 1-based attempt.
     */
    boolean shouldRetry(int attempt, Throwable error) {
        return attempt <= maxRetries && error instanceof Exception && retryOn.test(error);
    }

    /**
     * Fluent builder for {@link RetrySpec}.
     */
    public static final class Builder {

        private int maxRetries = 3;
        private Duration initialDelay = Duration.ofMillis(50);
        private double backoffMultiplier = 2.0;
        private Duration maxDelay = Duration.ofSeconds(30);
        private double jitterFactor = 0.2;
        private Predicate<Throwable> retryOn = t -> t instanceof Exception;

        private Builder() {
        }

        public Builder maxRetries(int maxRetries) {
            this.maxRetries = maxRetries;
            return this;
        }

        public Builder initialDelay(Duration initialDelay) {
            this.initialDelay = initialDelay;
            return this;
        }

        public Builder backoffMultiplier(double backoffMultiplier) {
            this.backoffMultiplier = backoffMultiplier;
            return this;
        }

        public Builder maxDelay(Duration maxDelay) {
            this.maxDelay = maxDelay;
            return this;
        }

        public Builder jitterFactor(double jitterFactor) {
            this.jitterFactor = jitterFactor;
            return this;
        }

        public Builder retryOn(Predicate<Throwable> retryOn) {
            this.retryOn = retryOn;
            return this;
        }

        public RetrySpec build() {
            return new RetrySpec(maxRetries, initialDelay, backoffMultiplier, maxDelay, jitterFactor, retryOn);
        }
    }
}
