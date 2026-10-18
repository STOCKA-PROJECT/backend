package com.stocka.backend.modules.oauth;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * OAuth scopes understood by Stocka's authorization server and enforced by the MCP tools.
 *
 * <p>Scopes narrow what a connector may do; the organization role of the user still applies on top
 * (a SPECTATOR with {@code inventory:write} cannot write). Every {@code *:write} scope implies
 * {@link #READ}. {@link #DELETE} and {@link #ORG_MANAGE} are opt-in on the consent page.
 */
public enum OAuthScope {
    READ("read", true, true),
    INVENTORY_WRITE("inventory:write", true, true),
    TIMELINES_WRITE("timelines:write", true, true),
    HARDWARE_WRITE("hardware:write", true, true),
    ORG_MANAGE("org:manage", false, true),
    DELETE("delete", false, true),
    /** Refresh tokens; shown as "keep the connection" rather than as a permission. */
    OFFLINE_ACCESS("offline_access", true, false);

    private final String value;
    private final boolean checkedByDefault;
    private final boolean permission;

    OAuthScope(String value, boolean checkedByDefault, boolean permission) {
        this.value = value;
        this.checkedByDefault = checkedByDefault;
        this.permission = permission;
    }

    /** @return the wire value, e.g. {@code inventory:write} */
    public String value() { return value; }

    /** @return whether the consent page pre-checks this scope */
    public boolean isCheckedByDefault() { return checkedByDefault; }

    /** @return whether the scope is a permission over data (vs. a token-lifetime scope) */
    public boolean isPermission() { return permission; }

    /** @return i18n key of the human-readable description */
    public String messageKey() { return "oauth.scope." + name().toLowerCase(); }

    /** @return whether holding this scope satisfies {@code required} */
    public boolean implies(OAuthScope required) {
        if (this == required) return true;
        return required == READ && permission && this != READ;
    }

    /** @return the scope for a wire value, if known */
    public static Optional<OAuthScope> fromValue(String raw) {
        if (raw == null) return Optional.empty();
        String v = raw.trim();
        return Arrays.stream(values()).filter(s -> s.value.equals(v)).findFirst();
    }

    /** @return the scopes a protected resource advertises (every permission, no offline_access) */
    public static List<String> resourceScopeValues() {
        List<String> out = new ArrayList<>();
        for (OAuthScope s : values()) {
            if (s.permission) out.add(s.value);
        }
        return out;
    }

    /** @return every wire value, including {@code offline_access} */
    public static List<String> allValues() {
        return Arrays.stream(values()).map(OAuthScope::value).toList();
    }

    /** @return the known scopes among {@code values} (unknown strings ignored, order kept) */
    public static Set<OAuthScope> parse(Collection<String> values) {
        Set<OAuthScope> out = new LinkedHashSet<>();
        if (values == null) return out;
        for (String v : values) {
            fromValue(v).ifPresent(out::add);
        }
        return out;
    }

    /** @return whether any scope in {@code granted} satisfies {@code required} */
    public static boolean satisfies(Collection<OAuthScope> granted, OAuthScope required) {
        if (granted == null) return false;
        return granted.stream().anyMatch(s -> s.implies(required));
    }
}
