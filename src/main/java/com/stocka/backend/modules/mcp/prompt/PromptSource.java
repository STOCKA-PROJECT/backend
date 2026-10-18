package com.stocka.backend.modules.mcp.prompt;

import java.util.List;
import java.util.Map;
import java.util.function.Function;

/**
 * A bean that contributes MCP prompts: reusable instructions the host can offer to the user.
 */
public interface PromptSource {

    /** One prompt argument. */
    record Argument(String name, String description, boolean required) {
    }

    /** A prompt. The renderer receives the argument values and returns the user message text. */
    record PromptDefinition(String name, String title, String description, List<Argument> arguments,
                            Function<Map<String, String>, String> renderer) {
    }

    List<PromptDefinition> prompts();
}
