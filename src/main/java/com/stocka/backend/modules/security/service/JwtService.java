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

    /** Value of {@link #CLAIM_TOKEN_TYPE} for ordinary access tokens. */
    public static final String TYPE_ACCESS = "access";

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
