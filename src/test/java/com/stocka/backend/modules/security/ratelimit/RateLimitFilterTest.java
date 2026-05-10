package com.stocka.backend.modules.security.ratelimit;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.context.support.StaticMessageSource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.stocka.backend.modules.common.error.ProblemDetailFactory;

@DisplayName("RateLimitFilter")
class RateLimitFilterTest {

    private RateLimitProperties properties;
    private RateLimitService service;
    private RateLimitFilter filter;

    @BeforeEach
    void setUp() {
        properties = new RateLimitProperties();
        service = new RateLimitService();
        ProblemDetailFactory factory = new ProblemDetailFactory(new StaticMessageSource());
        ClientIpResolver ipResolver = new ClientIpResolver(properties);
        filter = new RateLimitFilter(properties, service, ipResolver, factory, new ObjectMapper());
    }

    @Test
    @DisplayName("delegates to the chain when the path is not rate-limited")
    void should_passthrough_unrelated_path() throws Exception {
        MockHttpServletRequest req = new MockHttpServletRequest("GET", "/health");
        req.setRemoteAddr("1.2.3.4");
        MockHttpServletResponse resp = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        filter.doFilter(req, resp, chain);

        assertThat(resp.getStatus()).isEqualTo(HttpStatus.OK.value());
        assertThat(chain.getRequest()).isSameAs(req);
    }

    @Test
    @DisplayName("returns 429 with Retry-After once the IP exhausts the /auth/login bucket")
    void should_return_429_after_exhausting_login_bucket() throws Exception {
        for (int i = 0; i < RateLimitFilter.AUTH_LOGIN_IP.capacity(); i++) {
            MockHttpServletResponse okResp = new MockHttpServletResponse();
            filter.doFilter(loginRequest("1.2.3.4"), okResp, new MockFilterChain());
            assertThat(okResp.getStatus()).isEqualTo(HttpStatus.OK.value());
        }

        MockHttpServletResponse limited = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();
        filter.doFilter(loginRequest("1.2.3.4"), limited, chain);

        assertThat(limited.getStatus()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS.value());
        assertThat(limited.getHeader(HttpHeaders.RETRY_AFTER)).isNotNull();
        assertThat(Long.parseLong(limited.getHeader(HttpHeaders.RETRY_AFTER))).isPositive();
        assertThat(limited.getContentType()).isEqualTo(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
        assertThat(chain.getRequest()).isNull();
    }

    @Test
    @DisplayName("isolates buckets per IP")
    void should_isolate_per_ip() throws Exception {
        for (int i = 0; i < RateLimitFilter.AUTH_LOGIN_IP.capacity(); i++) {
            filter.doFilter(loginRequest("1.2.3.4"), new MockHttpServletResponse(), new MockFilterChain());
        }
        MockHttpServletResponse otherIp = new MockHttpServletResponse();
        filter.doFilter(loginRequest("9.9.9.9"), otherIp, new MockFilterChain());

        assertThat(otherIp.getStatus()).isEqualTo(HttpStatus.OK.value());
    }

    @Test
    @DisplayName("is a no-op when stocka.rate-limit.enabled=false")
    void should_skip_when_disabled() throws Exception {
        properties.setEnabled(false);
        for (int i = 0; i < RateLimitFilter.AUTH_LOGIN_IP.capacity() + 5; i++) {
            MockHttpServletResponse resp = new MockHttpServletResponse();
            filter.doFilter(loginRequest("1.2.3.4"), resp, new MockFilterChain());
            assertThat(resp.getStatus()).isEqualTo(HttpStatus.OK.value());
        }
    }

    @Test
    @DisplayName("matches /invitations/{token}/accept")
    void should_match_invitation_accept() throws Exception {
        for (int i = 0; i < RateLimitFilter.INVITATIONS_ACCEPT_IP.capacity(); i++) {
            MockHttpServletResponse resp = new MockHttpServletResponse();
            MockHttpServletRequest req = new MockHttpServletRequest("POST", "/invitations/abc-123/accept");
            req.setRemoteAddr("1.2.3.4");
            filter.doFilter(req, resp, new MockFilterChain());
            assertThat(resp.getStatus()).isEqualTo(HttpStatus.OK.value());
        }
        MockHttpServletResponse limited = new MockHttpServletResponse();
        MockHttpServletRequest req = new MockHttpServletRequest("POST", "/invitations/abc-123/accept");
        req.setRemoteAddr("1.2.3.4");
        filter.doFilter(req, limited, new MockFilterChain());
        assertThat(limited.getStatus()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS.value());
    }

    private static MockHttpServletRequest loginRequest(String ip) {
        MockHttpServletRequest req = new MockHttpServletRequest("POST", "/auth/login");
        req.setRemoteAddr(ip);
        return req;
    }
}
