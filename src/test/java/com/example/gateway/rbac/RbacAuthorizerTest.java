package com.example.gateway.rbac;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class RbacAuthorizerTest {

    /** Custom roles as the RBAC API would have stored them; tests change it. */
    private Map<String, List<String>> customRoles = Map.of("orders-auditor", List.of("orders:read"));

    // role -> permissions; a role is held by many users, a user may hold many roles
    private final RbacAuthorizer rbac = new RbacAuthorizer(
            Map.of("orders-reader", List.of("orders:read"),
                    "orders-writer", List.of("orders:read", "orders:write", "orders:delete"),
                    "admin", List.of("routes:write")),
            () -> customRoles,
            (tenant, user) -> switch (tenant + "/" + user) {
                case "acme/usera" -> List.of("orders-reader", "orders-writer");
                case "acme/userb" -> List.of("orders-reader");
                case "acme/auditor" -> List.of("orders-auditor");
                case "null/ops" -> List.of("admin");
                default -> List.of();
            });

    @Test
    void testAllowed_ActiveRoleGrantsPermission_True() {
        assertThat(rbac.allowed(new Subject("acme", "userb", "orders-reader"), Permission.parse("orders:read"))).isTrue();
        assertThat(rbac.allowed(Subject.operator("ops", "admin"), Permission.parse("routes:write"))).isTrue();
    }

    @Test
    void testAllowed_OnlyTheActiveRoleCounts_NotTheUnionOfRoles() {
        // usera holds both roles; acting as reader, delete is denied
        Subject asReader = new Subject("acme", "usera", "orders-reader");

        assertThat(rbac.allowed(asReader, Permission.parse("orders:delete"))).isFalse();
        assertThat(rbac.allowed(new Subject("acme", "usera", "orders-writer"), Permission.parse("orders:delete"))).isTrue();
    }

    @Test
    void testAllowed_RoleNotHeldUnknownOrOtherTenant_False() {
        Permission read = Permission.parse("orders:read");

        assertThat(rbac.allowed(new Subject("acme", "userb", "orders-writer"), read)).isFalse(); // not assigned
        assertThat(rbac.allowed(new Subject("acme", "usera", "superuser"), read)).isFalse();     // undefined role
        assertThat(rbac.allowed(new Subject("globex", "usera", "orders-reader"), read)).isFalse(); // other tenant
        assertThat(rbac.allowed(Subject.operator("usera", "orders-reader"), read)).isFalse();     // not an operator
    }

    @Test
    void testHoldsRoleAndRoleExists() {
        assertThat(rbac.holdsRole(new Subject("acme", "usera", "orders-writer"))).isTrue();
        assertThat(rbac.holdsRole(new Subject("acme", "userb", "orders-writer"))).isFalse();
        assertThat(rbac.roleExists("orders-reader")).isTrue();
        assertThat(rbac.roleExists("superuser")).isFalse();
    }

    @Test
    void testCustomRole_GrantsLikeABuiltIn_AndChangesApplyAtOnce() {
        Subject auditor = new Subject("acme", "auditor", "orders-auditor");

        assertThat(rbac.allowed(auditor, Permission.parse("orders:read"))).isTrue();
        assertThat(rbac.isBuiltIn("orders-auditor")).isFalse();

        customRoles = Map.of(); // the role is deleted through the API

        assertThat(rbac.allowed(auditor, Permission.parse("orders:read"))).isFalse();
        assertThat(rbac.roleExists("orders-auditor")).isFalse();
    }

    @Test
    void testCustomRole_CannotShadowABuiltIn() {
        customRoles = Map.of("admin", List.of("routes:write", "tenants:write", "rbac:write"));

        assertThat(rbac.permissionsOf("admin")).containsExactly(Permission.parse("routes:write"));
    }

    @Test
    void testRoles_ListsBuiltInAndCustom() {
        assertThat(rbac.roles()).containsExactlyInAnyOrder("orders-reader", "orders-writer", "admin", "orders-auditor");
    }

    @Test
    void testConstructor_InvalidPermission_FailsStartup() {
        assertThatThrownBy(() -> new RbacAuthorizer(Map.of("r", List.of("orders")), Map::of, (t, u) -> List.of()))
                .hasMessageContaining("resource:action");
        assertThatThrownBy(() -> new RbacAuthorizer(Map.of("r", List.of("Orders:READ")), Map::of, (t, u) -> List.of()))
                .hasMessageContaining("invalid permission");
    }
}
