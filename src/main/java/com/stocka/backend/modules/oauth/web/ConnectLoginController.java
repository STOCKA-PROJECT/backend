package com.stocka.backend.modules.oauth.web;

import java.util.Optional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClient;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClientRepository;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.security.web.savedrequest.DefaultSavedRequest;
import org.springframework.security.web.savedrequest.RequestCache;
import org.springframework.security.web.savedrequest.SavedRequest;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;

import com.stocka.backend.modules.auth.dto.LoginUserDto;
import com.stocka.backend.modules.auth.service.AuthenticationService;
import com.stocka.backend.modules.auth.service.RefreshTokenService;
import com.stocka.backend.modules.auth.twofactor.TwoFactorService;
import com.stocka.backend.modules.common.error.ApiException;
import com.stocka.backend.modules.common.error.ErrorCodes;
import com.stocka.backend.modules.oauth.client.ClientPresentationService;
import com.stocka.backend.modules.security.audit.SecurityAuditService;
import com.stocka.backend.modules.security.audit.SecurityEventType;
import com.stocka.backend.modules.security.config.RefreshTokenProperties;
import com.stocka.backend.modules.users.entity.User;
import com.stocka.backend.modules.users.repository.UserRepository;

import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;

/**
 * Browser login of the authorization server ({@code /auth/connect/login}, {@code /auth/connect/2fa},
 * {@code /auth/connect/continue}).
 *
 * <p>Reuses the credentials check, rate limits, email-verified gate and two-factor verification of
 * the regular login, then stores a session-scoped {@link org.springframework.security.core.Authentication}
 * whose name is the user id (the {@code sub} of every token) and returns to the authorization request
 * saved by the authorize endpoint.
 *
 * <p>It lives under {@code /auth} on purpose: that is the path of the SPA's refresh cookie, so a
 * browser that is already signed in to Stocka is recognised ({@link RefreshTokenService#peek}) and
 * offered "continue as …" without retyping the password. The consent page is still shown.
 */
@Controller
@RequestMapping("/auth/connect")
public class ConnectLoginController {

    private static final Logger log = LoggerFactory.getLogger(ConnectLoginController.class);
    static final String SESSION_PENDING_2FA = "stocka.oauth.pending2fa";

    private final AuthenticationService authenticationService;
    private final TwoFactorService twoFactorService;
    private final RefreshTokenService refreshTokens;
    private final RefreshTokenProperties refreshProperties;
    private final UserRepository users;
    private final SecurityAuditService audit;
    private final SecurityContextRepository securityContextRepository;
    private final RequestCache requestCache;
    private final RegisteredClientRepository clients;
    private final ClientPresentationService presentations;
    private final String frontendBaseUrl;

    public ConnectLoginController(AuthenticationService authenticationService,
                                  TwoFactorService twoFactorService,
                                  RefreshTokenService refreshTokens,
                                  RefreshTokenProperties refreshProperties,
                                  UserRepository users,
                                  SecurityAuditService audit,
                                  SecurityContextRepository securityContextRepository,
                                  RequestCache requestCache,
                                  RegisteredClientRepository clients,
                                  ClientPresentationService presentations,
                                  @Value("${app.frontend.base-url}") String frontendBaseUrl) {
        this.authenticationService = authenticationService;
        this.twoFactorService = twoFactorService;
        this.refreshTokens = refreshTokens;
        this.refreshProperties = refreshProperties;
        this.users = users;
        this.audit = audit;
        this.securityContextRepository = securityContextRepository;
        this.requestCache = requestCache;
        this.clients = clients;
        this.presentations = presentations;
        this.frontendBaseUrl = frontendBaseUrl;
    }

    @GetMapping("/login")
    public String loginForm(@RequestParam(value = "other", required = false) String other,
                            HttpServletRequest request, HttpServletResponse response, Model model) {
        model.addAttribute("clientName", pendingClientName(request, response));
        if (other == null) {
            sessionUser(request).ifPresent(user -> {
                model.addAttribute("knownUserName", displayName(user));
                model.addAttribute("knownUserEmail", user.getEmail());
            });
        }
        return "oauth/login";
    }

    @PostMapping("/login")
    public String login(@RequestParam("email") String email,
                        @RequestParam("password") String password,
                        HttpServletRequest request, HttpServletResponse response, Model model) {
        User user;
        try {
            user = authenticationService.authenticate(new LoginUserDto().setEmail(email.trim()).setPassword(password));
        } catch (AuthenticationException ex) {
            return loginError(request, response, model, email, "oauth.ui.login.error_credentials");
        } catch (ApiException ex) {
            String key = ErrorCodes.AUTH_EMAIL_NOT_VERIFIED.equals(ex.getCode())
                    ? "oauth.ui.login.error_unverified" : "oauth.ui.login.error_generic";
            return loginError(request, response, model, email, key);
        }
        if (user.isTwoFactorEnabled()) {
            request.getSession(true).setAttribute(SESSION_PENDING_2FA, user.getId());
            return "redirect:/auth/connect/2fa";
        }
        return complete(user, request, response, "password");
    }

