package com.stocka.backend.modules.security.service;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import jakarta.annotation.PostConstruct;
import java.time.LocalDateTime;
import java.util.Date;
import java.util.HashMap;
import java.util.Map;
import java.util.function.Function;
import javax.crypto.SecretKey;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.stereotype.Service;

/**
 * Issues and parses signed access tokens (HS256). Access tokens are short-lived
 * (15 minutes by default) and carry two claims on top of the standard set:
 * <ul>
 *   <li>{@code tokenType=access} — distinguishes access tokens from future
 *       short-lived intermediate tokens (e.g. the 2FA challenge token).</li>
 *   <li>{@code tokenVersion} — bumping the configured value invalidates every
 *       previously-issued token, enabling a global forced-logout if needed.</li>
 * </ul>
 */
@Service
public class JwtService {

    /** Claim name carrying the token type ({@code access}, {@code mfa_challenge}, …). */
    public static final String CLAIM_TOKEN_TYPE = "tokenType";

    /** Claim name carrying the global token version. */
    public static final String CLAIM_TOKEN_VERSION = "tokenVersion";

    /**
     * Claim name carrying the refresh-token family the access token was minted
     * from. Used by {@code GET /users/me/sessions} to flag the current session
     * without having to read the (path-restricted) refresh cookie.
     */
    public static final String CLAIM_FAMILY_ID = "familyId";

    /** Value of {@link #CLAIM_TOKEN_TYPE} for ordinary access tokens. */
    public static final String TYPE_ACCESS = "access";

    /**
     * Short-lived intermediate token issued by {@code /auth/login} when the
     * account has 2FA enabled. Carries enough state (subject = email) for
     * {@code /auth/login/2fa} to finish the authentication without making the
     * client resend the password.
     */
    public static final String TYPE_MFA_CHALLENGE = "mfa_challenge";

    /**
     * Short-lived single-purpose token used to hand off an authenticated session to a separate
     * front-end app (the Timeline Editor) opened in a new window. The main app mints it via
     * {@code POST /auth/handoff} and the editor's BFF exchanges it for a full session via
     * {@code POST /auth/handoff/exchange}. TTL is intentionally tiny.
     */
    public static final String TYPE_HANDOFF = "handoff";

    private static final int MIN_HEX_LENGTH = 64;

    @Value("${security.jwt.secret-key}")
    private String secretKey;

    @Value("${security.jwt.expiration-time:900000}")
    private long jwtExpiration;

    @Value("${security.jwt.token-version:1}")
    private int currentTokenVersion;

    @PostConstruct
    void validateConfiguration() {
        if (secretKey == null || secretKey.isBlank()) {
            throw new IllegalStateException(
                    "security.jwt.secret-key is required. Set the JWT_SECRET_KEY environment variable "
                            + "to a hex string of at least 256 bits (generate with: openssl rand -hex 32).");
        }
        if (secretKey.length() < MIN_HEX_LENGTH) {
            throw new IllegalStateException(
                    "security.jwt.secret-key must be at least " + MIN_HEX_LENGTH
                            + " hex characters (256 bits); got " + secretKey.length() + ".");
        }
        if (secretKey.length() % 2 != 0 || !secretKey.matches("\\p{XDigit}+")) {
            throw new IllegalStateException(
                    "security.jwt.secret-key must be a valid hexadecimal string.");
        }
    }

    public String extractUsername(String token) {
        return extractClaim(token, Claims::getSubject);
    }

    /**
     * Returns the {@code tokenType} claim or {@code null} when absent. Tokens
     * minted before this claim was introduced therefore read as {@code null}
     * and are rejected by the JWT filter.
     *
     * @param token signed JWT
     * @return the token type claim, or {@code null}
     */
    public String extractTokenType(String token) {
        return extractClaim(token, c -> c.get(CLAIM_TOKEN_TYPE, String.class));
    }

    /**
     * Returns the {@code tokenVersion} claim or {@code null} when absent.
     * Tokens whose claim does not match {@link #getCurrentTokenVersion()} are
     * rejected — bumping {@code security.jwt.token-version} therefore logs out
     * everyone.
     *
     * @param token signed JWT
     * @return the token version claim, or {@code null}
     */
    public Integer extractTokenVersion(String token) {
        return extractClaim(token, c -> c.get(CLAIM_TOKEN_VERSION, Integer.class));
    }

    /**
     * Returns the {@code familyId} claim or {@code null} when absent.
     *
     * @param token signed JWT
     * @return refresh-token family id the access token was minted from
     */
    public String extractFamilyId(String token) {
        return extractClaim(token, c -> c.get(CLAIM_FAMILY_ID, String.class));
    }

    public String generateToken(UserDetails userDetails) {
        return generateToken(new HashMap<>(), userDetails);
    }

    public String generateToken(Map<String, Object> extraClaims, UserDetails userDetails) {
        Map<String, Object> claims = new HashMap<>(extraClaims);
        claims.putIfAbsent(CLAIM_TOKEN_TYPE, TYPE_ACCESS);
        claims.putIfAbsent(CLAIM_TOKEN_VERSION, currentTokenVersion);
        return Jwts.builder()
                .claims(claims)
                .subject(userDetails.getUsername())
                .issuedAt(new Date(System.currentTimeMillis()))
                .expiration(new Date(System.currentTimeMillis() + jwtExpiration))
                .signWith(getSignInKey())
                .compact();
    }

