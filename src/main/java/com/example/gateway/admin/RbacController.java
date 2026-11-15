package com.example.gateway.admin;

import com.example.gateway.auth.RequiresPermission;
import com.example.gateway.error.ErrorCode;
import com.example.gateway.error.GatewayException;
import com.example.gateway.rbac.Authorizer;
import com.example.gateway.rbac.Permission;
import com.example.gateway.store.ConfigStore;
import com.example.gateway.store.Route;
import com.example.gateway.store.Tenant;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * The RBAC management API: custom roles (role → permissions) and tenant users' role assignments
 * (user → roles). Built-in roles come from config and are read-only here. Operators and their roles
 * stay in config, next to their keys.
 *
 * <p>Custom roles hold service permissions only ({@code orders:read}); management permissions
 * ({@code routes:write}, {@code rbac:write}, ...) exist only in built-in roles, which only operators
 * hold. So {@code rbac:write} hands out access to services, never to the management API, and there
 * is no way to escalate through it. Tenant users may only be given roles without management
 * permissions, since they never call the management API.
 */
@RestController
@RequestMapping("/admin/rbac")
public class RbacController {

    public record RoleView(String name, List<String> permissions, boolean builtIn) {}

    public record RoleBody(@NotEmpty List<@NotBlank String> permissions) {}

    public record AssignmentBody(@NotEmpty List<@NotBlank String> roles) {}

    private final ConfigStore store;
    private final Authorizer authorizer;

    public RbacController(ConfigStore store, Authorizer authorizer) {
        this.store = store;
        this.authorizer = authorizer;
    }

    // ---- roles -------------------------------------------------------------------------------

    @GetMapping("/roles")
    @RequiresPermission(AdminPermissions.RBAC_READ)
    public List<RoleView> roles() {
        return authorizer.roles().stream().sorted().map(this::view).toList();
    }

    @PutMapping("/roles/{role}")
    @RequiresPermission(AdminPermissions.RBAC_WRITE)
    public RoleView putRole(@PathVariable String role, @Valid @RequestBody RoleBody body) {
        if (!role.matches(Route.ID)) {
            throw new GatewayException(ErrorCode.VALIDATION_ERROR, "role name must match " + Route.ID);
        }
        if (authorizer.isBuiltIn(role)) {
            throw new GatewayException(ErrorCode.CONFLICT, "role '" + role + "' is built in and cannot be changed");
        }
        List<String> permissions = new ArrayList<>(new TreeSet<>(body.permissions()));
        for (String value : permissions) {
            if (AdminPermissions.isManagement(parse(value))) {
                throw new GatewayException(ErrorCode.VALIDATION_ERROR,
                        value + " is a management permission; custom roles may only hold service permissions");
            }
        }
        store.putRole(role, permissions);
        return new RoleView(role, permissions, false);
    }

    /** 409 while the role is still assigned: deleting it would silently take access away. */
    @DeleteMapping("/roles/{role}")
    @RequiresPermission(AdminPermissions.RBAC_WRITE)
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deleteRole(@PathVariable String role) {
        if (authorizer.isBuiltIn(role)) {
            throw new GatewayException(ErrorCode.CONFLICT, "role '" + role + "' is built in and cannot be deleted");
        }
        List<String> holders = store.listAllAssignments().entrySet().stream()
                .filter(e -> e.getValue().contains(role)).map(Map.Entry::getKey).sorted().toList();
        if (!holders.isEmpty()) {
            throw new GatewayException(ErrorCode.CONFLICT, "role '" + role + "' is still assigned to " + holders);
        }
        store.deleteRole(role);
    }

    // ---- tenant users' assignments -------------------------------------------------------------

    @GetMapping("/tenants/{tenant}/users")
    @RequiresPermission(AdminPermissions.RBAC_READ)
    public Map<String, List<String>> assignments(@PathVariable String tenant) {
        requireTenant(tenant);
        return store.listAssignments(tenant);
    }

    /** Replaces the user's roles. */
    @PutMapping("/tenants/{tenant}/users/{user}")
    @RequiresPermission(AdminPermissions.RBAC_WRITE)
    public Map<String, List<String>> assign(@PathVariable String tenant, @PathVariable String user,
            @Valid @RequestBody AssignmentBody body) {
        requireTenant(tenant);
        if (!user.matches(Tenant.ID)) {
            throw new GatewayException(ErrorCode.VALIDATION_ERROR, "user id must match " + Tenant.ID);
        }
        List<String> roles = new ArrayList<>(new TreeSet<>(body.roles()));
        for (String role : roles) {
            if (!authorizer.roleExists(role)) {
                throw new GatewayException(ErrorCode.VALIDATION_ERROR, "unknown role '" + role + "'");
            }
            if (authorizer.permissionsOf(role).stream().anyMatch(AdminPermissions::isManagement)) {
                throw new GatewayException(ErrorCode.VALIDATION_ERROR,
                        "role '" + role + "' has management permissions; tenant users cannot hold it");
            }
        }
        store.putAssignment(tenant, user, roles);
        return Map.of(user, roles);
    }

    @DeleteMapping("/tenants/{tenant}/users/{user}")
    @RequiresPermission(AdminPermissions.RBAC_WRITE)
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void unassign(@PathVariable String tenant, @PathVariable String user) {
        store.deleteAssignment(tenant, user);
    }

    // ---- helpers -------------------------------------------------------------------------------

    private RoleView view(String role) {
        List<String> permissions = authorizer.permissionsOf(role).stream().map(Permission::toString).sorted().toList();
        return new RoleView(role, permissions, authorizer.isBuiltIn(role));
    }

    private void requireTenant(String tenant) {
        if (store.findTenant(tenant).isEmpty()) {
            throw new GatewayException(ErrorCode.NOT_FOUND, "tenant '" + tenant + "' not found");
        }
    }

    private static Permission parse(String value) {
        try {
            return Permission.parse(value);
        } catch (IllegalArgumentException e) {
            throw new GatewayException(ErrorCode.VALIDATION_ERROR, e.getMessage());
        }
    }
}
