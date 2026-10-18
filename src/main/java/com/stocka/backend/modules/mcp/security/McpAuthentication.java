package com.stocka.backend.modules.mcp.security;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;

import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;

import com.stocka.backend.modules.oauth.OAuthScope;
import com.stocka.backend.modules.users.entity.User;

/**
 * The authentication behind an MCP call: the Stocka {@link User} the token was issued to (so every
 * {@code @orgSecurity} check and every service that reads the current user keeps working), the
 * granted scopes and the client name. Authorities are the user's roles plus {@code SCOPE_<scope>}.
 */
public class McpAuthentication extends AbstractAuthenticationToken {

    private final User user;
    private final Set<OAuthScope> scopes;
    private final String clientName;
    private final String tokenId;

    public McpAuthentication(User user, Set<OAuthScope> scopes, String clientName, String tokenId) {
        super(authorities(user, scopes));
        this.user = user;
        this.scopes = Collections.unmodifiableSet(scopes);
        this.clientName = clientName;
        this.tokenId = tokenId;
        setAuthenticated(true);
    }

    private static List<GrantedAuthority> authorities(User user, Set<OAuthScope> scopes) {
        List<GrantedAuthority> out = new ArrayList<>(user.getAuthorities());
        for (OAuthScope scope : scopes) {
            out.add(new SimpleGrantedAuthority("SCOPE_" + scope.value()));
        }
        return out;
    }

    @Override
    public Object getCredentials() {
        return null;
    }

    @Override
    public Object getPrincipal() {
        return user;
    }

    @Override
    public String getName() {
        return String.valueOf(user.getId());
    }

    public User getUser() { return user; }
    public Set<OAuthScope> getScopes() { return scopes; }
    public String getClientName() { return clientName; }
    public String getTokenId() { return tokenId; }

    /** @return whether a granted scope satisfies {@code required} */
    public boolean hasScope(OAuthScope required) {
        return OAuthScope.satisfies(scopes, required);
    }
}
