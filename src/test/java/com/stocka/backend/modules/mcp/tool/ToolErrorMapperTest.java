package com.stocka.backend.modules.mcp.tool;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Locale;
import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.context.support.ResourceBundleMessageSource;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.web.server.ResponseStatusException;

import com.stocka.backend.modules.common.error.ApiException;
import com.stocka.backend.modules.common.error.ErrorCodes;
import com.stocka.backend.modules.search.client.XeyeException;
import com.stocka.backend.modules.security.ratelimit.RateLimitedException;

@DisplayName("ToolErrorMapper")
class ToolErrorMapperTest {

    private final ToolErrorMapper mapper;

    ToolErrorMapperTest() {
        ResourceBundleMessageSource messages = new ResourceBundleMessageSource();
        messages.setBasename("messages");
        messages.setDefaultEncoding("UTF-8");
        messages.setDefaultLocale(Locale.ENGLISH);
        mapper = new ToolErrorMapper(messages);
    }

    @Nested
    @DisplayName("domain errors")
    class Domain {

        @Test
        @DisplayName("ApiException keeps its code, resolves the English message and adds a hint when known")
        void apiException() {
            ToolError error = mapper.map(new ApiException(HttpStatus.CONFLICT, ErrorCodes.TIMELINE_SCENE_VERSION_CONFLICT,
                    Map.of("currentVersion", 4)));
            assertThat(error.code()).isEqualTo("timeline_scenes.version_conflict");
            assertThat(error.message()).containsIgnoringCase("scene");
            assertThat(error.hint()).contains("timeline_scene_get");
            assertThat(error.params()).containsEntry("currentVersion", 4);
        }

        @Test
        @DisplayName("ToolException keeps its own hint and params")
        void toolException() {
            ToolError error = mapper.map(new ToolException("pieces.attribute_ambiguous", "Ambiguous", "Use the id",
                    Map.of("attribute", "color")));
            assertThat(error.code()).isEqualTo("pieces.attribute_ambiguous");
            assertThat(error.message()).isEqualTo("Ambiguous");
            assertThat(error.hint()).isEqualTo("Use the id");
        }

        @Test
        @DisplayName("insufficient scope resolves the message with the scope and the reconnect hint")
        void insufficientScope() {
            ToolError error = mapper.map(new ToolException(ErrorCodes.AUTH_INSUFFICIENT_SCOPE, null, null,
                    Map.of("scope", "delete")));
            assertThat(error.message()).contains("delete");
            assertThat(error.hint()).contains("reconnect");
        }

        @Test
        @DisplayName("legacy ResponseStatusException becomes legacy.<status> with a status hint")
        void legacy() {
            ToolError error = mapper.map(new ResponseStatusException(HttpStatus.NOT_FOUND, "Organización no encontrada"));
            assertThat(error.code()).isEqualTo("legacy.404");
            assertThat(error.message()).isEqualTo("Organización no encontrada");
            assertThat(error.hint()).contains("look it up");
        }
    }

    @Nested
    @DisplayName("framework errors")
    class Framework {

        @Test
        @DisplayName("access denied, authentication, rate limit, search and arguments")
        void others() {
            assertThat(mapper.map(new AccessDeniedException("x")).code()).isEqualTo(ErrorCodes.AUTH_FORBIDDEN);
            assertThat(mapper.map(new AccessDeniedException("x")).hint()).contains("role");
            assertThat(mapper.map(new BadCredentialsException("x")).code()).isEqualTo(ErrorCodes.AUTH_UNAUTHENTICATED);
            assertThat(mapper.map(new RateLimitedException("mcp", 3)).code()).isEqualTo(ErrorCodes.REQUEST_RATE_LIMITED);
            assertThat(mapper.map(new XeyeException(503, "SERVICE_UNAVAILABLE", "down")).code())
                    .isEqualTo(ErrorCodes.SEARCH_UNAVAILABLE);
            ToolError args = mapper.map(new IllegalArgumentException("bad"));
            assertThat(args.code()).isEqualTo(ErrorCodes.MCP_INVALID_ARGUMENTS);
            assertThat(args.message()).contains("bad");
        }

        @Test
        @DisplayName("anything else is an internal error without details")
        void internal() {
            ToolError error = mapper.map(new NullPointerException("secret stack"));
            assertThat(error.code()).isEqualTo(ErrorCodes.SERVER_INTERNAL_ERROR);
            assertThat(error.message()).doesNotContain("secret");
            assertThat(error.hint()).isNotBlank();
        }
    }
}
