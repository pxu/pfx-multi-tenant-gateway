package com.example.gateway.policy;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.startsWith;
import static org.mockito.Mockito.inOrder;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.example.gateway.auth.TokenService.Principal;
import com.example.gateway.error.ErrorCode;
import com.example.gateway.error.GatewayException;
import com.example.gateway.rbac.RbacAuthorizer;
import com.example.gateway.store.Route;
import com.example.gateway.store.Tenant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class PolicyEngineTest {

    private final RateLimiter rateLimiter = mock(RateLimiter.class);
    /** user -> roles in tenant acme; tests change it to revoke roles. */
    private final Map<String, List<String>> acmeUsers = new HashMap<>(Map.of(
            "rita", List.of("orders-reader"), "will", List.of("orders-writer")));
    private final PolicyEngine engine = new PolicyEngine(rateLimiter, new RbacAuthorizer(
            Map.of("orders-reader", List.of("orders:read"),
                    "orders-writer", List.of("orders:read", "orders:write", "orders:delete")),
            Map::of, (tenant, user) -> "acme".equals(tenant) ? acmeUsers.getOrDefault(user, List.of()) : List.of()));

    private final Tenant acme = tenant(List.of("/orders"), Set.of("orders", "billing"));
    private final Route orders = new Route("orders", "/orders", List.of(new Route.Upstream("http://upstream", 1)), null);
    private final Principal reader = new Principal("acme", "rita", "orders-reader", Set.of("orders"), "t-1");
    private final Principal writer = new Principal("acme", "will", "orders-writer", Set.of("orders"), "t-1");

    private static Tenant tenant(List<String> paths, Set<String> services) {
        return new Tenant("acme", "Acme", false, 100, 10, paths, services);
    }

    @BeforeEach
    void quotaAvailable() {
        when(rateLimiter.tryAcquire(anyString(), anyInt(), anyInt())).thenReturn(new RateLimiter.Result(true, 99, 0));
    }

    private ErrorCode rejection(Tenant tenant, Principal caller, Route route, String method, String path) {
        try {
            engine.enforce(tenant, caller, route, method, path);
            return null;
        } catch (GatewayException e) {
            return e.code();
        }
    }

    @Test
    void testEnforce_AllChecksPass_ReturnsRateLimitHeaders() {
        // Act
        Map<String, String> headers = engine.enforce(acme, reader, orders, "GET", "/orders/1");

        // Assert
        assertThat(headers).containsEntry("X-RateLimit-Limit", "100").containsEntry("X-RateLimit-Remaining", "99");
    }

    @Test
    void testEnforce_PathOutsideAllowlist_DeniedWithoutTouchingQuota() {
        assertThat(rejection(acme, reader, orders, "GET", "/billing/1")).isEqualTo(ErrorCode.PATH_NOT_ALLOWED);
        verify(rateLimiter, never()).tryAcquire(anyString(), anyInt(), anyInt());
    }

    @Test
    void testEnforce_PrefixWithoutSegmentBoundary_Denied() {
        assertThat(rejection(acme, reader, orders, "GET", "/ordersX")).isEqualTo(ErrorCode.PATH_NOT_ALLOWED);
    }

    @Test
    void testEnforce_EmptyAllowlist_DeniesEverything() {
        assertThat(rejection(tenant(List.of(), Set.of("orders")), reader, orders, "GET", "/orders"))
                .isEqualTo(ErrorCode.PATH_NOT_ALLOWED);
    }

    @Test
    void testEnforce_TokenWithoutServiceScope_DeniedWithoutTouchingQuota() {
        Principal billingOnly = new Principal("acme", "will", "orders-writer", Set.of("billing"), "t-2");

        assertThat(rejection(acme, billingOnly, orders, "GET", "/orders")).isEqualTo(ErrorCode.INSUFFICIENT_SCOPE);
        verify(rateLimiter, never()).tryAcquire(anyString(), anyInt(), anyInt());
    }

    @Test
    void testEnforce_ServiceRemovedFromTenantAfterIssuance_Denied() {
        // The token still says orders, but the tenant may no longer reach it.
        assertThat(rejection(tenant(List.of("/orders"), Set.of("billing")), reader, orders, "GET", "/orders"))
                .isEqualTo(ErrorCode.INSUFFICIENT_SCOPE);
    }

    @Test
    void testEnforce_ActionByMethod_OnlyWhatTheRoleAllows() {
        // reader: read only
        assertThat(rejection(acme, reader, orders, "GET", "/orders/1")).isNull();
        assertThat(rejection(acme, reader, orders, "HEAD", "/orders/1")).isNull();
        assertThat(rejection(acme, reader, orders, "POST", "/orders")).isEqualTo(ErrorCode.PERMISSION_DENIED);
        assertThat(rejection(acme, reader, orders, "DELETE", "/orders/1")).isEqualTo(ErrorCode.PERMISSION_DENIED);
        // writer: read, write, delete
        assertThat(rejection(acme, writer, orders, "PATCH", "/orders/1")).isNull();
        assertThat(rejection(acme, writer, orders, "DELETE", "/orders/1")).isNull();
    }

    @Test
    void testEnforce_PermissionDenied_DoesNotTouchQuota() {
        rejection(acme, reader, orders, "DELETE", "/orders/1");

        verify(rateLimiter, never()).tryAcquire(anyString(), anyInt(), anyInt());
    }

    @Test
    void testEnforce_RoleRevokedAfterIssuance_DeniedOnNextRequest() {
        // Arrange: rita's token says orders-reader, then the assignment is removed
        acmeUsers.put("rita", List.of());

        // Act + Assert
        assertThat(rejection(acme, reader, orders, "GET", "/orders/1")).isEqualTo(ErrorCode.PERMISSION_DENIED);
    }

    @Test
    void testEnforce_ServiceDefaultsToRouteIdOrUsesExplicitName() {
        Route billingRoute = new Route("invoices", "/orders", List.of(new Route.Upstream("http://upstream", 1)), "billing");

        assertThat(orders.service()).isEqualTo("orders");
        assertThat(rejection(acme, reader, billingRoute, "GET", "/orders")).isEqualTo(ErrorCode.INSUFFICIENT_SCOPE);
    }

    @Test
    void testAction_HttpMethods_MappedToReadWriteDelete() {
        assertThat(PolicyEngine.action("GET")).isEqualTo("read");
        assertThat(PolicyEngine.action("OPTIONS")).isEqualTo("read");
        assertThat(PolicyEngine.action("PUT")).isEqualTo("write");
        assertThat(PolicyEngine.action("DELETE")).isEqualTo("delete");
        assertThat(PolicyEngine.action("delete")).isEqualTo("delete"); // not "write"
    }

    @Test
    void testEnforce_QuotaExceeded_Denied429WithRetryAfter() {
        when(rateLimiter.tryAcquire("tenant:acme", 100, 10)).thenReturn(new RateLimiter.Result(false, 0, 7));

        try {
            engine.enforce(acme, reader, orders, "GET", "/orders");
        } catch (GatewayException e) {
            assertThat(e.code()).isEqualTo(ErrorCode.RATE_LIMITED);
            assertThat(e.headers()).containsEntry("Retry-After", "7");
            return;
        }
        throw new AssertionError("expected 429");
    }

    @Test
    void testEnforce_BurstUnset_BucketHoldsOneSecondOfQuota() {
        Tenant unset = new Tenant("acme", "Acme", false, 600, 0, List.of("/orders"), Set.of("orders"));

        engine.enforce(unset, reader, orders, "GET", "/orders");

        verify(rateLimiter).tryAcquire("tenant:acme", 600, 10); // 600/min -> 10 at once, not 600
    }

    @Test
    void testEnforce_UserOverItsLimit_Rejected429BeforeTouchingTheTenantQuota() {
        // Arrange: rita may send 60/min (one per second); her bucket is empty
        Tenant perUser = new Tenant("acme", "Acme", false, 100, 10, List.of("/orders"), Set.of("orders"), 60);
        when(rateLimiter.tryAcquire("user:acme/rita", 60, 1)).thenReturn(new RateLimiter.Result(false, 0, 1));

        // Act + Assert
        assertThat(rejection(perUser, reader, orders, "GET", "/orders")).isEqualTo(ErrorCode.USER_RATE_LIMITED);
        verify(rateLimiter, never()).tryAcquire(eq("tenant:acme"), anyInt(), anyInt());
    }

    @Test
    void testEnforce_UserWithinItsLimit_TenantQuotaAppliesToo() {
        Tenant perUser = new Tenant("acme", "Acme", false, 100, 10, List.of("/orders"), Set.of("orders"), 60);
        when(rateLimiter.tryAcquire("user:acme/rita", 60, 1)).thenReturn(new RateLimiter.Result(true, 0, 0));

        engine.enforce(perUser, reader, orders, "GET", "/orders");

        verify(rateLimiter).tryAcquire("tenant:acme", 100, 10);
    }

    @Test
    void testEnforce_NoUserLimit_OnlyTheTenantBucket() {
        engine.enforce(acme, reader, orders, "GET", "/orders");

        verify(rateLimiter, never()).tryAcquire(startsWith("user:"), anyInt(), anyInt());
    }

    @Test
    void testEnforce_RateLimiterFailedOpen_NoRateLimitHeaders() {
        when(rateLimiter.tryAcquire("tenant:acme", 100, 10)).thenReturn(new RateLimiter.Result(true, -1, 0));

        assertThat(engine.enforce(acme, reader, orders, "GET", "/orders")).isEmpty();
    }

    @Test
    void testEnforce_RouteOverItsLimit_Rejected429AfterTheTenantQuota() {
        // Arrange: a route limited to 60/min, burst 5; its bucket is empty
        Route limited = new Route("orders", "/orders", List.of(new Route.Upstream("http://upstream", 1)), null, false, 0, 60, 5);
        when(rateLimiter.tryAcquire(eq("route:orders"), anyInt(), anyInt())).thenReturn(new RateLimiter.Result(false, 0, 2));

        // Act
        Throwable rejected = catchThrowable(() -> engine.enforce(acme, reader, limited, "GET", "/orders/1"));

        // Assert: 429 with Retry-After; one bucket for the whole route (no tenant in the key), checked last
        assertThat(rejected).isInstanceOfSatisfying(GatewayException.class, e -> {
            assertThat(e.code()).isEqualTo(ErrorCode.ROUTE_RATE_LIMITED);
            assertThat(e.headers()).containsEntry("Retry-After", "2");
        });
        var order = inOrder(rateLimiter);
        order.verify(rateLimiter).tryAcquire("tenant:acme", 100, 10);
        order.verify(rateLimiter).tryAcquire("route:orders", 60, 5);
    }

    @Test
    void testEnforce_RouteWithoutLimit_NoRouteBucket_BurstDefaultsToOneSecond() {
        engine.enforce(acme, reader, orders, "GET", "/orders/1");
        verify(rateLimiter, never()).tryAcquire(eq("route:orders"), anyInt(), anyInt());

        Route unsetBurst = new Route("orders", "/orders", List.of(new Route.Upstream("http://upstream", 1)), null, false, 0, 600, 0);
        engine.enforce(acme, reader, unsetBurst, "GET", "/orders/1");
        verify(rateLimiter).tryAcquire("route:orders", 600, 10);
    }

    @Test
    void testEnforce_TenantQuotaRejects_RouteBucketUntouched() {
        Route limited = new Route("orders", "/orders", List.of(new Route.Upstream("http://upstream", 1)), null, false, 0, 60, 5);
        when(rateLimiter.tryAcquire(eq("tenant:acme"), anyInt(), anyInt())).thenReturn(new RateLimiter.Result(false, 0, 1));

        assertThat(rejection(acme, reader, limited, "GET", "/orders/1")).isEqualTo(ErrorCode.RATE_LIMITED);
        verify(rateLimiter, never()).tryAcquire(eq("route:orders"), anyInt(), anyInt());
    }
}
