package com.stocka.backend.modules.mcp.tool;

/**
 * MCP tool annotations: how a host should treat the tool before calling it.
 *
 * @param readOnly    the tool changes nothing
 * @param destructive the tool may delete or irreversibly change data
 * @param idempotent  repeating the call with the same arguments has no further effect
 */
public record ToolHints(boolean readOnly, boolean destructive, boolean idempotent) {

    /** Pure read. */
    public static final ToolHints READ = new ToolHints(true, false, true);
    /** Creates something new on every call. */
    public static final ToolHints CREATE = new ToolHints(false, false, false);
    /** Updates in place; safe to repeat. */
    public static final ToolHints UPDATE = new ToolHints(false, false, true);
    /** Deletes or revokes. */
    public static final ToolHints DELETE = new ToolHints(false, true, true);
}
