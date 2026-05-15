package com.stocka.backend.modules.common.error;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.MessageSource;
import org.springframework.context.NoSuchMessageException;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.validation.FieldError;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.ServletRequestBindingException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import com.stocka.backend.modules.security.ratelimit.RateLimitedException;
import com.stocka.backend.modules.storage.R2UnavailableException;

import jakarta.servlet.http.HttpServletRequest;

/**
 * Manejador global de excepciones que serializa la respuesta como
 * {@link ProblemDetail} (RFC 7807 + {@code code}).
 *
 * <p>Las subclases más específicas deben declararse antes que las genéricas
 * para que Spring elija el handler correcto (por ejemplo,
 * {@link BadCredentialsException} antes que {@link AuthenticationException}).
 *
 * <p>El handler de {@link ResponseStatusException} es la red de seguridad
 * de la migración: los throws antiguos siguen funcionando con un code
 * {@code legacy.<status>} y conservando el mensaje original como {@code detail}.
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    private final ProblemDetailFactory factory;
    private final MessageSource messageSource;

    public GlobalExceptionHandler(ProblemDetailFactory factory, MessageSource messageSource) {
        this.factory = factory;
        this.messageSource = messageSource;
    }

    @ExceptionHandler(ApiException.class)
    public ResponseEntity<ProblemDetail> handleApiException(ApiException ex, HttpServletRequest request) {
        ProblemDetail body = factory.build(
                ex.getStatus(),
                ex.getCode(),
                ex.getParams(),
                request.getRequestURI());
        return ResponseEntity.status(ex.getStatus()).body(body);
    }

    /**
     * Especialización de {@link ApiException} para añadir la cabecera
     * {@code Retry-After} requerida por RFC 6585.
     */
    @ExceptionHandler(RateLimitedException.class)
    public ResponseEntity<ProblemDetail> handleRateLimited(RateLimitedException ex, HttpServletRequest request) {
        ProblemDetail body = factory.build(
                ex.getStatus(),
                ex.getCode(),
                ex.getParams(),
                request.getRequestURI());
        return ResponseEntity.status(ex.getStatus())
                .header(HttpHeaders.RETRY_AFTER, Long.toString(ex.getRetryAfterSeconds()))
                .body(body);
    }

    @ExceptionHandler(R2UnavailableException.class)
    public ResponseEntity<ProblemDetail> handleR2(R2UnavailableException ex, HttpServletRequest request) {
        ProblemDetail body = factory.build(
                HttpStatus.SERVICE_UNAVAILABLE,
                ErrorCodes.STORAGE_R2_UNAVAILABLE,
                null,
                request.getRequestURI());
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(body);
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ProblemDetail> handleValidation(
            MethodArgumentNotValidException ex, HttpServletRequest request) {
        Locale locale = LocaleContextHolder.getLocale();
        List<Map<String, Object>> fieldErrors = new ArrayList<>();
        for (FieldError fe : ex.getBindingResult().getFieldErrors()) {
            String constraint = fe.getCode() == null ? "invalid" : fe.getCode().toLowerCase(Locale.ROOT);
            String code = "validation." + constraint;
            String message = resolveOrFallback(code, locale, fe.getDefaultMessage());
            Map<String, Object> entry = new HashMap<>();
            entry.put("field", fe.getField());
            entry.put("code", code);
            entry.put("message", message);
            fieldErrors.add(entry);
        }
        ProblemDetail body = factory.buildValidation(
                HttpStatus.BAD_REQUEST,
                ErrorCodes.VALIDATION_FAILED,
                request.getRequestURI(),
                fieldErrors);
        return ResponseEntity.badRequest().body(body);
    }

    @ExceptionHandler(BadCredentialsException.class)
    public ResponseEntity<ProblemDetail> handleBadCredentials(
            BadCredentialsException ex, HttpServletRequest request) {
        ProblemDetail body = factory.build(
                HttpStatus.UNAUTHORIZED,
                ErrorCodes.AUTH_INVALID_CREDENTIALS,
                null,
                request.getRequestURI());
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(body);
    }

    @ExceptionHandler(AuthenticationException.class)
    public ResponseEntity<ProblemDetail> handleAuth(AuthenticationException ex, HttpServletRequest request) {
        ProblemDetail body = factory.build(
                HttpStatus.UNAUTHORIZED,
                ErrorCodes.AUTH_UNAUTHENTICATED,
                null,
                request.getRequestURI());
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(body);
    }

    @ExceptionHandler(AccessDeniedException.class)
    public ResponseEntity<ProblemDetail> handleAccessDenied(
            AccessDeniedException ex, HttpServletRequest request) {
        ProblemDetail body = factory.build(
                HttpStatus.FORBIDDEN,
                ErrorCodes.AUTH_FORBIDDEN,
                null,
                request.getRequestURI());
        return ResponseEntity.status(HttpStatus.FORBIDDEN).body(body);
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ProblemDetail> handleNotReadable(
            HttpMessageNotReadableException ex, HttpServletRequest request) {
        ProblemDetail body = factory.build(
                HttpStatus.BAD_REQUEST,
                ErrorCodes.REQUEST_MALFORMED_BODY,
                null,
                request.getRequestURI());
        return ResponseEntity.badRequest().body(body);
    }

    /**
     * Cubre subclases como {@code MissingRequestHeaderException} o
     * {@code MissingServletRequestParameterException}: Spring las traducía a
     * 400 por defecto, así que mantenemos ese comportamiento en lugar de dejar
     * que el catch-all las convierta en 500.
     */
    @ExceptionHandler(ServletRequestBindingException.class)
    public ResponseEntity<ProblemDetail> handleBinding(
            ServletRequestBindingException ex, HttpServletRequest request) {
        ProblemDetail body = factory.build(
                HttpStatus.BAD_REQUEST,
                ErrorCodes.REQUEST_MALFORMED_BODY,
                null,
                request.getRequestURI(),
                ex.getMessage());
        return ResponseEntity.badRequest().body(body);
    }

    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    public ResponseEntity<ProblemDetail> handleMethodNotSupported(
            HttpRequestMethodNotSupportedException ex, HttpServletRequest request) {
        ProblemDetail body = factory.build(
                HttpStatus.METHOD_NOT_ALLOWED,
                "request.method_not_allowed",
                null,
                request.getRequestURI(),
                ex.getMessage());
        return ResponseEntity.status(HttpStatus.METHOD_NOT_ALLOWED).body(body);
    }

    @ExceptionHandler(NoResourceFoundException.class)
    public ResponseEntity<ProblemDetail> handleNoResource(
            NoResourceFoundException ex, HttpServletRequest request) {
        ProblemDetail body = factory.build(
                HttpStatus.NOT_FOUND,
                "request.not_found",
                null,
                request.getRequestURI());
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(body);
    }

    @ExceptionHandler(MaxUploadSizeExceededException.class)
    public ResponseEntity<ProblemDetail> handleMaxUpload(
            MaxUploadSizeExceededException ex, HttpServletRequest request) {
        ProblemDetail body = factory.build(
                HttpStatus.CONTENT_TOO_LARGE,
                ErrorCodes.UPLOAD_TOO_LARGE,
                null,
                request.getRequestURI());
        return ResponseEntity.status(HttpStatus.CONTENT_TOO_LARGE).body(body);
    }

    /**
     * Puente para excepciones existentes lanzadas como {@link ResponseStatusException}.
     * Devuelve un {@code code} con prefijo {@code legacy.<status>} y conserva el
     * {@code reason} original como {@code detail} para que el frontend pueda
     * usarlo como fallback hasta migrar el throw a {@link ApiException}.
     */
    @ExceptionHandler(ResponseStatusException.class)
    public ResponseEntity<ProblemDetail> handleLegacy(ResponseStatusException ex, HttpServletRequest request) {
        HttpStatusCode statusCode = ex.getStatusCode();
        HttpStatus status = HttpStatus.resolve(statusCode.value());
        if (status == null) {
            status = HttpStatus.INTERNAL_SERVER_ERROR;
        }
        String legacyCode = "legacy." + status.name().toLowerCase(Locale.ROOT);
        ProblemDetail body = factory.build(status, legacyCode, null, request.getRequestURI(), ex.getReason());
        return ResponseEntity.status(status).body(body);
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ProblemDetail> handleUnknown(Exception ex, HttpServletRequest request) {
        log.error("unhandled_exception path={}", request.getRequestURI(), ex);
        ProblemDetail body = factory.build(
                HttpStatus.INTERNAL_SERVER_ERROR,
                ErrorCodes.SERVER_INTERNAL_ERROR,
                null,
                request.getRequestURI());
        return ResponseEntity.internalServerError().body(body);
    }

    private String resolveOrFallback(String code, Locale locale, String fallback) {
        try {
            return messageSource.getMessage("errors." + code, null, locale);
        } catch (NoSuchMessageException ignored) {
            return fallback != null ? fallback : code;
        }
    }
}
