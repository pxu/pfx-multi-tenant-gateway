package com.example.gateway.rbac;

import java.util.List;

/**
 * Where user → role assignments come from. The RBAC module asks; the application decides where they
 * live (today: operators in config, tenant users in the tenant's config; a dedicated store later).
 */
@FunctionalInterface
public interface RoleAssignments {

    /** The roles {@code user} holds; {@code tenant} is {@code null} for operators. Empty if unknown. */
    List<String> rolesOf(String tenant, String user);
}
