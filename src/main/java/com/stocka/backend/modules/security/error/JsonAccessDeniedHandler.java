package com.stocka.backend.modules.security.error;

import java.io.IOException;

import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.web.access.AccessDeniedHandler;
import org.springframework.stereotype.Component;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.stocka.backend.modules.common.error.ErrorCodes;
import com.stocka.backend.modules.common.error.ProblemDetailFactory;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/**
 * Handler de acceso denegado que devuelve un {@link ProblemDetail} JSON con
 * {@code code: auth.forbidden} para los 403 generados por Spring Security a
 * nivel de filtro (antes del {@code @RestControllerAdvice}).
 */
@Component
public class JsonAccessDeniedHandler implements AccessDeniedHandler {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    private final ProblemDetailFactory factory;

    public JsonAccessDeniedHandler(ProblemDetailFactory factory) {
        this.factory = factory;
    }

    @Override
    public void handle(
            HttpServletRequest request,
            HttpServletResponse response,
            AccessDeniedException accessDeniedException) throws IOException {
        ProblemDetail body = factory.build(
                HttpStatus.FORBIDDEN,
                ErrorCodes.AUTH_FORBIDDEN,
                null,
                request.getRequestURI());
        response.setStatus(HttpStatus.FORBIDDEN.value());
        response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
        OBJECT_MAPPER.writeValue(response.getWriter(), body);
    }
}
