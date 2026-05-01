package com.stocka.backend.modules.common.error;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.lang.reflect.Method;
import java.net.URI;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.context.MessageSource;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.context.support.ResourceBundleMessageSource;
import org.springframework.core.MethodParameter;
import org.springframework.http.HttpInputMessage;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.validation.BeanPropertyBindingResult;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.server.ResponseStatusException;

import com.stocka.backend.modules.storage.R2UnavailableException;

import jakarta.servlet.http.HttpServletRequest;

@DisplayName("GlobalExceptionHandler")
class GlobalExceptionHandlerTest {

    private GlobalExceptionHandler handler;
    private HttpServletRequest request;

    @BeforeEach
    void setUp() {
        ResourceBundleMessageSource ms = new ResourceBundleMessageSource();
        ms.setBasename("messages");
        ms.setDefaultEncoding("UTF-8");
        ms.setUseCodeAsDefaultMessage(false);
        ms.setFallbackToSystemLocale(false);
        MessageSource messageSource = ms;
        ProblemDetailFactory factory = new ProblemDetailFactory(messageSource);
        handler = new GlobalExceptionHandler(factory, messageSource);
        request = mock(HttpServletRequest.class);
        when(request.getRequestURI()).thenReturn("/test/path");
        LocaleContextHolder.setLocale(Locale.of("es"));
    }

    @AfterEach
    void tearDown() {
        LocaleContextHolder.resetLocaleContext();
    }

    private ProblemDetail body(ResponseEntity<ProblemDetail> resp) {
        ProblemDetail b = resp.getBody();
        assertNotNull(b);
        return b;
    }

    @Nested
    @DisplayName("ApiException handling")
    class ApiExceptionHandling {

        @Test
        @DisplayName("should propagate status, code and detail localized in Spanish")
        void should_propagateStatusCodeAndDetail_es() {
            ApiException ex = new ApiException(HttpStatus.BAD_REQUEST, ErrorCodes.AUTH_PASSWORDS_MISMATCH);

            ResponseEntity<ProblemDetail> resp = handler.handleApiException(ex, request);

            assertEquals(HttpStatus.BAD_REQUEST, resp.getStatusCode());
            ProblemDetail b = body(resp);
            assertEquals(400, b.getStatus());
            assertEquals("Las contraseñas no coinciden.", b.getDetail());
            assertEquals(ErrorCodes.AUTH_PASSWORDS_MISMATCH, b.getProperties().get("code"));
        }

        @Test
        @DisplayName("should localize detail when locale is English")
        void should_localizeDetail_when_localeIsEnglish() {
            LocaleContextHolder.setLocale(Locale.of("en"));
            ApiException ex = new ApiException(HttpStatus.BAD_REQUEST, ErrorCodes.AUTH_PASSWORDS_MISMATCH);

            ResponseEntity<ProblemDetail> resp = handler.handleApiException(ex, request);

            assertEquals("Passwords do not match.", body(resp).getDetail());
        }

        @Test
        @DisplayName("should localize detail when locale is Catalan")
        void should_localizeDetail_when_localeIsCatalan() {
            LocaleContextHolder.setLocale(Locale.of("ca"));
            ApiException ex = new ApiException(HttpStatus.BAD_REQUEST, ErrorCodes.AUTH_PASSWORDS_MISMATCH);

            ResponseEntity<ProblemDetail> resp = handler.handleApiException(ex, request);

            assertEquals("Les contrasenyes no coincideixen.", body(resp).getDetail());
        }

        @Test
        @DisplayName("should expose params in response and interpolate them in detail")
        void should_exposeParams_andInterpolate_inDetail() {
            ApiException ex = new ApiException(
                    HttpStatus.BAD_REQUEST,
                    ErrorCodes.ORGANIZATIONS_INVITATION_LIMIT_REACHED,
                    Map.of("max", 50));

            ResponseEntity<ProblemDetail> resp = handler.handleApiException(ex, request);

            ProblemDetail b = body(resp);
            assertTrue(b.getDetail().contains("50"));
            @SuppressWarnings("unchecked")
            Map<String, Object> params = (Map<String, Object>) b.getProperties().get("params");
            assertNotNull(params);
            assertEquals(50, params.get("max"));
        }

        @Test
        @DisplayName("should fall back to errors.generic.<status> when code has no translation")
        void should_fallback_toGeneric_when_codeMissing() {
            ApiException ex = new ApiException(HttpStatus.BAD_REQUEST, "made.up.code");

            ResponseEntity<ProblemDetail> resp = handler.handleApiException(ex, request);

            assertEquals("Solicitud incorrecta.", body(resp).getDetail());
            assertEquals("made.up.code", body(resp).getProperties().get("code"));
        }

