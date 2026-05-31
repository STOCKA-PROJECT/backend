package com.stocka.backend.modules.security.error;

import java.io.IOException;

import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.web.access.AccessDeniedHandler;
import org.springframework.stereotype.Component;

import com.stocka.backend.modules.common.error.ErrorCodes;
import com.stocka.backend.modules.common.error.ProblemDetailFactory;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import tools.jackson.databind.ObjectMapper;

/**
 * Handler de acceso denegado que devuelve un {@link ProblemDetail} JSON con
 * {@code code: auth.forbidden} para los 403 generados por Spring Security a
 * nivel de filtro (antes del {@code @RestControllerAdvice}).
 *
 * <p>Usa el {@link ObjectMapper} gestionado por Spring (con el mixin de
 * {@link ProblemDetail} registrado) para que las {@code properties} se
 * aplanen al nivel raíz del JSON.
 */
@Component
public class JsonAccessDeniedHandler implements AccessDeniedHandler {

    private final ProblemDetailFactory factory;
    private final ObjectMapper objectMapper;

    public JsonAccessDeniedHandler(ProblemDetailFactory factory, ObjectMapper objectMapper) {
        this.factory = factory;
        this.objectMapper = objectMapper;
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
        objectMapper.writeValue(response.getWriter(), body);
    }
}
