package com.example.gateway.rbac;

import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.Supplier;
import java.util.stream.Collectors;

/**
 * In-process RBAC: role → permissions, user → roles. Two kinds of role:
 * <ul>
 *   <li><b>built-in</b>, from {@code gateway.rbac.roles}: read-only, so nobody can lock everyone out
 *       by editing or deleting {@code admin} through the API;
 *   <li><b>custom</b>, created through the RBAC API and read from memory ({@code customRoles}).
 * </ul>
 * Everything is in memory, so a decision costs no I/O. The assignment is checked on every decision,
 * so removing a user's role takes effect on their next request, not when their session or token expires.
 */
public final class RbacAuthorizer implements Authorizer {

    private final Map<String, Set<String>> builtIn;
    private final Supplier<Map<String, List<String>>> customRoles;
    private final RoleAssignments assignments;

    /** @throws IllegalArgumentException on an invalid built-in permission (fails startup) */
    public RbacAuthorizer(Map<String, List<String>> builtInRoles, Supplier<Map<String, List<String>>> customRoles,
            RoleAssignments assignments) {
        this.builtIn = builtInRoles.entrySet().stream().collect(Collectors.toUnmodifiableMap(
                Map.Entry::getKey,
                e -> e.getValue().stream().map(Permission::parse).map(Permission::toString)
                        .collect(Collectors.toUnmodifiableSet())));
        this.customRoles = customRoles;
        this.assignments = assignments;
    }

    /** Permission strings of a role; built-ins win, so a custom role can never shadow one. */
    private Optional<? extends Collection<String>> lookup(String role) {
        Set<String> fixed = builtIn.get(role);
        return fixed != null ? Optional.of(fixed) : Optional.ofNullable(customRoles.get().get(role));
    }

    @Override
    public Set<String> roles() {
        Set<String> names = new TreeSet<>(customRoles.get().keySet());
        names.addAll(builtIn.keySet());
        return Collections.unmodifiableSet(names);
    }

    @Override
    public boolean roleExists(String role) {
        return lookup(role).isPresent();
    }

    @Override
    public boolean isBuiltIn(String role) {
        return builtIn.containsKey(role);
    }

    @Override
    public Set<Permission> permissionsOf(String role) {
        return lookup(role).map(p -> p.stream().map(Permission::parse).collect(Collectors.toUnmodifiableSet()))
                .orElse(Set.of());
    }

    @Override
    public boolean holdsRole(Subject subject) {
        return roleExists(subject.role()) && assignments.rolesOf(subject.tenant(), subject.user()).contains(subject.role());
    }

    @Override
    public boolean allowed(Subject subject, Permission permission) {
        // One lookup for the role, so a snapshot swapped in between cannot make it disappear halfway.
        return lookup(subject.role())
                .filter(p -> p.contains(permission.toString()))
                .isPresent()
                && assignments.rolesOf(subject.tenant(), subject.user()).contains(subject.role());
    }
}
