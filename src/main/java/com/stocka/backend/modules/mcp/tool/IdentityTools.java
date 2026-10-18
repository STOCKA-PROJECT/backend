package com.stocka.backend.modules.mcp.tool;

import java.util.ArrayList;
import java.util.List;

import org.springframework.stereotype.Component;

import com.stocka.backend.modules.oauth.OAuthScope;
import com.stocka.backend.modules.organizations.entity.Organization;
import com.stocka.backend.modules.organizations.security.OrganizationSecurity;
import com.stocka.backend.modules.organizations.service.OrganizationService;
import com.stocka.backend.modules.search.service.SemanticSearchService;
import com.stocka.backend.modules.users.entity.User;

/**
 * Who am I: the starting point of every conversation. Returns the user, the organizations with the
 * user's role and feature flags, the scopes of this connection and the client name.
 */
@Component
public class IdentityTools implements ToolSource {

    /** No arguments. */
    public record NoInput() {
    }

    public record UserView(
            @Param("User id") Integer id,
            @Param("Email") String email,
            @Param("Display name") String name,
            @Param("Username") String username,
            @Param("Preferred language (es, ca, en)") String language,
            @Param("Whether the user is a global administrator") boolean globalAdmin) {
    }

    public record OrganizationView(
            @Param("Organization id") Integer id,
            @Param("Name") String name,
            @Param("Slug: pass it as `org` to every organization tool") String slug,
            @Param("The user's role: OWNER, MANAGER, USER or SPECTATOR (read-only)") String role,
            @Param("Whether ports and piece-type actions (hardware features) are available") boolean hardwareEnabled,
            @Param("Whether `pieces_search` ranks results semantically for this organization") boolean semanticSearch) {
    }

    public record MeResult(
            @Param("The authenticated user") UserView user,
            @Param("Organizations the user belongs to") List<OrganizationView> organizations,
            @Param("Scopes granted to this connection") List<String> scopes,
            @Param("Name of the connected client application") String client) {
    }

    private final OrganizationService organizations;
    private final OrganizationSecurity security;
    private final SemanticSearchService search;

    public IdentityTools(OrganizationService organizations, OrganizationSecurity security, SemanticSearchService search) {
        this.organizations = organizations;
        this.security = security;
        this.search = search;
    }

    @Override
    public List<ToolDefinition<?>> tools() {
        return List.of(
                ToolDefinition.of("me",
                        "Returns the authenticated user, the organizations they belong to (with slug, role and "
                                + "feature flags) and the scopes of this connection. Call it first to learn which "
                                + "organization slugs to use.",
                        NoInput.class, OAuthScope.READ, ToolHints.READ, this::me).returning(MeResult.class));
    }

    private MeResult me(ToolCall<NoInput> call) {
        User user = call.caller().user();
        List<OrganizationView> orgs = new ArrayList<>();
        for (Organization org : organizations.findUserOrganizations(user)) {
            String role = organizations.getCurrentUserRole(org, user).map(Enum::name).orElse("");
            boolean hardware = security.portsEnabled(org, user);
            boolean semantic = false;
            try {
                var status = search.status(org);
                semantic = status.enabled() && status.indexed() && status.indexedPieces() > 0;
            } catch (RuntimeException ignored) {
                // search status is best-effort; the organization is still usable
            }
            orgs.add(new OrganizationView(org.getId(), org.getName(), org.getSlug(), role, hardware, semantic));
        }
        UserView view = new UserView(user.getId(), user.getEmail(), displayName(user), user.getUsernameValue(),
                user.getLanguage() == null ? null : user.getLanguage().name().toLowerCase(),
                OrganizationSecurity.isGlobalAdmin(user));
        List<String> scopes = call.caller().scopes().stream().map(OAuthScope::value).toList();
        return new MeResult(view, orgs, scopes, call.caller().clientName());
    }

    static String displayName(User user) {
        String name = user.getName() == null ? "" : user.getName().trim();
        String last = user.getLastName() == null ? "" : user.getLastName().trim();
        String full = (name + " " + last).trim();
        return full.isEmpty() ? user.getEmail() : full;
    }
}
