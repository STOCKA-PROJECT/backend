package com.stocka.backend.modules.mcp.tool;

import java.util.List;

/**
 * A bean that contributes tools. One implementation per domain (pieces, timelines, …); the registry
 * collects them all at startup.
 */
public interface ToolSource {

    List<ToolDefinition<?>> tools();
}
