package com.stocka.backend.modules.mcp.tool;

import java.util.Set;

import com.stocka.backend.modules.oauth.OAuthScope;
import com.stocka.backend.modules.users.entity.User;

/**
 * Who is calling a tool: the Stocka user the token belongs to, the scopes the user granted and the
 * name of the connected client.
 *
 * @param user       authenticated user
 * @param scopes     granted scopes
 * @param clientName display name of the OAuth client, may be {@code null}
 */
public record McpCaller(User user, Set<OAuthScope> scopes, String clientName) {

    /** @return whether a granted scope satisfies {@code required} */
    public boolean has(OAuthScope required) {
        return OAuthScope.satisfies(scopes, required);
    }
}
