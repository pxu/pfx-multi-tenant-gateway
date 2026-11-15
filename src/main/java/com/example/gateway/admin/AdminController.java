package com.example.gateway.admin;

import com.example.gateway.Paths;
import com.example.gateway.audit.AuditRecord;
import com.example.gateway.auth.SessionService;
import com.example.gateway.auth.RequiresPermission;
import com.example.gateway.auth.TokenService;
import com.example.gateway.error.ErrorCode;
import com.example.gateway.error.GatewayException;
import com.example.gateway.rbac.Authorizer;
import com.example.gateway.rbac.Subject;
import com.example.gateway.store.ConfigStore;
import com.example.gateway.store.Route;
import com.example.gateway.store.Tenant;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import java.time.Duration;
import java.util.List;
import java.util.Set;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * Control plane: login, tenants, routes and token issuance. Every endpoint except login declares
 * the permission it needs; the session's active role must have it (see {@code PermissionInterceptor}).
 */
@RestController
@RequestMapping("/admin")
public class AdminController {

    /**
     * A token for one of the tenant's users, acting as one of that user's roles. Empty scopes = all the
     * tenant's grantable services; no ttl = the configured default.
     */
    public record TokenRequest(@NotBlank String user, @NotBlank String role,
            Set<@Pattern(regexp = Tenant.SCOPE) String> scopes, @Positive Long ttlSeconds) {}


    /** Log in as one of your roles. */
    public record LoginRequest(@NotBlank String user, @NotBlank String key, @NotBlank String role) {}

    private final ConfigStore store;
    /** What {@link TokenService} issues (a UUID), loosely: the length bounds the database key. */
    private static final String TOKEN_ID = "[A-Za-z0-9-]{1,36}";

    private final TokenService tokens;
    private final UpstreamUrlValidator upstreamUrls;
    private final SessionService sessions;
    private final Authorizer authorizer;

    public AdminController(ConfigStore store, TokenService tokens, UpstreamUrlValidator upstreamUrls, SessionService sessions,
            Authorizer authorizer) {
        this.store = store;
        this.tokens = tokens;
        this.upstreamUrls = upstreamUrls;
        this.sessions = sessions;
        this.authorizer = authorizer;
    }

    // ---- login (the only admin endpoint that needs no session) -------------------------------

    @PostMapping("/login")
    public SessionService.Issued login(@Valid @RequestBody LoginRequest login, HttpServletRequest request) {
        AuditRecord audit = AuditRecord.of(request);
        audit.actor(login.user(), login.role()); // audited even when it fails (brute-force forensics)
        SessionService.Issued issued = sessions.login(login.user(), login.key(), login.role());
        audit.allow();
        return issued;
    }

    // ---- tenants ------------------------------------------------------------------------------

    @GetMapping("/tenants")
    @RequiresPermission(AdminPermissions.TENANTS_READ)
    public List<Tenant> tenants() {
        return store.listTenants();
    }

    @GetMapping("/tenants/{id}")
    @RequiresPermission(AdminPermissions.TENANTS_READ)
    public Tenant tenant(@PathVariable String id) {
        return store.findTenant(id).orElseThrow(() -> new GatewayException(ErrorCode.NOT_FOUND, "tenant '" + id + "' not found"));
    }

    @PostMapping("/tenants")
    @RequiresPermission(AdminPermissions.TENANTS_WRITE)
    @ResponseStatus(HttpStatus.CREATED)
    public Tenant createTenant(@Valid @RequestBody Tenant tenant) {
        requireId(tenant.id());
        checkBurst(tenant);
        store.createTenant(tenant);
        return tenant;
    }

    @PutMapping("/tenants/{id}")
    @RequiresPermission(AdminPermissions.TENANTS_WRITE)
    public Tenant updateTenant(@PathVariable String id, @Valid @RequestBody Tenant tenant) {
        checkBurst(tenant);
        Tenant updated = tenant.withId(id);
        store.updateTenant(updated);
        return updated;
    }

    @DeleteMapping("/tenants/{id}")
    @RequiresPermission(AdminPermissions.TENANTS_WRITE)
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deleteTenant(@PathVariable String id) {
        store.deleteTenant(id);
    }

    @PostMapping("/tenants/{id}/tokens")
    @RequiresPermission(AdminPermissions.TOKENS_ISSUE)
    @ResponseStatus(HttpStatus.CREATED)
    public TokenService.IssuedToken issueToken(@PathVariable String id, @Valid @RequestBody TokenRequest request) {
        Duration ttl = request.ttlSeconds() == null ? null : Duration.ofSeconds(request.ttlSeconds());
        Tenant tenant = tenant(id); // 404 first
        if (!authorizer.holdsRole(new Subject(id, request.user(), request.role()))) {
            throw new GatewayException(ErrorCode.VALIDATION_ERROR,
                    "user '" + request.user() + "' of tenant '" + id + "' does not hold role '" + request.role() + "'");
        }
        return tokens.issue(tenant, request.user(), request.role(), request.scopes(), ttl);
    }

