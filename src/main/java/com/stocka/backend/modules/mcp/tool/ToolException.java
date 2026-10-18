package com.stocka.backend.modules.mcp.tool;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * An error a tool wants to report with a stable code and a hint the model can act on.
 *
 * <p>Use the codes of {@link com.stocka.backend.modules.common.error.ErrorCodes} when one fits; the
 * message is resolved from the English bundle, or taken from {@code message} when the code has none.
 */
public class ToolException extends RuntimeException {

    private final String code;
    private final String hint;
    private final Map<String, Object> params;

    public ToolException(String code, String message, String hint) {
        this(code, message, hint, null);
    }

    public ToolException(String code, String message, String hint, Map<String, Object> params) {
        super(message);
        this.code = code;
        this.hint = hint;
        this.params = params == null ? Collections.emptyMap() : Collections.unmodifiableMap(new LinkedHashMap<>(params));
    }

    public String getCode() { return code; }
    public String getHint() { return hint; }
    public Map<String, Object> getParams() { return params; }
}
