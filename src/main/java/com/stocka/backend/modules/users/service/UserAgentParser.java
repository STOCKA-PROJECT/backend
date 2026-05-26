package com.stocka.backend.modules.users.service;

import org.springframework.stereotype.Component;

/**
 * Minimal {@code User-Agent} parser used to build the "display name" of a
 * {@link com.stocka.backend.modules.users.entity.UserDevice}. The pattern set
 * is deliberately small — anything we can't recognize falls back to
 * {@code "Unknown browser"} so the panel never shows a raw 400-character UA
 * to the user.
 *
 * <p>For a production-quality parser we'd reach for {@code yauaa}; the
 * coverage here is intentionally narrow to keep the dependency surface flat.
 */
@Component
public class UserAgentParser {

    /** Result of the parsing. Null fields signal "unknown". */
    public record ParsedUserAgent(String browser, String os) {
        public String toDisplayName() {
            if (browser == null && os == null) return "Unknown browser";
            if (browser == null) return "Browser on " + os;
            if (os == null) return browser;
            return browser + " on " + os;
        }
    }

    private static final int MAX_DISPLAY_NAME_LENGTH = 120;

    public ParsedUserAgent parse(String userAgent) {
        if (userAgent == null || userAgent.isBlank()) {
            return new ParsedUserAgent(null, null);
        }
        return new ParsedUserAgent(extractBrowser(userAgent), extractOs(userAgent));
    }

    public String buildDisplayName(String userAgent) {
        String name = parse(userAgent).toDisplayName();
        return name.length() <= MAX_DISPLAY_NAME_LENGTH ? name : name.substring(0, MAX_DISPLAY_NAME_LENGTH);
    }

    private static String extractBrowser(String ua) {
        // Order matters: Chrome's UA contains "Safari", Edge's contains "Chrome", etc.
        if (ua.contains("Edg/")) return "Edge";
        if (ua.contains("OPR/") || ua.contains("Opera")) return "Opera";
        if (ua.contains("Firefox/")) return "Firefox";
        if (ua.contains("Chrome/")) return "Chrome";
        if (ua.contains("Safari/")) return "Safari";
        return null;
    }

    private static String extractOs(String ua) {
        if (ua.contains("Windows")) return "Windows";
        if (ua.contains("Android")) return "Android";
        if (ua.contains("iPhone") || ua.contains("iPad") || ua.contains("iPod")) return "iOS";
        if (ua.contains("Mac OS X") || ua.contains("Macintosh")) return "macOS";
        if (ua.contains("Linux")) return "Linux";
        return null;
    }
}
