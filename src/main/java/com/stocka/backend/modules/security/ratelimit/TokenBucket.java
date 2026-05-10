package com.stocka.backend.modules.security.ratelimit;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Bucket de tokens (token-bucket algorithm) usado por {@link RateLimitService}.
 *
 * <p>El bucket se rellena de forma continua a razón de
 * {@code refillTokens} cada {@code refillPeriod}. Cada llamada a
 * {@link #tryConsume(long)} consume un token cuando hay disponibles y, en caso
 * contrario, devuelve los milisegundos que el cliente debe esperar antes de
 * volver a intentarlo.
 *
 * <p>Es thread-safe: la actualización del estado se hace con un
 * {@link AtomicReference} y un {@code compareAndSet} para evitar bloqueos.
 */
final class TokenBucket {

    private final long capacity;
    private final long refillTokens;
    private final long refillPeriodNanos;
    private final AtomicReference<State> state;

    /**
     * Crea un bucket lleno con la capacidad indicada.
     *
     * @param capacity número máximo de tokens; debe ser positivo
     * @param refillTokens tokens que se reponen por cada {@code refillPeriod}; debe ser positivo
     * @param refillPeriod periodo de reposición; debe ser positivo
     */
    TokenBucket(long capacity, long refillTokens, Duration refillPeriod) {
        this(capacity, refillTokens, refillPeriod, System.nanoTime());
    }

    /**
     * Variante de test que permite inyectar el {@code nanoTime} inicial para
     * simular el reloj con valores deterministas.
     */
    TokenBucket(long capacity, long refillTokens, Duration refillPeriod, long initialNanos) {
        if (capacity <= 0 || refillTokens <= 0 || refillPeriod == null || refillPeriod.isZero() || refillPeriod.isNegative()) {
            throw new IllegalArgumentException("capacity, refillTokens and refillPeriod must be positive");
        }
        this.capacity = capacity;
        this.refillTokens = refillTokens;
        this.refillPeriodNanos = refillPeriod.toNanos();
        this.state = new AtomicReference<>(new State(capacity, initialNanos));
    }

    /**
     * Intenta consumir un token.
     *
     * @param nowNanos instante actual en nanosegundos ({@link System#nanoTime()})
     * @return {@code 0} si se concedió el token; en otro caso, milisegundos
     *         hasta que vuelva a haber al menos un token disponible
     */
    long tryConsume(long nowNanos) {
        while (true) {
            State current = state.get();
            long elapsed = nowNanos - current.lastRefillNanos();
            long replenished = elapsed > 0 ? (elapsed / refillPeriodNanos) * refillTokens : 0L;
            long carryNanos = elapsed > 0 ? elapsed % refillPeriodNanos : 0L;
            long newTokens = Math.min(capacity, current.tokens() + replenished);
            long newLastRefill = replenished > 0 ? nowNanos - carryNanos : current.lastRefillNanos();

            if (newTokens >= 1) {
                State next = new State(newTokens - 1, newLastRefill);
                if (state.compareAndSet(current, next)) {
                    return 0L;
                }
            } else {
                long missingNanos = refillPeriodNanos - (nowNanos - newLastRefill);
                long retryAfterMillis = Math.max(1L, (missingNanos + 999_999L) / 1_000_000L);
                State next = new State(newTokens, newLastRefill);
                if (state.compareAndSet(current, next)) {
                    return retryAfterMillis;
                }
            }
        }
    }

    private record State(long tokens, long lastRefillNanos) { }
}
