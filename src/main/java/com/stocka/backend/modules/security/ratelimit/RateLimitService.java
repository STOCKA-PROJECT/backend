package com.stocka.backend.modules.security.ratelimit;

import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Servicio in-memory que mantiene un {@link TokenBucket} por cada par
 * {@code (política, clave)} y aplica rate-limiting con el algoritmo
 * token-bucket.
 *
 * <p>El estado vive en el proceso. En despliegues multi-instancia conviene
 * apoyarse en una capa compartida (Redis); para una API mono-instancia este
 * enfoque es suficiente y evita dependencias adicionales.
 */
@Service
public class RateLimitService {

    private static final Logger log = LoggerFactory.getLogger(RateLimitService.class);

    private final Map<BucketKey, TokenBucket> buckets = new ConcurrentHashMap<>();

    /**
     * Intenta consumir un token bajo la política indicada para la clave dada.
     *
     * @param policy política aplicada
     * @param key clave del cliente (típicamente IP, email o user-id);
     *            se {@link Object#toString() normaliza} a {@link String}
     * @return {@code 0} si la petición es admitida; en otro caso, segundos que
     *         debe esperar el cliente antes de reintentar (mínimo 1)
     */
    public long tryAcquire(RateLimitPolicy policy, String key) {
        Objects.requireNonNull(policy, "policy");
        String normalizedKey = key == null || key.isBlank() ? "_unknown_" : key;
        TokenBucket bucket = buckets.computeIfAbsent(
                new BucketKey(policy.name(), normalizedKey),
                ignored -> new TokenBucket(policy.capacity(), policy.refillTokens(), policy.refillPeriod()));
        long retryAfterMillis = bucket.tryConsume(System.nanoTime());
        if (retryAfterMillis == 0L) {
            return 0L;
        }
        long retryAfterSeconds = Math.max(1L, (retryAfterMillis + 999L) / 1000L);
        log.warn("rate_limit_exceeded policy={} key={} retryAfterSeconds={}", policy.name(), normalizedKey, retryAfterSeconds);
        return retryAfterSeconds;
    }

    /**
     * Lanza {@link RateLimitedException} cuando la política se ha superado.
     *
     * @param policy política aplicada
     * @param key clave del cliente
     * @throws RateLimitedException cuando se ha agotado el cupo
     */
    public void checkOrThrow(RateLimitPolicy policy, String key) {
        long retryAfterSeconds = tryAcquire(policy, key);
        if (retryAfterSeconds > 0L) {
            throw new RateLimitedException(policy.name(), retryAfterSeconds);
        }
    }

    /**
     * Borra todos los buckets. Pensado para tests; no usar en producción.
     */
    public void reset() {
        buckets.clear();
    }

    private record BucketKey(String policy, String key) { }
}
