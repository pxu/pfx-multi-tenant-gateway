package com.example.gateway.admin;

import com.example.gateway.rbac.Permission;
import java.util.Set;

/**
 * The management API's permissions, as compile-time constants so {@code @RequiresPermission} gets a
 * typo checked by the compiler. They are ordinary {@code resource:action} permissions: the RBAC module
 * does not know them, roles in {@code gateway.rbac.roles} grant them like any other.
 */
public final class AdminPermissions {

    public static final String TENANTS_READ = "tenants:read";
    public static final String TENANTS_WRITE = "tenants:write";
    public static final String TOKENS_ISSUE = "tokens:issue";
    public static final String TOKENS_REVOKE = "tokens:revoke";
    public static final String ROUTES_READ = "routes:read";
    public static final String ROUTES_WRITE = "routes:write";
    public static final String RBAC_READ = "rbac:read";
    public static final String RBAC_WRITE = "rbac:write";

    /**
     * The resources above. Upstream services may not use these names, or their data-plane permissions
     * (e.g. {@code routes:write}) would mean the same as a management permission.
     */
    public static final Set<String> RESOURCES = Set.of("tenants", "tokens", "routes", "rbac");

    /** A permission on the management API (as opposed to an upstream service). */
    public static boolean isManagement(Permission permission) {
        return RESOURCES.contains(permission.resource());
    }

    private AdminPermissions() {}
}