        @Test
        @DisplayName("should fall back to code literal when neither key nor generic exist")
        void should_fallback_toCode_when_genericMissing() {
            ApiException ex = new ApiException(HttpStatus.METHOD_NOT_ALLOWED, "something.weird");

            ResponseEntity<ProblemDetail> resp = handler.handleApiException(ex, request);

            assertEquals("something.weird", body(resp).getDetail());
        }
    }

    @Nested
    @DisplayName("Legacy ResponseStatusException")
    class LegacyHandling {

        @Test
        @DisplayName("should map to legacy.<status> code and keep original reason as detail")
        void should_mapToLegacyCode_andKeepReason() {
            ResponseStatusException ex = new ResponseStatusException(
                    HttpStatus.BAD_REQUEST, "Las contraseñas no coinciden");

            ResponseEntity<ProblemDetail> resp = handler.handleLegacy(ex, request);

            ProblemDetail b = body(resp);
            assertEquals(400, b.getStatus());
            assertEquals("legacy.bad_request", b.getProperties().get("code"));
            assertEquals("Las contraseñas no coinciden", b.getDetail());
        }

        @Test
        @DisplayName("should produce legacy.conflict for 409")
        void should_produceLegacyConflict_for409() {
            ResponseStatusException ex = new ResponseStatusException(
                    HttpStatus.CONFLICT, "Ya existe un usuario con ese email");

            ResponseEntity<ProblemDetail> resp = handler.handleLegacy(ex, request);

            assertEquals(HttpStatus.CONFLICT, resp.getStatusCode());
            assertEquals("legacy.conflict", body(resp).getProperties().get("code"));
        }

        @Test
        @DisplayName("should fall back to localized message when reason is null")
        void should_fallbackToLocalizedMessage_when_reasonIsNull() {
            ResponseStatusException ex = new ResponseStatusException(HttpStatus.NOT_FOUND);

            ResponseEntity<ProblemDetail> resp = handler.handleLegacy(ex, request);

            ProblemDetail b = body(resp);
            assertEquals("legacy.not_found", b.getProperties().get("code"));
            assertEquals("Recurso no encontrado.", b.getDetail());
        }
    }

    @Nested
    @DisplayName("Validation failures")
    class ValidationFailures {

        @Test
        @DisplayName("should produce 400 with validation.failed code and errors[] per field")
        void should_produce400_withValidationFailed_andFieldErrors() throws Exception {
            MethodArgumentNotValidException ex = buildValidationException(
                    "email", "NotBlank", "must not be blank");

            ResponseEntity<ProblemDetail> resp = handler.handleValidation(ex, request);

            assertEquals(HttpStatus.BAD_REQUEST, resp.getStatusCode());
            ProblemDetail b = body(resp);
            assertEquals(ErrorCodes.VALIDATION_FAILED, b.getProperties().get("code"));
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> errors = (List<Map<String, Object>>) b.getProperties().get("errors");
            assertNotNull(errors);
            assertEquals(1, errors.size());
            assertEquals("email", errors.get(0).get("field"));
            assertEquals("validation.notblank", errors.get(0).get("code"));
            assertNotNull(errors.get(0).get("message"));
        }

        @Test
        @DisplayName("should fall back to default message when constraint code has no translation")
        void should_fallback_toDefaultMessage_when_constraintMissing() throws Exception {
            MethodArgumentNotValidException ex = buildValidationException(
                    "name", "MyCustomConstraint", "must satisfy custom rule");

            ResponseEntity<ProblemDetail> resp = handler.handleValidation(ex, request);

            ProblemDetail b = body(resp);
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> errors = (List<Map<String, Object>>) b.getProperties().get("errors");
            assertEquals("must satisfy custom rule", errors.get(0).get("message"));
        }

        private MethodArgumentNotValidException buildValidationException(
                String field, String constraint, String defaultMessage) throws NoSuchMethodException {
            Method m = Holder.class.getMethod("any", String.class);
            MethodParameter mp = new MethodParameter(m, 0);
            BeanPropertyBindingResult br = new BeanPropertyBindingResult(new Object(), "target");
            br.addError(new FieldError(
                    "target", field, null, false, new String[]{constraint}, null, defaultMessage));
            return new MethodArgumentNotValidException(mp, br);
        }

        static class Holder {
            public void any(String x) {
                // test fixture
            }
        }
    }

    @Nested
    @DisplayName("Auth errors (controller advice path)")
    class AuthErrors {

        @Test
        @DisplayName("should map BadCredentialsException to 401 + auth.invalid_credentials")
        void should_mapBadCredentials() {
            ResponseEntity<ProblemDetail> resp = handler.handleBadCredentials(
                    new BadCredentialsException("bad"), request);

            assertEquals(HttpStatus.UNAUTHORIZED, resp.getStatusCode());
            assertEquals(ErrorCodes.AUTH_INVALID_CREDENTIALS, body(resp).getProperties().get("code"));
        }

