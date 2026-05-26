package com.stocka.backend.modules.security.error;

import java.io.IOException;

import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.stereotype.Component;

import com.stocka.backend.modules.common.error.ErrorCodes;
import com.stocka.backend.modules.common.error.ProblemDetailFactory;
import com.stocka.backend.modules.security.filter.JwtAuthenticationFilter;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import tools.jackson.databind.ObjectMapper;

/**
 * Punto de entrada de autenticación que devuelve un {@link ProblemDetail} JSON
 * con {@code code: auth.unauthenticated} en lugar de un 401 con cuerpo vacío.
 *
 * <p>Cuando el {@link JwtAuthenticationFilter} marca el request con
 * {@link JwtAuthenticationFilter#ATTR_TOKEN_EXPIRED} se devuelve
 * {@code auth.token_expired} para que el frontend dispare {@code /auth/refresh}.
 *
 * <p>Usa el {@link ObjectMapper} gestionado por Spring (con el mixin de
 * {@link ProblemDetail} registrado) para que las {@code properties} se
 * aplanen al nivel raíz del JSON.
 */
@Component
public class JsonAuthenticationEntryPoint implements AuthenticationEntryPoint {

    private final ProblemDetailFactory factory;
    private final ObjectMapper objectMapper;

    public JsonAuthenticationEntryPoint(ProblemDetailFactory factory, ObjectMapper objectMapper) {
        this.factory = factory;
        this.objectMapper = objectMapper;
    }

    @Override
    public void commence(
            HttpServletRequest request,
            HttpServletResponse response,
            AuthenticationException authException) throws IOException {
        String code = Boolean.TRUE.equals(request.getAttribute(JwtAuthenticationFilter.ATTR_TOKEN_EXPIRED))
                ? ErrorCodes.AUTH_TOKEN_EXPIRED
                : ErrorCodes.AUTH_UNAUTHENTICATED;
        ProblemDetail body = factory.build(
                HttpStatus.UNAUTHORIZED,
                code,
                null,
                request.getRequestURI());
        response.setStatus(HttpStatus.UNAUTHORIZED.value());
        response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
        objectMapper.writeValue(response.getWriter(), body);
    }
}