    /** Continues with the Stocka session the browser already has (refresh cookie), no password. */
    @PostMapping("/continue")
    public String continueWithSession(HttpServletRequest request, HttpServletResponse response) {
        Optional<User> user = sessionUser(request);
        if (user.isEmpty()) {
            return "redirect:/auth/connect/login?other";
        }
        return complete(user.get(), request, response, "session");
    }

    @GetMapping("/2fa")
    public String twoFactorForm(HttpServletRequest request) {
        if (pendingUser(request) == null) {
            return "redirect:/auth/connect/login";
        }
        return "oauth/twofactor";
    }

    @PostMapping("/2fa")
    public String twoFactor(@RequestParam("code") String code, HttpServletRequest request,
                            HttpServletResponse response, Model model) {
        User user = pendingUser(request);
        if (user == null) {
            return "redirect:/auth/connect/login";
        }
        if (!twoFactorService.verifyChallenge(user, code == null ? "" : code.trim())) {
            audit.recordFailure(SecurityEventType.TWO_FACTOR_CHALLENGE_FAILED, user, user.getEmail());
            model.addAttribute("error", "oauth.ui.twofactor.error");
            return "oauth/twofactor";
        }
        return complete(user, request, response, "password");
    }

    private String loginError(HttpServletRequest request, HttpServletResponse response, Model model,
                              String email, String messageKey) {
        model.addAttribute("error", messageKey);
        model.addAttribute("email", email);
        model.addAttribute("clientName", pendingClientName(request, response));
        return "oauth/login";
    }

    private User pendingUser(HttpServletRequest request) {
        HttpSession session = request.getSession(false);
        Object id = session == null ? null : session.getAttribute(SESSION_PENDING_2FA);
        if (!(id instanceof Integer userId)) return null;
        return users.findById(userId).filter(User::isTwoFactorEnabled).orElse(null);
    }

    /** @return the user of the SPA refresh cookie, when the browser carries a live one */
    private Optional<User> sessionUser(HttpServletRequest request) {
        Cookie[] cookies = request.getCookies();
        if (cookies == null) return Optional.empty();
        for (Cookie cookie : cookies) {
            if (refreshProperties.getCookieName().equals(cookie.getName())) {
                try {
                    return refreshTokens.peek(cookie.getValue());
                } catch (RuntimeException ex) {
                    return Optional.empty();
                }
            }
        }
        return Optional.empty();
    }

    private String complete(User user, HttpServletRequest request, HttpServletResponse response, String via) {
        HttpSession session = request.getSession(true);
        session.removeAttribute(SESSION_PENDING_2FA);
        request.changeSessionId();

        UserDetails principal = org.springframework.security.core.userdetails.User
                .withUsername(String.valueOf(user.getId()))
                .password("n/a")
                .authorities(user.getAuthorities())
                .build();
        UsernamePasswordAuthenticationToken authentication =
                UsernamePasswordAuthenticationToken.authenticated(principal, null, principal.getAuthorities());
        SecurityContext context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(authentication);
        SecurityContextHolder.setContext(context);
        securityContextRepository.saveContext(context, request, response);
        audit.recordSuccess(SecurityEventType.LOGIN_SUCCESS, user, "{\"channel\":\"oauth\",\"via\":\"" + via + "\"}");
        log.info("oauth_login user={} via={}", user.getId(), via);

        SavedRequest saved = requestCache.getRequest(request, response);
        String target = saved == null ? frontendBaseUrl : saved.getRedirectUrl();
        return "redirect:" + target;
    }

    /** Name of the client whose authorization request is waiting, when it can be resolved. */
    private String pendingClientName(HttpServletRequest request, HttpServletResponse response) {
        SavedRequest saved = requestCache.getRequest(request, response);
        if (!(saved instanceof DefaultSavedRequest defaultSaved)) return null;
        String[] ids = defaultSaved.getParameterValues("client_id");
        if (ids == null || ids.length != 1) return null;
        try {
            RegisteredClient client = clients.findByClientId(ids[0]);
            return client == null ? null : presentations.of(client).name();
        } catch (RuntimeException ex) {
            return null;
        }
    }

    static String displayName(User user) {
        String name = user.getName() == null ? "" : user.getName().trim();
        String last = user.getLastName() == null ? "" : user.getLastName().trim();
        String full = (name + " " + last).trim();
        return full.isEmpty() ? user.getEmail() : full;
    }
}
