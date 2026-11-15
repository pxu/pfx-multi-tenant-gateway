package com.example.gateway.auth;

import com.example.gateway.Paths;
import com.example.gateway.audit.AuditRecord;
import com.example.gateway.auth.TokenService.Failure;
import com.example.gateway.auth.TokenService.InvalidToken;
import com.example.gateway.auth.TokenService.Principal;
import com.example.gateway.error.ErrorCode;
import com.example.gateway.error.GatewayException;
import com.example.gateway.rbac.Subject;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.function.Predicate;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Authentication, second filter after {@code AuditFilter}. Two separate trust domains:
 * <ul>
 *   <li>{@code /admin/**}: an RBAC session token from {@code POST /admin/login} (the only admin
 *       path open without one). The verified {@link Subject} (user + active role) goes
 *       on the request; {@code PermissionInterceptor} checks each endpoint's permission.
 *       Tenant tokens are never accepted here.
 *   <li>Everything else (except other reserved paths): a tenant bearer token. On success the
 *       verified {@link Principal} is put on the request for the proxy to use. A token whose id has
 *       been revoked is rejected (checked only after the signature, so junk tokens cost no lookup).
 * </ul>
 */
public class AuthFilter extends OncePerRequestFilter {

    private static final Pattern BEARER = Pattern.compile("^Bearer ([A-Za-z0-9._~+/=-]+)$", Pattern.CASE_INSENSITIVE);

    public static final String LOGIN_PATH = "/admin/login";

    private final TokenService tokens;
    private final SessionService sessions;
    private final Predicate<String> revoked;

    /** @param revoked token id → revoked? ({@code ConfigStore::isRevoked}) */
    public AuthFilter(TokenService tokens, SessionService sessions, Predicate<String> revoked) {
        this.tokens = tokens;
        this.sessions = sessions;
        this.revoked = revoked;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        AuditRecord audit = AuditRecord.of(request);
        String path = audit.path();
        if (Paths.matches(path, "/admin")) {
            audit.tenant("admin");
            if (!path.equals(LOGIN_PATH)) {
                Subject session;
                try {
                    session = sessions.verify(bearerToken(request));
                } catch (InvalidToken e) {
                    audit.detail("auth:" + e.failure);
                    throw new GatewayException(ErrorCode.ADMIN_UNAUTHORIZED, e.failure.publicMessage);
                }
                audit.actor(session.user(), session.role());
                request.setAttribute(Subject.ATTRIBUTE, session);
            }
        } else if (!Paths.isReserved(path)) {
            Principal principal;
            try {
                principal = tokens.verify(bearerToken(request));
            } catch (InvalidToken e) {
                audit.detail("auth:" + e.failure);
                throw unauthenticated(e.failure);
            }
            audit.tenant(principal.tenantId());
            if (revoked.test(principal.tokenId())) {
                audit.detail("auth:" + Failure.REVOKED);
                throw unauthenticated(Failure.REVOKED);
            }
            request.setAttribute(Principal.ATTRIBUTE, principal);
        }
        chain.doFilter(request, response);
    }

    private static String bearerToken(HttpServletRequest request) throws InvalidToken {
        List<String> headers = Collections.list(request.getHeaders("Authorization"));
        if (headers.isEmpty()) {
            throw new InvalidToken(Failure.MISSING_TOKEN);
        }
        Matcher m = BEARER.matcher(headers.getFirst().trim());
        if (headers.size() > 1 || !m.matches()) { // two Authorization headers: layers could disagree
            throw new InvalidToken(Failure.MALFORMED);
        }
        return m.group(1);
    }

    /** 401 with the RFC 6750 challenge header. */
    static GatewayException unauthenticated(Failure failure) {
        String challenge = failure == Failure.MISSING_TOKEN
                ? "Bearer realm=\"gateway\""
                : "Bearer realm=\"gateway\", error=\"invalid_token\"";
        return new GatewayException(ErrorCode.UNAUTHENTICATED, failure.publicMessage, Map.of("WWW-Authenticate", challenge));
    }
}
