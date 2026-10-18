package com.stocka.backend.modules.oauth.web;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.core.endpoint.OAuth2AuthorizationRequest;
import org.springframework.security.oauth2.core.endpoint.OAuth2ParameterNames;
import org.springframework.security.oauth2.server.authorization.OAuth2Authorization;
import org.springframework.security.oauth2.server.authorization.OAuth2AuthorizationConsent;
import org.springframework.security.oauth2.server.authorization.OAuth2AuthorizationConsentService;
import org.springframework.security.oauth2.server.authorization.OAuth2AuthorizationService;
import org.springframework.security.oauth2.server.authorization.OAuth2TokenType;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClient;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClientRepository;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;

import com.stocka.backend.modules.oauth.OAuthScope;
import com.stocka.backend.modules.oauth.OAuthServerConfiguration;
import com.stocka.backend.modules.oauth.client.ClientPresentationService;
import com.stocka.backend.modules.oauth.client.ClientPresentationService.ClientPresentation;
import com.stocka.backend.modules.oauth.client.RedirectUris;
import com.stocka.backend.modules.organizations.entity.Organization;
import com.stocka.backend.modules.organizations.service.OrganizationService;
import com.stocka.backend.modules.users.entity.User;
import com.stocka.backend.modules.users.repository.UserRepository;

/**
 * Consent page of the authorization server. The authorize endpoint redirects here with
 * {@code client_id}, {@code scope} and {@code state}; the page lists the requesting application, the
 * host it will send the user back to, the permissions as checkboxes and the organizations the token
 * will reach. The form posts back to {@code /oauth2/authorize}, where Spring Authorization Server
 * records the approved scopes (a submission without scopes is a denial).
 */
@Controller
public class ConsentController {

    /** One permission row on the page. */
    public record ScopeView(String value, String labelKey, boolean checked, boolean defaultChecked) {
    }

    /** One organization row on the page. */
    public record OrganizationView(String name, String slug, String role) {
    }

    private final RegisteredClientRepository clients;
    private final OAuth2AuthorizationService authorizations;
    private final OAuth2AuthorizationConsentService consents;
    private final ClientPresentationService presentations;
    private final UserRepository users;
    private final OrganizationService organizations;

    public ConsentController(RegisteredClientRepository clients,
                             OAuth2AuthorizationService authorizations,
                             OAuth2AuthorizationConsentService consents,
                             ClientPresentationService presentations,
                             UserRepository users,
                             OrganizationService organizations) {
        this.clients = clients;
        this.authorizations = authorizations;
        this.consents = consents;
        this.presentations = presentations;
        this.users = users;
        this.organizations = organizations;
    }

    @GetMapping(OAuthServerConfiguration.CONSENT_PATH)
    public String consent(@RequestParam(OAuth2ParameterNames.CLIENT_ID) String clientId,
                          @RequestParam(value = OAuth2ParameterNames.SCOPE, required = false) String scope,
                          @RequestParam(OAuth2ParameterNames.STATE) String state,
                          Authentication authentication, Model model) {
        RegisteredClient client = clients.findByClientId(clientId);
        User user = currentUser(authentication);
        if (client == null || user == null) {
            model.addAttribute("messageKey", "oauth.ui.error.client_unknown");
            return "oauth/error";
        }
        ClientPresentation presentation = presentations.of(client);
        Set<String> requested = split(scope);
        Set<String> previouslyApproved = previouslyApproved(client, authentication.getName());

        List<ScopeView> scopes = new ArrayList<>();
        boolean offlineRequested = false;
        for (String value : requested) {
            OAuthScope known = OAuthScope.fromValue(value).orElse(null);
            if (known == null) continue;
            if (!known.isPermission()) {
                offlineRequested = true;
                continue;
            }
            boolean checked = previouslyApproved.contains(value) || known.isCheckedByDefault();
            scopes.add(new ScopeView(value, known.messageKey(), checked, known.isCheckedByDefault()));
        }

        String redirectUri = pendingRedirectUri(state);
        String redirectHost = RedirectUris.parse(redirectUri).map(u -> u.getHost()).orElse(null);

        model.addAttribute("client", presentation);
        model.addAttribute("clientId", clientId);
        model.addAttribute("state", state);
        model.addAttribute("scopes", scopes);
        model.addAttribute("offlineRequested", offlineRequested);
        model.addAttribute("redirectHost", redirectHost);
        model.addAttribute("loopback", RedirectUris.isLoopbackHost(redirectHost));
        model.addAttribute("userName", displayName(user));
        model.addAttribute("userEmail", user.getEmail());
        model.addAttribute("organizations", organizationViews(user));
        model.addAttribute("authorizeUri", "/oauth2/authorize");
        return "oauth/consent";
    }

    private User currentUser(Authentication authentication) {
        if (authentication == null || authentication.getName() == null) return null;
        try {
            return users.findById(Integer.valueOf(authentication.getName())).orElse(null);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private Set<String> previouslyApproved(RegisteredClient client, String principalName) {
        OAuth2AuthorizationConsent consent = consents.findById(client.getId(), principalName);
        Set<String> out = new LinkedHashSet<>();
        if (consent != null) {
            out.addAll(consent.getScopes());
        }
        return out;
    }

    private String pendingRedirectUri(String state) {
        try {
            OAuth2Authorization authorization = authorizations.findByToken(state,
                    new OAuth2TokenType(OAuth2ParameterNames.STATE));
            if (authorization == null) return null;
            OAuth2AuthorizationRequest request = authorization.getAttribute(OAuth2AuthorizationRequest.class.getName());
            return request == null ? null : request.getRedirectUri();
        } catch (RuntimeException e) {
            return null;
        }
    }

    private List<OrganizationView> organizationViews(User user) {
        List<OrganizationView> out = new ArrayList<>();
        for (Organization org : organizations.findUserOrganizations(user)) {
            String role = organizations.getCurrentUserRole(org, user).map(Enum::name).orElse("");
            out.add(new OrganizationView(org.getName(), org.getSlug(), role));
        }
        return out;
    }

    static Set<String> split(String scope) {
        Set<String> out = new LinkedHashSet<>();
        if (scope == null) return out;
        for (String part : scope.trim().split("\\s+")) {
            if (!part.isBlank()) out.add(part);
        }
        return out;
    }

    static String displayName(User user) {
        String name = user.getName() == null ? "" : user.getName().trim();
        String last = user.getLastName() == null ? "" : user.getLastName().trim();
        String full = (name + " " + last).trim();
        return full.isEmpty() ? user.getEmail() : full;
    }
}