    /**
     * Builds a short-lived {@link #TYPE_MFA_CHALLENGE} token used between the
     * password step and the 2FA step. The TTL is intentionally short — long
     * enough for the user to fetch the code from their phone but not long
     * enough to bypass password rate-limits on retry.
     *
     * @param email user being authenticated
     * @param ttlSeconds lifetime
     * @return signed JWT
     */
    public String generateMfaChallengeToken(String email, long ttlSeconds) {
        long now = System.currentTimeMillis();
        return Jwts.builder()
                .claims(Map.of(CLAIM_TOKEN_TYPE, TYPE_MFA_CHALLENGE,
                        CLAIM_TOKEN_VERSION, currentTokenVersion))
                .subject(email)
                .issuedAt(new Date(now))
                .expiration(new Date(now + ttlSeconds * 1000L))
                .signWith(getSignInKey())
                .compact();
    }

    /**
     * Validates a {@link #TYPE_MFA_CHALLENGE} token's signature, expiration
     * and type. Subject (the email) is exposed via {@link #extractUsername}.
     *
     * @param token signed JWT
     * @return {@code true} when the token can be trusted as an MFA challenge
     */
    public boolean isMfaChallengeValid(String token) {
        try {
            if (extractExpiration(token).before(new Date())) return false;
            if (!TYPE_MFA_CHALLENGE.equals(extractTokenType(token))) return false;
            Integer version = extractTokenVersion(token);
            return version != null && version == currentTokenVersion;
        } catch (RuntimeException ignored) {
            return false;
        }
    }

    /**
     * Builds a short-lived {@link #TYPE_HANDOFF} token carrying the user's email as subject. Used to
     * pass an authenticated session to a separate front-end app opened in a new window.
     *
     * @param email      user being handed off
     * @param ttlSeconds lifetime (kept very short)
     * @return signed JWT
     */
    public String generateHandoffToken(String email, long ttlSeconds) {
        long now = System.currentTimeMillis();
        return Jwts.builder()
                .claims(Map.of(CLAIM_TOKEN_TYPE, TYPE_HANDOFF,
                        CLAIM_TOKEN_VERSION, currentTokenVersion))
                .subject(email)
                .issuedAt(new Date(now))
                .expiration(new Date(now + ttlSeconds * 1000L))
                .signWith(getSignInKey())
                .compact();
    }

    /**
     * Validates a {@link #TYPE_HANDOFF} token's signature, expiration and type. Subject (the email)
     * is exposed via {@link #extractUsername}.
     *
     * @param token signed JWT
     * @return {@code true} when the token can be trusted as a handoff ticket
     */
    public boolean isHandoffValid(String token) {
        try {
            if (extractExpiration(token).before(new Date())) return false;
            if (!TYPE_HANDOFF.equals(extractTokenType(token))) return false;
            Integer version = extractTokenVersion(token);
            return version != null && version == currentTokenVersion;
        } catch (RuntimeException ignored) {
            return false;
        }
    }

    /**
     * Validates signature, expiration, subject, token type and token version.
     *
     * @param token       signed JWT
     * @param userDetails authenticated principal
     * @return {@code true} when the token can be trusted as an access token
     */
    public boolean isTokenValid(String token, UserDetails userDetails) {
        String username = extractUsername(token);
        if (username == null || !username.equals(userDetails.getUsername())) {
            return false;
        }
        if (isTokenExpired(token)) {
            return false;
        }
        if (!TYPE_ACCESS.equals(extractTokenType(token))) {
            return false;
        }
        Integer version = extractTokenVersion(token);
        return version != null && version == currentTokenVersion;
    }

    public long getExpirationTime() {
        return jwtExpiration;
    }

    public int getCurrentTokenVersion() {
        return currentTokenVersion;
    }

    private boolean isTokenExpired(String token) {
        return extractExpiration(token).before(new Date());
    }

    public LocalDateTime extractExpirationAsLocalDateTime(String token) {
        Date expiration = extractExpiration(token);
        return expiration.toInstant().atZone(java.time.ZoneId.systemDefault()).toLocalDateTime();
    }

    public LocalDateTime extractIssuedAtAsLocalDateTime(String token) {
        Date issuedAt = extractClaim(token, Claims::getIssuedAt);
        return issuedAt.toInstant().atZone(java.time.ZoneId.systemDefault()).toLocalDateTime();
    }

    private Date extractExpiration(String token) {
        return extractClaim(token, Claims::getExpiration);
    }

    private <T> T extractClaim(String token, Function<Claims, T> claimsResolver) {
        final Claims claims = extractAllClaims(token);
        return claimsResolver.apply(claims);
    }

    private Claims extractAllClaims(String token) {
        return Jwts.parser()
                .verifyWith(getSignInKey())
                .build()
                .parseSignedClaims(token)
                .getPayload();
    }

    private SecretKey getSignInKey() {
        byte[] keyBytes = hexToBytes(secretKey);
        return Keys.hmacShaKeyFor(keyBytes);
    }

    private byte[] hexToBytes(String hex) {
        if (hex.length() % 2 != 0) {
            throw new IllegalArgumentException("La clave JWT en hexadecimal no es valida");
        }

        byte[] bytes = new byte[hex.length() / 2];
        for (int i = 0; i < hex.length(); i += 2) {
            bytes[i / 2] = (byte) Integer.parseInt(hex.substring(i, i + 2), 16);
        }
        return bytes;
    }
}
