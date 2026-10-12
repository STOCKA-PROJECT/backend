package com.stocka.backend.modules.common.error;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.LinkedHashMap;
import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

@DisplayName("ApiException")
class ApiExceptionTest {

    @Nested
    @DisplayName("params")
    class Params {

        @Test
        @DisplayName("keeps the declaration order so messages can bind {0}, {1}... by position")
        void should_keepInsertionOrder() {
            Map<String, Object> params = new LinkedHashMap<>();
            params.put("first", 0);
            params.put("last", 300);
            params.put("max", 299);

            ApiException ex = new ApiException(HttpStatus.BAD_REQUEST, ErrorCodes.PORTS_SEGMENT_OUT_OF_RANGE, params);

            assertThat(ex.getParams().keySet()).containsExactly("first", "last", "max");
            assertThat(ex.getParams().values()).containsExactly(0, 300, 299);
        }

        @Test
        @DisplayName("copies the map so later changes to the caller's map do not leak in")
        void should_copyParams() {
            Map<String, Object> params = new LinkedHashMap<>();
            params.put("pin", 27);
            ApiException ex = new ApiException(HttpStatus.BAD_REQUEST, ErrorCodes.PORTS_PIN_RESERVED, params);
            params.put("pin", 99);
            assertThat(ex.getParams()).containsEntry("pin", 27);
        }

        @Test
        @DisplayName("is immutable")
        void should_beImmutable() {
            ApiException ex = new ApiException(HttpStatus.BAD_REQUEST, ErrorCodes.PORTS_KIND_REQUIRED, Map.of("a", 1));
            assertThatThrownBy(() -> ex.getParams().put("b", 2)).isInstanceOf(UnsupportedOperationException.class);
        }

        @Test
        @DisplayName("defaults to an empty map when null")
        void should_defaultToEmpty() {
            assertThat(new ApiException(HttpStatus.NOT_FOUND, ErrorCodes.PORTS_NOT_FOUND).getParams()).isEmpty();
            assertThat(new ApiException(HttpStatus.NOT_FOUND, ErrorCodes.PORTS_NOT_FOUND, null).getParams()).isEmpty();
        }

        @Test
        @DisplayName("exposes status, code and cause")
        void should_exposeStatusCodeAndCause() {
            RuntimeException cause = new RuntimeException("db");
            ApiException ex = new ApiException(HttpStatus.CONFLICT, ErrorCodes.PORTS_PIN_CONFLICT, null, cause);
            assertThat(ex.getStatus()).isEqualTo(HttpStatus.CONFLICT);
            assertThat(ex.getCode()).isEqualTo(ErrorCodes.PORTS_PIN_CONFLICT);
            assertThat(ex.getCause()).isSameAs(cause);
        }
    }
}
