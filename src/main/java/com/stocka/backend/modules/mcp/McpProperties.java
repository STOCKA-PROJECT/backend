package com.stocka.backend.modules.mcp;

import java.util.ArrayList;
import java.util.List;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Settings of the MCP server ({@code stocka.mcp.*}). The transport itself is configured through
 * {@code spring.ai.mcp.server.*}; these are Stocka's own knobs.
 */
@ConfigurationProperties(prefix = "stocka.mcp")
public class McpProperties {

    private boolean enabled = true;
    /** Origins allowed to call {@code /mcp} from a browser; empty means none (server-side clients only). */
    private List<String> corsAllowedOrigins = new ArrayList<>();
    private int maxPageSize = 50;
    private int defaultPageSize = 20;
    /** Tool calls per user per minute. */
    private int userCallsPerMinute = 120;
    /** Longest free-text field returned in a tool result before truncation. */
    private int maxTextLength = 2000;

    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }
    public List<String> getCorsAllowedOrigins() { return corsAllowedOrigins; }
    public void setCorsAllowedOrigins(List<String> corsAllowedOrigins) {
        this.corsAllowedOrigins = new ArrayList<>();
        if (corsAllowedOrigins != null) {
            for (String origin : corsAllowedOrigins) {
                if (origin != null && !origin.isBlank()) this.corsAllowedOrigins.add(origin.trim());
            }
        }
    }
    public int getMaxPageSize() { return maxPageSize; }
    public void setMaxPageSize(int maxPageSize) { this.maxPageSize = Math.max(1, maxPageSize); }
    public int getDefaultPageSize() { return defaultPageSize; }
    public void setDefaultPageSize(int defaultPageSize) { this.defaultPageSize = Math.max(1, defaultPageSize); }
    public int getUserCallsPerMinute() { return userCallsPerMinute; }
    public void setUserCallsPerMinute(int userCallsPerMinute) { this.userCallsPerMinute = Math.max(1, userCallsPerMinute); }
    public int getMaxTextLength() { return maxTextLength; }
    public void setMaxTextLength(int maxTextLength) { this.maxTextLength = Math.max(100, maxTextLength); }
}
