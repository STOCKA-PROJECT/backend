package com.stocka.backend.modules.security.ratelimit;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

@DisplayName("ClientIpResolver")
class ClientIpResolverTest {

    @Test
    @DisplayName("returns getRemoteAddr when no trusted proxies are configured")
    void should_ignore_xff_when_no_trusted_proxies() {
        ClientIpResolver resolver = new ClientIpResolver(new RateLimitProperties());
        MockHttpServletRequest req = new MockHttpServletRequest();
        req.setRemoteAddr("10.0.0.1");
        req.addHeader("X-Forwarded-For", "203.0.113.7, 10.0.0.1");

        assertThat(resolver.resolve(req)).isEqualTo("10.0.0.1");
    }

    @Test
    @DisplayName("honors X-Forwarded-For when remoteAddr is a trusted proxy")
    void should_honor_xff_for_trusted_proxy() {
        RateLimitProperties props = new RateLimitProperties();
        props.setTrustedProxies(List.of("10.0.0.1"));
        ClientIpResolver resolver = new ClientIpResolver(props);

        MockHttpServletRequest req = new MockHttpServletRequest();
        req.setRemoteAddr("10.0.0.1");
        req.addHeader("X-Forwarded-For", "203.0.113.7, 10.0.0.1");

        assertThat(resolver.resolve(req)).isEqualTo("203.0.113.7");
    }

    @Test
    @DisplayName("ignores X-Forwarded-For from untrusted remoteAddr")
    void should_ignore_xff_for_untrusted_proxy() {
        RateLimitProperties props = new RateLimitProperties();
        props.setTrustedProxies(List.of("10.0.0.1"));
        ClientIpResolver resolver = new ClientIpResolver(props);

        MockHttpServletRequest req = new MockHttpServletRequest();
        req.setRemoteAddr("203.0.113.99");
        req.addHeader("X-Forwarded-For", "1.2.3.4");

        assertThat(resolver.resolve(req)).isEqualTo("203.0.113.99");
    }

    @Test
    @DisplayName("falls back to placeholder when remoteAddr is null")
    void should_fallback_when_remote_addr_null() {
        ClientIpResolver resolver = new ClientIpResolver(new RateLimitProperties());
        MockHttpServletRequest req = new MockHttpServletRequest();
        req.setRemoteAddr(null);

        assertThat(resolver.resolve(req)).isEqualTo("_unknown_");
    }
}
