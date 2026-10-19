package com.stocka.backend.modules.security.filter;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.context.SecurityContextHolder;

import com.stocka.backend.modules.security.repository.InvalidatedTokenRepository;
import com.stocka.backend.modules.security.service.AppUserDetailsService;
import com.stocka.backend.modules.security.service.JwtService;

/**
 * The filter must only react to the {@code Bearer} scheme: the belén players authenticate with
 * {@code Authorization: Device <token>} on the public heartbeat endpoint and that header must pass
 * through untouched (no 401 attribute, no JWT parsing, no principal).
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("JwtAuthenticationFilter")
class JwtAuthenticationFilterTest {

    @Mock private JwtService jwtService;
    @Mock private AppUserDetailsService userDetailsService;
    @Mock private InvalidatedTokenRepository invalidatedTokenRepository;

    @InjectMocks private JwtAuthenticationFilter filter;

    @AfterEach
    void clearContext() {
        SecurityContextHolder.clearContext();
    }

    @Nested
    @DisplayName("non-Bearer Authorization headers")
    class NonBearer {

        @ParameterizedTest
        @ValueSource(strings = {"Device bht_abc", "device bht_abc", "Basic dXNlcjpwYXNz", "Bearer", "bht_abc"})
        @DisplayName("should pass through without parsing, without a principal and without the expired flag")
        void doFilter_should_ignoreOtherSchemes(String header) throws Exception {
            MockHttpServletRequest req = new MockHttpServletRequest("POST", "/player/heartbeat");
            req.addHeader("Authorization", header);
            MockHttpServletResponse resp = new MockHttpServletResponse();
            MockFilterChain chain = new MockFilterChain();

            filter.doFilter(req, resp, chain);

            assertThat(chain.getRequest()).isSameAs(req);
            assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
            assertThat(req.getAttribute(JwtAuthenticationFilter.ATTR_TOKEN_EXPIRED)).isNull();
            verifyNoInteractions(jwtService, userDetailsService, invalidatedTokenRepository);
        }

        @Test
        @DisplayName("should pass through when there is no Authorization header at all")
        void doFilter_should_ignoreMissingHeader() throws Exception {
            MockHttpServletRequest req = new MockHttpServletRequest("GET", "/health");
            MockFilterChain chain = new MockFilterChain();

            filter.doFilter(req, new MockHttpServletResponse(), chain);

            assertThat(chain.getRequest()).isSameAs(req);
            verifyNoInteractions(jwtService, userDetailsService, invalidatedTokenRepository);
        }
    }

    @Nested
    @DisplayName("Bearer header")
    class Bearer {

        @Test
        @DisplayName("should flag an unparseable Bearer token as expired and continue unauthenticated")
        void doFilter_should_flagInvalidBearer() throws Exception {
            when(invalidatedTokenRepository.existsByTokenAndExpiresAtAfter(anyString(), any())).thenReturn(false);
            when(jwtService.extractUsername("bht_abc")).thenThrow(new IllegalArgumentException("not a jwt"));
            MockHttpServletRequest req = new MockHttpServletRequest("POST", "/player/heartbeat");
            req.addHeader("Authorization", "Bearer bht_abc");
            MockFilterChain chain = new MockFilterChain();

            filter.doFilter(req, new MockHttpServletResponse(), chain);

            assertThat(chain.getRequest()).isSameAs(req);
            assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
            assertThat(req.getAttribute(JwtAuthenticationFilter.ATTR_TOKEN_EXPIRED)).isEqualTo(Boolean.TRUE);
        }
    }
}
