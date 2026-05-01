package com.stocka.backend.modules.security.error;

import java.io.IOException;

import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.stereotype.Component;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.stocka.backend.modules.common.error.ErrorCodes;
import com.stocka.backend.modules.common.error.ProblemDetailFactory;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/**
 * Punto de entrada de autenticación que devuelve un {@link ProblemDetail} JSON
 * con {@code code: auth.unauthenticated} en lugar de un 401 con cuerpo vacío.
 *
 * <p>Se usa para 401 generados por Spring Security antes de que el
 * {@code @RestControllerAdvice} entre en juego (token ausente, malformado o
 * expirado).
 */
@Component
public class JsonAuthenticationEntryPoint implements AuthenticationEntryPoint {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    private final ProblemDetailFactory factory;

    public JsonAuthenticationEntryPoint(ProblemDetailFactory factory) {
        this.factory = factory;
    }

    @Override
    public void commence(
            HttpServletRequest request,
            HttpServletResponse response,
            AuthenticationException authException) throws IOException {
        ProblemDetail body = factory.build(
                HttpStatus.UNAUTHORIZED,
                ErrorCodes.AUTH_UNAUTHENTICATED,
                null,
                request.getRequestURI());
        response.setStatus(HttpStatus.UNAUTHORIZED.value());
        response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
        OBJECT_MAPPER.writeValue(response.getWriter(), body);
    }
}
