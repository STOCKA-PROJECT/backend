package com.stocka.backend.modules.security.filter;

import com.stocka.backend.modules.security.repository.InvalidatedTokenRepository;
import com.stocka.backend.modules.security.service.AppUserDetailsService;
import com.stocka.backend.modules.security.service.JwtService;
import com.stocka.backend.modules.users.entity.User;
import java.time.LocalDateTime;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.web.authentication.WebAuthenticationDetailsSource;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

@Component
public class JwtAuthenticationFilter extends OncePerRequestFilter {

    /**
     * Request attribute set when the request carried a Bearer token that failed
     * validation (expired, wrong version, wrong type, …). Read by
     * {@link com.stocka.backend.modules.security.error.JsonAuthenticationEntryPoint}
     * to surface {@code auth.token_expired} instead of {@code auth.unauthenticated}
     * so the frontend can trigger a refresh.
     */
    public static final String ATTR_TOKEN_EXPIRED = "stocka.auth.token_expired";

    private final JwtService jwtService;
    private final AppUserDetailsService userDetailsService;
    private final InvalidatedTokenRepository invalidatedTokenRepository;

    public JwtAuthenticationFilter(
            JwtService jwtService,
            AppUserDetailsService userDetailsService,
            InvalidatedTokenRepository invalidatedTokenRepository
    ) {
        this.jwtService = jwtService;
        this.userDetailsService = userDetailsService;
        this.invalidatedTokenRepository = invalidatedTokenRepository;
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain filterChain
    ) throws ServletException, IOException {
        final String authHeader = request.getHeader("Authorization");

        if (authHeader == null || !authHeader.startsWith("Bearer ")) {
            filterChain.doFilter(request, response);
            return;
        }

        try {
            String jwt = authHeader.substring(7);

            if (invalidatedTokenRepository.existsByTokenAndExpiresAtAfter(jwt, LocalDateTime.now())) {
                request.setAttribute(ATTR_TOKEN_EXPIRED, Boolean.TRUE);
                filterChain.doFilter(request, response);
                return;
            }

            String userEmail = jwtService.extractUsername(jwt);

            if (userEmail != null && SecurityContextHolder.getContext().getAuthentication() == null) {
                UserDetails userDetails = userDetailsService.loadUserByUsername(userEmail);

                if (jwtService.isTokenValid(jwt, userDetails) && isTokenIssuedAfterPasswordChange(jwt, userDetails)) {
                    UsernamePasswordAuthenticationToken authToken =
                            new UsernamePasswordAuthenticationToken(
                                    userDetails,
                                    null,
                                    userDetails.getAuthorities()
                            );
                    authToken.setDetails(new WebAuthenticationDetailsSource().buildDetails(request));
                    SecurityContextHolder.getContext().setAuthentication(authToken);
                } else {
                    request.setAttribute(ATTR_TOKEN_EXPIRED, Boolean.TRUE);
                }
            }
        } catch (RuntimeException ignored) {
            // Any parsing/validation failure leaves the request unauthenticated. The
            // authentication entry point then returns 401 with code auth.token_expired
            // (or auth.unauthenticated for tokens that never were valid).
            request.setAttribute(ATTR_TOKEN_EXPIRED, Boolean.TRUE);
            SecurityContextHolder.clearContext();
        }

        filterChain.doFilter(request, response);
    }

    private boolean isTokenIssuedAfterPasswordChange(String jwt, UserDetails userDetails) {
        if (!(userDetails instanceof User user) || user.getPasswordChangedAt() == null) {
            return true;
        }
        LocalDateTime issuedAt = jwtService.extractIssuedAtAsLocalDateTime(jwt);
        return !issuedAt.isBefore(user.getPasswordChangedAt());
    }
}