        @Test
        @DisplayName("should map generic AuthenticationException to 401 + auth.unauthenticated")
        void should_mapAuthentication() {
            AuthenticationException ex = new AuthenticationException("nope") {};

            ResponseEntity<ProblemDetail> resp = handler.handleAuth(ex, request);

            assertEquals(HttpStatus.UNAUTHORIZED, resp.getStatusCode());
            assertEquals(ErrorCodes.AUTH_UNAUTHENTICATED, body(resp).getProperties().get("code"));
        }

        @Test
        @DisplayName("should map AccessDeniedException to 403 + auth.forbidden")
        void should_mapAccessDenied() {
            ResponseEntity<ProblemDetail> resp = handler.handleAccessDenied(
                    new AccessDeniedException("no"), request);

            assertEquals(HttpStatus.FORBIDDEN, resp.getStatusCode());
            assertEquals(ErrorCodes.AUTH_FORBIDDEN, body(resp).getProperties().get("code"));
        }
    }

    @Nested
    @DisplayName("R2 storage errors")
    class StorageErrors {

        @Test
        @DisplayName("should map R2UnavailableException to 503 + storage.r2_unavailable")
        void should_mapR2Unavailable() {
            ResponseEntity<ProblemDetail> resp = handler.handleR2(
                    new R2UnavailableException("down"), request);

            assertEquals(HttpStatus.SERVICE_UNAVAILABLE, resp.getStatusCode());
            ProblemDetail b = body(resp);
            assertEquals(ErrorCodes.STORAGE_R2_UNAVAILABLE, b.getProperties().get("code"));
            assertEquals("Almacenamiento no disponible.", b.getDetail());
        }
    }

    @Nested
    @DisplayName("Other framework errors")
    class FrameworkErrors {

        @Test
        @DisplayName("should map HttpMessageNotReadableException to 400 + request.malformed_body")
        void should_mapNotReadable() {
            HttpInputMessage input = mock(HttpInputMessage.class);
            ResponseEntity<ProblemDetail> resp = handler.handleNotReadable(
                    new HttpMessageNotReadableException("bad json", input), request);

            assertEquals(HttpStatus.BAD_REQUEST, resp.getStatusCode());
            assertEquals(ErrorCodes.REQUEST_MALFORMED_BODY, body(resp).getProperties().get("code"));
        }

        @Test
        @DisplayName("should map MaxUploadSizeExceededException to 413 + upload.too_large")
        void should_mapMaxUpload() {
            ResponseEntity<ProblemDetail> resp = handler.handleMaxUpload(
                    new MaxUploadSizeExceededException(100), request);

            assertEquals(HttpStatus.CONTENT_TOO_LARGE, resp.getStatusCode());
            assertEquals(ErrorCodes.UPLOAD_TOO_LARGE, body(resp).getProperties().get("code"));
        }
    }

    @Nested
    @DisplayName("Fallback for unknown exceptions")
    class FallbackForUnknown {

        @Test
        @DisplayName("should return 500 with server.internal_error code")
        void should_return500_withServerInternalError() {
            ResponseEntity<ProblemDetail> resp = handler.handleUnknown(
                    new RuntimeException("boom"), request);

            assertEquals(HttpStatus.INTERNAL_SERVER_ERROR, resp.getStatusCode());
            assertEquals(ErrorCodes.SERVER_INTERNAL_ERROR, body(resp).getProperties().get("code"));
        }

        @Test
        @DisplayName("should not leak the original message into detail")
        void should_notLeakOriginalMessage() {
            ResponseEntity<ProblemDetail> resp = handler.handleUnknown(
                    new IllegalStateException("DB connection refused at 10.0.0.5"), request);

            assertEquals("Error interno del servidor.", body(resp).getDetail());
        }
    }

    @Nested
    @DisplayName("ProblemDetail shape")
    class ProblemDetailShape {

        @Test
        @DisplayName("should always include status, code, timestamp and instance from the request URI")
        void should_includeRequiredProps() {
            ApiException ex = new ApiException(HttpStatus.BAD_REQUEST, ErrorCodes.AUTH_PASSWORDS_MISMATCH);

            ResponseEntity<ProblemDetail> resp = handler.handleApiException(ex, request);

            ProblemDetail b = body(resp);
            assertEquals(400, b.getStatus());
            assertEquals(URI.create("/test/path"), b.getInstance());
            assertNotNull(b.getProperties().get("code"));
            assertNotNull(b.getProperties().get("timestamp"));
        }

        @Test
        @DisplayName("should NOT include params property when none were supplied")
        void should_omitParams_when_none() {
            ApiException ex = new ApiException(HttpStatus.BAD_REQUEST, ErrorCodes.AUTH_PASSWORDS_MISMATCH);

            ResponseEntity<ProblemDetail> resp = handler.handleApiException(ex, request);

            assertNull(body(resp).getProperties().get("params"));
        }
    }
}