    /**
     * Revokes a tenant token by its id ({@code tokenId} from issuance, the JWT's {@code jti}). The
     * gateway does not keep issued tokens, so it cannot tell an unknown id from a known one: always
     * 204. Kept until no token issued so far could still be valid ({@code jwt.max-ttl}).
     */
    @PostMapping("/tokens/{tokenId}/revoke")
    @RequiresPermission(AdminPermissions.TOKENS_REVOKE)
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void revokeToken(@PathVariable String tokenId) {
        if (!tokenId.matches(TOKEN_ID)) {
            throw new GatewayException(ErrorCode.VALIDATION_ERROR, "not a token id: " + tokenId);
        }
        store.revokeToken(tokenId, tokens.latestExpiry());
    }

    // ---- routes -------------------------------------------------------------------------------

    @GetMapping("/routes")
    @RequiresPermission(AdminPermissions.ROUTES_READ)
    public List<Route> routes() {
        return store.listRoutes();
    }

    @GetMapping("/routes/{id}")
    @RequiresPermission(AdminPermissions.ROUTES_READ)
    public Route route(@PathVariable String id) {
        return store.findRoute(id).orElseThrow(() -> new GatewayException(ErrorCode.NOT_FOUND, "route '" + id + "' not found"));
    }

    @PostMapping("/routes")
    @RequiresPermission(AdminPermissions.ROUTES_WRITE)
    @ResponseStatus(HttpStatus.CREATED)
    public Route createRoute(@Valid @RequestBody Route route) {
        requireId(route.id());
        checkRouteLimit(route);
        checkPrefix(route.pathPrefix());
        checkService(route);
        route.upstreams().forEach(u -> upstreamUrls.validate(u.url()));
        store.createRoute(route);
        return route;
    }

    @PutMapping("/routes/{id}")
    @RequiresPermission(AdminPermissions.ROUTES_WRITE)
    public Route updateRoute(@PathVariable String id, @Valid @RequestBody Route route) {
        Route updated = route.withId(id);
        checkRouteLimit(updated);
        checkPrefix(updated.pathPrefix());
        checkService(updated);
        updated.upstreams().forEach(u -> upstreamUrls.validate(u.url()));
        store.updateRoute(updated);
        return updated;
    }

    @DeleteMapping("/routes/{id}")
    @RequiresPermission(AdminPermissions.ROUTES_WRITE)
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deleteRoute(@PathVariable String id) {
        store.deleteRoute(id);
    }

    // ---- validation ---------------------------------------------------------------------------

    private static void requireId(String id) {
        if (id == null) {
            throw new GatewayException(ErrorCode.VALIDATION_ERROR, "id: is required");
        }
    }

    /** A bucket larger than the minute's quota would let a tenant spend more than a minute at once. */
    private static void checkService(Route route) {
        if (AdminPermissions.RESOURCES.contains(route.service())) {
            throw new GatewayException(ErrorCode.VALIDATION_ERROR, "service name is reserved: " + AdminPermissions.RESOURCES);
        }
    }

    private static void checkRouteLimit(Route route) {
        if (route.burst() > route.requestsPerMinute()) {
            throw new GatewayException(ErrorCode.VALIDATION_ERROR, "burst must not exceed requestsPerMinute");
        }
    }

    private static void checkBurst(Tenant tenant) {
        if (tenant.burst() > tenant.requestsPerMinute()) {
            throw new GatewayException(ErrorCode.VALIDATION_ERROR, "burst must not exceed requestsPerMinute");
        }
        if (tenant.userRequestsPerMinute() > tenant.requestsPerMinute()) {
            throw new GatewayException(ErrorCode.VALIDATION_ERROR, "userRequestsPerMinute must not exceed requestsPerMinute");
        }
    }

    /** Prefixes must already be canonical (what the router compares against) and not shadow the control plane. */
    private static void checkPrefix(String prefix) {
        boolean canonical;
        try {
            canonical = Paths.normalize(prefix).equals(prefix) && (prefix.equals("/") || !prefix.endsWith("/"));
        } catch (GatewayException e) {
            canonical = false;
        }
        if (!canonical) {
            throw new GatewayException(ErrorCode.VALIDATION_ERROR, "pathPrefix must be a canonical path without a trailing '/'");
        }
        if (!prefix.equals("/") && Paths.isReserved(prefix)) {
            throw new GatewayException(ErrorCode.VALIDATION_ERROR, "pathPrefix is reserved: " + Paths.RESERVED);
        }
    }
}
