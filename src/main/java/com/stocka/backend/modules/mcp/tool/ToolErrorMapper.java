package com.stocka.backend.modules.mcp.tool;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.MessageSource;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.stereotype.Component;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.server.ResponseStatusException;

import com.stocka.backend.modules.common.error.ApiException;
import com.stocka.backend.modules.common.error.ErrorCodes;
import com.stocka.backend.modules.search.client.XeyeException;
import com.stocka.backend.modules.security.ratelimit.RateLimitedException;

import jakarta.validation.ConstraintViolation;
import jakarta.validation.ConstraintViolationException;

/**
 * Turns any exception raised while running a tool into a {@link ToolError} the model can read:
 * the same stable codes as the REST API, the English message of the bundle, and a hint for the
 * most common situations. Stack traces never leave the server.
 */
@Component
public class ToolErrorMapper {

    private static final Logger log = LoggerFactory.getLogger(ToolErrorMapper.class);

    private static final Map<String, String> HINTS = Map.ofEntries(
            Map.entry(ErrorCodes.AUTH_INSUFFICIENT_SCOPE,
                    "Ask the user to reconnect this app granting that permission on the consent page."),
            Map.entry(ErrorCodes.AUTH_FORBIDDEN,
                    "The user's role in this organization does not allow the action; tell the user instead of retrying."),
            Map.entry("organizations.not_found",
                    "Use one of the organization slugs returned by the `me` tool."),
            Map.entry("pieces.not_found", "Find the piece id with `pieces_search` first."),
            Map.entry("piece_types.not_found", "List types with `piece_types_list` to get a valid id."),
            Map.entry("locations.not_found", "Call `locations_tree` to get a valid location id."),
            Map.entry("timelines.not_found", "Call `timelines_list` to get a valid timeline id."),
            Map.entry("timeline_scenes.version_conflict",
                    "Someone else saved the scene. Call `timeline_scene_get`, re-read the current version and retry with expectedVersion set to it."),
            Map.entry("timelines.name_conflict", "Choose another name or rename the existing timeline."),
            Map.entry(ErrorCodes.MCP_INVALID_ARGUMENTS, "Check the tool's input schema and resend the call."),
            Map.entry(ErrorCodes.REQUEST_RATE_LIMITED, "Wait a moment before calling more tools."),
            Map.entry(ErrorCodes.SEARCH_UNAVAILABLE, "Semantic search is down; the plain text search was used instead or try again later."));

    private final MessageSource messages;

    public ToolErrorMapper(MessageSource messages) {
        this.messages = messages;
    }

    /** @return the error for {@code ex}; unexpected exceptions are logged and reported generically */
    public ToolError map(Throwable ex) {
        if (ex instanceof ToolException te) {
            String message = resolve(te.getCode(), te.getParams(), te.getMessage());
            return new ToolError(te.getCode(), message, hint(te.getCode(), te.getHint()), te.getParams());
        }
        if (ex instanceof ApiException ae) {
            return new ToolError(ae.getCode(), resolve(ae.getCode(), ae.getParams(), ae.getCode()),
                    hint(ae.getCode(), null), ae.getParams());
        }
        if (ex instanceof RateLimitedException) {
            return new ToolError(ErrorCodes.REQUEST_RATE_LIMITED, resolve(ErrorCodes.REQUEST_RATE_LIMITED, Map.of(),
                    "Too many calls"), hint(ErrorCodes.REQUEST_RATE_LIMITED, null), Map.of());
        }
        if (ex instanceof ResponseStatusException rse) {
            String code = "legacy." + rse.getStatusCode().value();
            String reason = rse.getReason() == null ? rse.getStatusCode().toString() : rse.getReason();
            return new ToolError(code, reason, hintForStatus(rse.getStatusCode().value()), Map.of());
        }
        if (ex instanceof MethodArgumentNotValidException manve) {
            Map<String, Object> fields = new LinkedHashMap<>();
            manve.getBindingResult().getFieldErrors().forEach(f -> fields.put(f.getField(), f.getDefaultMessage()));
            return new ToolError(ErrorCodes.VALIDATION_FAILED, "Validation failed", hint(ErrorCodes.MCP_INVALID_ARGUMENTS, null),
                    fields);
        }
        if (ex instanceof ConstraintViolationException cve) {
            Map<String, Object> fields = new LinkedHashMap<>();
            for (ConstraintViolation<?> v : cve.getConstraintViolations()) {
                fields.put(String.valueOf(v.getPropertyPath()), v.getMessage());
            }
            return new ToolError(ErrorCodes.VALIDATION_FAILED, "Validation failed", hint(ErrorCodes.MCP_INVALID_ARGUMENTS, null),
                    fields);
        }
        if (ex instanceof AccessDeniedException) {
            return new ToolError(ErrorCodes.AUTH_FORBIDDEN, resolve(ErrorCodes.AUTH_FORBIDDEN, Map.of(), "Forbidden"),
                    hint(ErrorCodes.AUTH_FORBIDDEN, null), Map.of());
        }
        if (ex instanceof AuthenticationException) {
            return new ToolError(ErrorCodes.AUTH_UNAUTHENTICATED,
                    resolve(ErrorCodes.AUTH_UNAUTHENTICATED, Map.of(), "Unauthenticated"), null, Map.of());
        }
        if (ex instanceof XeyeException xe) {
            return new ToolError(ErrorCodes.SEARCH_UNAVAILABLE, resolve(ErrorCodes.SEARCH_UNAVAILABLE, Map.of(),
                    "Search unavailable"), hint(ErrorCodes.SEARCH_UNAVAILABLE, null), Map.of("detail", xe.getMessage()));
        }
        if (ex instanceof IllegalArgumentException iae) {
            return new ToolError(ErrorCodes.MCP_INVALID_ARGUMENTS, "Invalid arguments: " + iae.getMessage(),
                    hint(ErrorCodes.MCP_INVALID_ARGUMENTS, null), Map.of());
        }
        if (ex instanceof DataIntegrityViolationException) {
            return new ToolError("request.conflict", "The change conflicts with existing data (duplicate or referenced row).",
                    "Check for duplicates and retry with different values.", Map.of());
        }
        log.error("mcp_tool_unexpected_error", ex);
        return new ToolError(ErrorCodes.SERVER_INTERNAL_ERROR,
                resolve(ErrorCodes.SERVER_INTERNAL_ERROR, Map.of(), "Internal error"),
                "Retry once; if it fails again report the problem to the user.", Map.of());
    }

    /** @return the English message of {@code code}, falling back to {@code fallback} */
    public String resolve(String code, Map<String, Object> params, String fallback) {
        Object[] args = params == null ? new Object[0] : params.values().toArray();
        String text = messages.getMessage("errors." + code, args, null, Locale.ENGLISH);
        return text == null || text.isBlank() ? fallback : text;
    }

    private static String hint(String code, String explicit) {
        if (explicit != null && !explicit.isBlank()) return explicit;
        return HINTS.get(code);
    }

    private static String hintForStatus(int status) {
        return switch (status) {
            case 404 -> "The referenced item does not exist in this organization; look it up first.";
            case 409 -> "The change conflicts with the current state; reload and retry.";
            case 400 -> "The request was rejected; fix the arguments as described and retry.";
            default -> null;
        };
    }

    static List<String> hintedCodes() {
        return List.copyOf(HINTS.keySet());
    }
}
