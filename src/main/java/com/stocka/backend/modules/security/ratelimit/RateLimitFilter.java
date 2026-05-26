package com.stocka.backend.modules.security.ratelimit;

import java.io.IOException;
import java.time.Duration;
import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.stereotype.Component;
import org.springframework.util.AntPathMatcher;
import org.springframework.web.filter.OncePerRequestFilter;

import com.stocka.backend.modules.common.error.ErrorCodes;
import com.stocka.backend.modules.common.error.ProblemDetailFactory;

import tools.jackson.databind.ObjectMapper;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/**
 * Aplica políticas de rate-limit por IP a los endpoints sensibles antes de que
 * lleguen al {@link com.stocka.backend.modules.security.filter.JwtAuthenticationFilter}.
 *
 * <p>Cuando un cliente excede el cupo se devuelve {@code 429 Too Many Requests}
 * con cabecera {@code Retry-After} y un cuerpo {@link ProblemDetail} consistente
 * con el resto de errores del API.
 *
 * <p>El filtro se desactiva configurando {@code stocka.rate-limit.enabled=false}
 * (típicamente en tests).
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 10)
public class RateLimitFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(RateLimitFilter.class);
    private static final AntPathMatcher MATCHER = new AntPathMatcher();

    /** {@code POST /auth/login}: 5 intentos/min por IP. */
    static final RateLimitPolicy AUTH_LOGIN_IP =
            new RateLimitPolicy("auth.login.ip", 5, 5, Duration.ofMinutes(1));
    /** {@code POST /auth/refresh}: 30/min por IP — varias pestañas pueden refrescar a la vez. */
    static final RateLimitPolicy AUTH_REFRESH_IP =
            new RateLimitPolicy("auth.refresh.ip", 30, 30, Duration.ofMinutes(1));
    /** {@code POST /auth/login/2fa}: 5/min por IP — frena fuerza bruta sobre el código de 6 dígitos. */
    static final RateLimitPolicy AUTH_2FA_LOGIN_IP =
            new RateLimitPolicy("auth.2fa_login.ip", 5, 5, Duration.ofMinutes(1));
    /** {@code GET /auth/oauth/google/authorize}: 30/min por IP. */
    static final RateLimitPolicy OAUTH_AUTHORIZE_IP =
            new RateLimitPolicy("auth.oauth_authorize.ip", 30, 30, Duration.ofMinutes(1));
    /** {@code POST /auth/oauth/google/callback}: 10/min por IP. */
    static final RateLimitPolicy OAUTH_CALLBACK_IP =
            new RateLimitPolicy("auth.oauth_callback.ip", 10, 10, Duration.ofMinutes(1));
    /** {@code POST /auth/signup}: 3 registros/h por IP. */
    static final RateLimitPolicy AUTH_SIGNUP_IP =
            new RateLimitPolicy("auth.signup.ip", 3, 3, Duration.ofHours(1));
    /** {@code POST /auth/forgot-password}: 5/h por IP. */
    static final RateLimitPolicy AUTH_FORGOT_IP =
            new RateLimitPolicy("auth.forgot_password.ip", 5, 5, Duration.ofHours(1));
    /** {@code POST /auth/reset-password}: 10/h por IP. */
    static final RateLimitPolicy AUTH_RESET_IP =
            new RateLimitPolicy("auth.reset_password.ip", 10, 10, Duration.ofHours(1));
    /** {@code POST /auth/verify-email}: 30/h por IP. */
    static final RateLimitPolicy AUTH_VERIFY_IP =
            new RateLimitPolicy("auth.verify_email.ip", 30, 30, Duration.ofHours(1));
    /** {@code POST /auth/resend-verification}: 5/h por IP. */
    static final RateLimitPolicy AUTH_RESEND_IP =
            new RateLimitPolicy("auth.resend_verification.ip", 5, 5, Duration.ofHours(1));
    /** {@code GET /auth/check-username}: 30/min por IP. */
    static final RateLimitPolicy AUTH_CHECK_USERNAME_IP =
            new RateLimitPolicy("auth.check_username.ip", 30, 30, Duration.ofMinutes(1));
    /** {@code POST /invitations/&#42;/accept}: 10/min por IP. */
    static final RateLimitPolicy INVITATIONS_ACCEPT_IP =
            new RateLimitPolicy("invitations.accept.ip", 10, 10, Duration.ofMinutes(1));
    /** {@code POST /webhooks/resend}: 60/min por IP (defensa en profundidad junto a la firma Svix). */
    static final RateLimitPolicy WEBHOOKS_RESEND_IP =
            new RateLimitPolicy("webhooks.resend.ip", 60, 60, Duration.ofMinutes(1));

    private final RateLimitProperties properties;
    private final RateLimitService rateLimitService;
    private final ClientIpResolver clientIpResolver;
    private final ProblemDetailFactory problemDetailFactory;
    private final ObjectMapper objectMapper;

    public RateLimitFilter(
            RateLimitProperties properties,
            RateLimitService rateLimitService,
            ClientIpResolver clientIpResolver,
            ProblemDetailFactory problemDetailFactory,
            ObjectMapper objectMapper) {
        this.properties = properties;
        this.rateLimitService = rateLimitService;
        this.clientIpResolver = clientIpResolver;
        this.problemDetailFactory = problemDetailFactory;
        this.objectMapper = objectMapper;
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain filterChain) throws ServletException, IOException {

        if (!properties.isEnabled()) {
            filterChain.doFilter(request, response);
            return;
        }

        RateLimitPolicy policy = resolvePolicy(request.getMethod(), request.getRequestURI());
        if (policy == null) {
            filterChain.doFilter(request, response);
            return;
        }

        String ip = clientIpResolver.resolve(request);
        long retryAfterSeconds = rateLimitService.tryAcquire(policy, ip);
        if (retryAfterSeconds == 0L) {
            filterChain.doFilter(request, response);
            return;
        }

        write429(request, response, policy, retryAfterSeconds);
    }

    private RateLimitPolicy resolvePolicy(String method, String path) {
        if (path == null) {
            return null;
        }
        for (Mapping mapping : MAPPINGS) {
            if (mapping.method.equalsIgnoreCase(method) && MATCHER.match(mapping.pattern, path)) {
                return mapping.policy;
            }
        }
        return null;
    }

    private void write429(
            HttpServletRequest request,
            HttpServletResponse response,
            RateLimitPolicy policy,
            long retryAfterSeconds) throws IOException {

        ProblemDetail body = problemDetailFactory.build(
                HttpStatus.TOO_MANY_REQUESTS,
                ErrorCodes.REQUEST_RATE_LIMITED,
                Map.of(
                        "policy", policy.name(),
                        "retryAfter", retryAfterSeconds),
                request.getRequestURI());

        response.setStatus(HttpStatus.TOO_MANY_REQUESTS.value());
        response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
        response.setHeader(HttpHeaders.RETRY_AFTER, Long.toString(retryAfterSeconds));
        objectMapper.writeValue(response.getOutputStream(), body);
        log.info("rate_limited path={} method={} policy={} retryAfter={}",
                request.getRequestURI(), request.getMethod(), policy.name(), retryAfterSeconds);
    }

    private record Mapping(String method, String pattern, RateLimitPolicy policy) { }

    private static final List<Mapping> MAPPINGS = List.of(
            new Mapping("POST", "/auth/login",                 AUTH_LOGIN_IP),
            new Mapping("POST", "/auth/refresh",               AUTH_REFRESH_IP),
            new Mapping("POST", "/auth/login/2fa",             AUTH_2FA_LOGIN_IP),
            new Mapping("GET",  "/auth/oauth/google/authorize", OAUTH_AUTHORIZE_IP),
            new Mapping("POST", "/auth/oauth/google/callback",  OAUTH_CALLBACK_IP),
            new Mapping("POST", "/auth/signup",                AUTH_SIGNUP_IP),
            new Mapping("POST", "/auth/forgot-password",       AUTH_FORGOT_IP),
            new Mapping("POST", "/auth/reset-password",        AUTH_RESET_IP),
            new Mapping("POST", "/auth/verify-email",          AUTH_VERIFY_IP),
            new Mapping("POST", "/auth/resend-verification",   AUTH_RESEND_IP),
            new Mapping("GET",  "/auth/check-username",        AUTH_CHECK_USERNAME_IP),
            new Mapping("POST", "/invitations/*/accept",       INVITATIONS_ACCEPT_IP),
            new Mapping("POST", "/webhooks/resend",            WEBHOOKS_RESEND_IP)
    );
}
