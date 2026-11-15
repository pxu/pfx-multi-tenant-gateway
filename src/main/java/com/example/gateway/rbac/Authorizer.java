package com.example.gateway.rbac;

import java.util.Set;

/**
 * The RBAC module's API: the policy decision point. The gateway (the enforcement point) only asks
 * these questions and acts on the answer; it does not know how roles, permissions or assignments
 * are stored. Today the implementation runs in-process ({@link RbacAuthorizer}); a remote RBAC
 * service would be another implementation of this interface, with no change to the callers.
 */
public interface Authorizer {

    /** Every role name, built-in and custom. */
    Set<String> roles();

    /** Is {@code role} defined (built-in or custom)? */
    boolean roleExists(String role);

    /** Built-in roles come from config and cannot be changed through the RBAC API. */
    boolean isBuiltIn(String role);

    /** The role's permissions; empty if the role does not exist. */
    Set<Permission> permissionsOf(String role);

    /** Is the subject's user currently assigned the subject's role? */
    boolean holdsRole(Subject subject);

    /** Deny by default: the user holds the role, and the role grants {@code permission}. */
    boolean allowed(Subject subject, Permission permission);
}
