package com.stocka.backend.modules.security.audit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Service;
import org.springframework.web.context.request.RequestAttributes;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import com.stocka.backend.modules.security.ratelimit.ClientIpResolver;
import com.stocka.backend.modules.users.entity.User;

import jakarta.servlet.http.HttpServletRequest;

/**
 * Façade used by every domain service that wants to record a security event.
 * Snapshots the request-scoped data (IP, User-Agent) <em>at publish time</em>
 * because the {@link SecurityAuditListener} consumes the event asynchronously
 * and the request context is gone by then.
 *
 * <p>Publishing failures are swallowed and logged so security-irrelevant code
 * paths don't fail just because the audit subsystem is misconfigured.
 */
@Service
public class SecurityAuditService {

    private static final Logger log = LoggerFactory.getLogger(SecurityAuditService.class);

    private static final int USER_AGENT_MAX_LENGTH = 512;

    private final ApplicationEventPublisher publisher;
    private final ClientIpResolver clientIpResolver;

    public SecurityAuditService(ApplicationEventPublisher publisher, ClientIpResolver clientIpResolver) {
        this.publisher = publisher;
        this.clientIpResolver = clientIpResolver;
    }

    /**
     * Records a successful event tied to an authenticated user.
     *
     * @param eventType catalog entry
     * @param user authenticated user; may be {@code null} for system events
     */
    public void recordSuccess(SecurityEventType eventType, User user) {
        record(eventType, user, user != null ? user.getEmail() : null, null, true);
    }

    /**
     * Records a successful event with extra metadata.
     *
     * @param eventType catalog entry
     * @param user authenticated user; may be {@code null}
     * @param metadata raw JSON (no validation here)
     */
    public void recordSuccess(SecurityEventType eventType, User user, String metadata) {
        record(eventType, user, user != null ? user.getEmail() : null, metadata, true);
    }

    /**
     * Records a failure. Used when the actor can't be safely tied to a user
     * (failed login, password-reset for unknown email) — pass {@code null} for
     * {@code user} and keep the {@code email} snapshot for traceability.
     *
     * @param eventType catalog entry
     * @param user resolved user, when known; otherwise {@code null}
     * @param email email the actor attempted to use
     */
    public void recordFailure(SecurityEventType eventType, User user, String email) {
        record(eventType, user, email, null, false);
    }

    /**
     * Records a failure with extra metadata.
     */
    public void recordFailure(SecurityEventType eventType, User user, String email, String metadata) {
        record(eventType, user, email, metadata, false);
    }

    private void record(SecurityEventType eventType, User user, String email, String metadata, boolean success) {
        try {
            HttpServletRequest request = currentRequest();
            String ip = request != null ? clientIpResolver.resolve(request) : null;
            String ua = request != null ? truncate(request.getHeader(HttpHeaders.USER_AGENT)) : null;
            Integer userId = user != null ? user.getId() : null;
            publisher.publishEvent(new SecurityAuditEvent(eventType, userId, email, ip, ua, metadata, success));
        } catch (RuntimeException ex) {
            log.warn("security_audit_publish_failed event={} email={}", eventType, email, ex);
        }
    }

    private static HttpServletRequest currentRequest() {
        RequestAttributes attrs = RequestContextHolder.getRequestAttributes();
        if (attrs instanceof ServletRequestAttributes servletAttrs) {
            return servletAttrs.getRequest();
        }
        return null;
    }

    private static String truncate(String value) {
        if (value == null) return null;
        return value.length() <= USER_AGENT_MAX_LENGTH ? value : value.substring(0, USER_AGENT_MAX_LENGTH);
    }
}
