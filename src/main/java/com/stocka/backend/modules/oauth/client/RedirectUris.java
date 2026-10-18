package com.stocka.backend.modules.oauth.client;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.Locale;
import java.util.Optional;

/**
 * Rules for client redirect URIs.
 *
 * <p>OAuth 2.1 requires exact matching, with one exception: loopback redirects of native clients may
 * use any port. MCP clients such as Claude Code bind an ephemeral port on {@code localhost}, so
 * {@code localhost} is treated like {@code 127.0.0.1} and {@code [::1]} here, even though the
 * OAuth 2.1 draft discourages the name.
 */
public final class RedirectUris {

    private RedirectUris() {
    }

    /** @return whether {@code host} is a loopback address or {@code localhost} */
    public static boolean isLoopbackHost(String host) {
        if (host == null) return false;
        String h = host.toLowerCase(Locale.ROOT);
        return h.equals("localhost") || h.equals("127.0.0.1") || h.equals("[::1]") || h.equals("::1");
    }

    /**
     * Whether a URI may be registered: {@code https} with a host, or {@code http} on a loopback host.
     * Fragments are never allowed.
     */
    public static boolean isRegistrable(String uri) {
        Optional<URI> parsed = parse(uri);
        if (parsed.isEmpty()) return false;
        URI u = parsed.get();
        if (u.getFragment() != null || u.getHost() == null || u.getScheme() == null) return false;
        String scheme = u.getScheme().toLowerCase(Locale.ROOT);
        if (scheme.equals("https")) return true;
        return scheme.equals("http") && isLoopbackHost(u.getHost());
    }

    /**
     * Whether {@code requested} matches {@code registered}: exact string match, or equal ignoring the
     * port when both point at a loopback host.
     */
    public static boolean matches(String registered, String requested) {
        if (registered == null || requested == null) return false;
        if (registered.equals(requested)) return true;
        Optional<URI> a = parse(registered);
        Optional<URI> b = parse(requested);
        if (a.isEmpty() || b.isEmpty()) return false;
        URI ra = a.get();
        URI rb = b.get();
        if (!isLoopbackHost(ra.getHost()) || !isLoopbackHost(rb.getHost())) return false;
        return eq(ra.getScheme(), rb.getScheme())
                && eq(loopbackName(ra.getHost()), loopbackName(rb.getHost()))
                && eq(ra.getPath(), rb.getPath())
                && eq(ra.getQuery(), rb.getQuery());
    }

    private static String loopbackName(String host) {
        // localhost and 127.0.0.1 are interchangeable for native clients.
        return host == null ? null : host.toLowerCase(Locale.ROOT);
    }

    private static boolean eq(String a, String b) {
        return a == null ? b == null || b.isEmpty() : (b != null && a.equals(b)) || (a.isEmpty() && b == null);
    }

    /** @return the parsed URI, or empty when blank or malformed */
    public static Optional<URI> parse(String uri) {
        if (uri == null || uri.isBlank()) return Optional.empty();
        try {
            return Optional.of(new URI(uri.trim()));
        } catch (URISyntaxException e) {
            return Optional.empty();
        }
    }
}
