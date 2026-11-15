package com.example.gateway;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.any;
import static com.github.tomakehurst.wiremock.client.WireMock.anyRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.anyUrl;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.equalToJson;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathMatching;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.options;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.example.gateway.policy.ConcurrencyLimiter;
import com.example.gateway.routing.RendezvousHash;
import com.example.gateway.store.Route;
import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.http.Fault;
import com.github.tomakehurst.wiremock.stubbing.Scenario;
import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.actuate.observability.AutoConfigureObservability;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.boot.test.web.server.LocalManagementPort;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.ActiveProfiles;

/**
 * End to end over real HTTP: test -> gateway (random port) -> WireMock upstream.
 * Every rejection test also checks that the upstream received nothing and that the audit line says
 * DENY with the right reason.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = "management.server.port=0")
@AutoConfigureObservability // tests disable metrics exporters by default; we assert on /actuator/prometheus
@ActiveProfiles("test")
@ExtendWith(OutputCaptureExtension.class)
class GatewayIT {

    static final WireMockServer UPSTREAM = new WireMockServer(options().dynamicPort());
    static final AtomicInteger IDS = new AtomicInteger();

    static {
        UPSTREAM.start(); // lives for the whole JVM, like the cached Spring context
    }

    @LocalServerPort
    int port;

    @LocalManagementPort
    int managementPort;

    @Autowired
    ObjectMapper json;

    @Autowired
    ConcurrencyLimiter concurrency;

    @Autowired
    io.micrometer.core.instrument.MeterRegistry meters;

    @Autowired
    com.example.gateway.proxy.UpstreamProxy upstreamProxy;

    private final HttpClient http = HttpClient.newHttpClient();

    @BeforeEach
    void resetUpstream() {
        UPSTREAM.resetAll();
        UPSTREAM.stubFor(any(anyUrl()).willReturn(aResponse().withStatus(200).withBody("ok")));
    }

    // ---- happy path ---------------------------------------------------------------------------

    @Test
    void testProxy_AllowedRequest_ForwardedFaithfully(CapturedOutput output) throws Exception {
        // Arrange
        Fixture f = fixture(List.of("/"), 100);
        UPSTREAM.stubFor(any(urlPathMatching(".*/orders/7")).willReturn(aResponse().withStatus(201)
                .withHeader("X-Upstream", "yes").withHeader("Content-Type", "application/json").withBody("{\"id\":7}")));

        // Act
        HttpResponse<String> response = send(HttpRequest.newBuilder(gateway(f.prefix + "/orders/7?expand=items"))
                .header("Authorization", "Bearer " + f.token)
                .header("Content-Type", "application/json")
                .header("X-Tenant-Id", "someone-else") // spoofing attempts
                .header("X-User-Id", "root")
                .header("X-Role", "admin")
                .header("X-HTTP-Method-Override", "DELETE") // would turn this POST into a delete upstream
                .POST(HttpRequest.BodyPublishers.ofString("{\"qty\":2}")));

        // Assert: the client gets exactly what the upstream sent...
        assertThat(response.statusCode()).isEqualTo(201);
        assertThat(response.body()).isEqualTo("{\"id\":7}");
        assertThat(response.headers().firstValue("X-Upstream")).contains("yes");
        assertThat(response.headers().firstValue("X-RateLimit-Remaining")).contains("99");
        // ...and the upstream got the request with the gateway's identity headers and without the credential.
        UPSTREAM.verify(postRequestedFor(urlEqualTo(f.prefix + "/orders/7?expand=items"))
                .withRequestBody(equalToJson("{\"qty\":2}"))
                .withHeader("Content-Type", equalTo("application/json"))
                .withHeader("X-Tenant-Id", equalTo(f.tenant))
                .withHeader("X-User-Id", equalTo("usera"))
                .withHeader("X-Role", equalTo("orders-writer"))
                .withHeader("X-Request-Id", equalTo(requestId(response)))
                .withoutHeader("X-HTTP-Method-Override")
                .withoutHeader("Authorization"));
        JsonNode audit = audit(output, response);
        assertThat(audit.get("decision").asText()).isEqualTo("ALLOW");
        assertThat(audit.get("tenantId").asText()).isEqualTo(f.tenant);
        assertThat(audit.get("actor").asText()).isEqualTo("usera");
        assertThat(audit.get("clientIp").asText()).isEqualTo("127.0.0.1"); // the TCP peer: no trusted proxies configured
        assertThat(audit.get("role").asText()).isEqualTo("orders-writer");
        assertThat(audit.get("routeId").asText()).isEqualTo(f.route);
        assertThat(audit.get("upstream").asText()).isEqualTo(upstreamUrl());
        assertThat(audit.get("upstreamStatus").asInt()).isEqualTo(201);
        assertThat(audit.get("latencyMs").asDouble()).isPositive();
    }

    @Test
    void testProxy_UpstreamErrorStatus_PassedThroughUnchanged() throws Exception {
        Fixture f = fixture(List.of("/"), 100);
        UPSTREAM.stubFor(any(anyUrl()).willReturn(aResponse().withStatus(404).withBody("no such order")));

        HttpResponse<String> response = get(f.prefix + "/orders/404", f.token);

        assertThat(response.statusCode()).isEqualTo(404);
        assertThat(response.body()).isEqualTo("no such order");
    }

    // ---- authentication -----------------------------------------------------------------------

    @Test
    void testProxy_MissingOrInvalidToken_Rejected401(CapturedOutput output) throws Exception {
        Fixture f = fixture(List.of("/"), 100);
        String tampered = f.token.substring(0, f.token.length() - 4) + "AAAA";

        HttpResponse<String> missing = get(f.prefix + "/x", null);
        HttpResponse<String> invalid = get(f.prefix + "/x", tampered);

        assertRejected(output, missing, 401, "UNAUTHENTICATED");
        assertThat(missing.headers().firstValue("WWW-Authenticate")).contains("Bearer realm=\"gateway\"");
        assertRejected(output, invalid, 401, "UNAUTHENTICATED");
        assertThat(audit(output, invalid).get("detail").asText()).isEqualTo("auth:INVALID_SIGNATURE");
    }

    @Test
    void testAdmin_NoSessionOrTenantToken_Rejected401(CapturedOutput output) throws Exception {
        Fixture f = fixture(List.of("/"), 100);

        assertRejected(output, get("/admin/tenants", null), 401, "ADMIN_UNAUTHORIZED");
        assertRejected(output, get("/admin/tenants", f.token), 401, "ADMIN_UNAUTHORIZED");
    }

    // ---- admin RBAC: user -> roles -> permissions, one role per login ---------------------------

    @Test
    void testAdmin_LoginWrongKeyOrUnassignedRole_Rejected(CapturedOutput output) throws Exception {
        HttpResponse<String> wrongKey = login("vera", "guess", "viewer");
        HttpResponse<String> notHerRole = login("vera", "test-vera-key", "admin");

        assertRejected(output, wrongKey, 401, "ADMIN_UNAUTHORIZED");
        assertRejected(output, notHerRole, 403, "PERMISSION_DENIED");
        assertThat(audit(output, notHerRole).get("actor").asText()).isEqualTo("vera");
    }

    @Test
    void testAdmin_SameUserDifferentRole_PermissionsOfActiveRoleOnly(CapturedOutput output) throws Exception {
        // Arrange: vera holds viewer and operator; she logs in once as each
        Fixture f = fixture(List.of("/"), 100);
        String asViewer = sessionToken(login("vera", "test-vera-key", "viewer"));
        String asOperator = sessionToken(login("vera", "test-vera-key", "operator"));

        // Act
        HttpResponse<String> read = adminAs(asViewer, "GET", "/admin/routes/" + f.route, null);
        HttpResponse<String> deleteAsViewer = adminAs(asViewer, "DELETE", "/admin/routes/" + f.route, null);
        HttpResponse<String> issueAsOperator = adminAs(asOperator, "POST", "/admin/tenants/" + f.tenant + "/tokens", Map.of());

        // Assert: the viewer session reads, but cannot write: refused before the handler, route still there
        assertThat(read.statusCode()).isEqualTo(200);
        JsonNode allowed = audit(output, read);
        assertThat(allowed.get("decision").asText()).isEqualTo("ALLOW");
        assertThat(allowed.get("actor").asText()).isEqualTo("vera");
        assertThat(allowed.get("role").asText()).isEqualTo("viewer");
        assertRejected(output, deleteAsViewer, 403, "PERMISSION_DENIED");
        assertThat(admin("GET", "/admin/routes/" + f.route, null).statusCode()).isEqualTo(200);
        // ...and operator has no tokens:issue, whatever other roles vera holds
        assertRejected(output, issueAsOperator, 403, "PERMISSION_DENIED");
        assertThat(adminAs(asOperator, "DELETE", "/admin/routes/" + f.route, null).statusCode()).isEqualTo(204);
    }

    @Test
    void testRevokeToken_TokenInUse_NextRequestRejected401(CapturedOutput output) throws Exception {
        // Arrange: a token that has been used (this instance has cached it as "not revoked")
        Fixture f = fixture(List.of("/"), 100);
        JsonNode issued = json.readTree(admin("POST", "/admin/tenants/" + f.tenant + "/tokens",
                Map.of("user", "usera", "role", "orders-writer")).body());
        String token = issued.get("token").asText();
        assertThat(get(f.prefix + "/x", token).statusCode()).isEqualTo(200);
        UPSTREAM.resetRequests();

        // Act
        HttpResponse<String> revoke = admin("POST", "/admin/tokens/" + issued.get("tokenId").asText() + "/revoke", null);
        HttpResponse<String> response = get(f.prefix + "/x", token);

        // Assert: refused at once, other tokens of the same user unaffected
        assertThat(revoke.statusCode()).isEqualTo(204);
        assertRejected(output, response, 401, "UNAUTHENTICATED");
        assertThat(response.headers().firstValue("WWW-Authenticate")).hasValueSatisfying(v -> assertThat(v).contains("invalid_token"));
        assertThat(audit(output, response).get("detail").asText()).isEqualTo("auth:REVOKED");
        assertThat(get(f.prefix + "/x", f.token).statusCode()).isEqualTo(200);
    }

    @Test
    void testRevokeToken_TwiceOrUnknownIs204_BadIdIs400_ViewerIs403(CapturedOutput output) throws Exception {
        // Arrange
        String viewer = sessionToken(login("vera", "test-vera-key", "viewer"));
        String id = java.util.UUID.randomUUID().toString();

        // Act + Assert: the gateway keeps no list of issued tokens, so any well-formed id is accepted
        assertThat(admin("POST", "/admin/tokens/" + id + "/revoke", null).statusCode()).isEqualTo(204);
        assertThat(admin("POST", "/admin/tokens/" + id + "/revoke", null).statusCode()).isEqualTo(204);
        assertThat(problemCode(admin("POST", "/admin/tokens/" + "x".repeat(37) + "/revoke", null))).isEqualTo("VALIDATION_ERROR");
        assertRejected(output, adminAs(viewer, "POST", "/admin/tokens/" + id + "/revoke", null), 403, "PERMISSION_DENIED");
    }

    @Test
    void testProxy_AdminSessionAsTenantToken_Rejected401(CapturedOutput output) throws Exception {
        Fixture f = fixture(List.of("/"), 100);

        assertRejected(output, get(f.prefix + "/x", adminSession()), 401, "UNAUTHENTICATED");
    }

    @Test
    void testAdmin_TokenWithServiceTenantCannotReach_Rejected400() throws Exception {
        Fixture f = fixture(List.of("/"), 100);

        HttpResponse<String> response = admin("POST", "/admin/tenants/" + f.tenant + "/tokens",
                Map.of("user", "usera", "role", "orders-writer", "scopes", List.of("payroll")));

        assertThat(response.statusCode()).isEqualTo(400);
        assertThat(problemCode(response)).isEqualTo("VALIDATION_ERROR");
    }

    @Test
    void testAdmin_TokenForRoleTheUserDoesNotHold_Rejected400() throws Exception {
        Fixture f = fixture(List.of("/"), 100);

        HttpResponse<String> response = admin("POST", "/admin/tenants/" + f.tenant + "/tokens",
                Map.of("user", "rita", "role", "orders-writer"));

        assertThat(response.statusCode()).isEqualTo(400);
        assertThat(json.readTree(response.body()).get("detail").asText()).contains("does not hold role");
    }

    @Test
    void testAdmin_TenantUserWithUnknownRoleOrReservedService_Rejected400() throws Exception {
        Fixture f = fixture(List.of("/"), 100);
        HttpResponse<String> unknownRole = admin("PUT", "/admin/rbac/tenants/" + f.tenant + "/users/usera",
                Map.of("roles", List.of("superuser")));
        Map<String, Object> route = new java.util.HashMap<>(routeBody("r" + IDS.incrementAndGet(), "/r" + IDS.get(), upstreamUrl()));
        route.put("service", "routes");

        assertThat(unknownRole.statusCode()).isEqualTo(400);
        assertThat(admin("POST", "/admin/routes", route).statusCode()).isEqualTo(400);
    }

    // ---- RBAC management API ------------------------------------------------------------------

    @Test
    void testRbac_CustomRoleLifecycle_CreateAssignUseAndDelete(CapturedOutput output) throws Exception {
        // Arrange: a custom role, assigned to a new user of the fixture tenant
        Fixture f = fixture(List.of("/"), 100);
        String role = "auditor-" + IDS.incrementAndGet();
        String users = "/admin/rbac/tenants/" + f.tenant + "/users/";
        assertThat(admin("PUT", "/admin/rbac/roles/" + role, Map.of("permissions", List.of("orders:read"))).statusCode())
                .isEqualTo(200);
        assertThat(admin("PUT", users + "audrey", Map.of("roles", List.of(role))).statusCode()).isEqualTo(200);
        String audrey = token(f.tenant, "audrey", role, List.of());

        // Act + Assert: the custom role works like a built-in one
        assertThat(get(f.prefix + "/orders/1", audrey).statusCode()).isEqualTo(200);
        assertThat(send(HttpRequest.newBuilder(gateway(f.prefix + "/orders/1"))
                .header("Authorization", "Bearer " + audrey).DELETE()).statusCode()).isEqualTo(403);
        JsonNode roles = json.readTree(admin("GET", "/admin/rbac/roles", null).body());
        assertThat(roles.findValuesAsText("name")).contains(role, "admin", "orders-reader");
        assertThat(json.readTree(admin("GET", users.substring(0, users.length() - 1), null).body()).has("audrey")).isTrue();

        // ...cannot be deleted while assigned; once unassigned it can, and access ends
        assertThat(admin("DELETE", "/admin/rbac/roles/" + role, null).statusCode()).isEqualTo(409);
        assertThat(admin("DELETE", users + "audrey", null).statusCode()).isEqualTo(204);
        assertThat(admin("DELETE", "/admin/rbac/roles/" + role, null).statusCode()).isEqualTo(204);
        UPSTREAM.resetRequests();
        assertRejected(output, get(f.prefix + "/orders/1", audrey), 403, "PERMISSION_DENIED");
    }

    @Test
    void testRbac_BuiltInRoles_ReadOnly() throws Exception {
        assertThat(admin("PUT", "/admin/rbac/roles/viewer", Map.of("permissions", List.of("routes:write"))).statusCode())
                .isEqualTo(409);
        assertThat(admin("DELETE", "/admin/rbac/roles/admin", null).statusCode()).isEqualTo(409);
    }

    @Test
    void testRbac_ManagementPermissionsStayInBuiltInRoles_NoEscalationThroughRbacWrite() throws Exception {
        // Arrange
        Fixture f = fixture(List.of("/"), 100);
        String role = "r" + IDS.incrementAndGet();

        // Act
        HttpResponse<String> managementRole = admin("PUT", "/admin/rbac/roles/" + role,
                Map.of("permissions", List.of("orders:read", "routes:write")));
        HttpResponse<String> tenantUserAsViewer = admin("PUT", "/admin/rbac/tenants/" + f.tenant + "/users/usera",
                Map.of("roles", List.of("viewer")));

        // Assert: a custom role cannot carry routes:write, and a tenant user cannot hold a management role
        assertThat(managementRole.statusCode()).isEqualTo(400);
        assertThat(json.readTree(managementRole.body()).get("detail").asText()).contains("management permission");
        assertThat(admin("GET", "/admin/rbac/roles", null).body()).doesNotContain("\"" + role + "\"");
        assertThat(tenantUserAsViewer.statusCode()).isEqualTo(400);
    }

    @Test
    void testRbac_ViewerReadsButCannotWrite(CapturedOutput output) throws Exception {
        String viewer = sessionToken(login("vera", "test-vera-key", "viewer"));

        assertThat(adminAs(viewer, "GET", "/admin/rbac/roles", null).statusCode()).isEqualTo(200);
        assertRejected(output, adminAs(viewer, "PUT", "/admin/rbac/roles/x" + IDS.incrementAndGet(),
                Map.of("permissions", List.of("orders:read"))), 403, "PERMISSION_DENIED");
    }

    // ---- policy -------------------------------------------------------------------------------

    @Test
    void testProxy_PathNotInAllowlist_Rejected403(CapturedOutput output) throws Exception {
        Fixture f = fixture(List.of("/only-this"), 100);

        assertRejected(output, get(f.prefix + "/x", f.token), 403, "PATH_NOT_ALLOWED");
    }

    @Test
    void testProxy_TokenWithoutTheRoutesService_Rejected403(CapturedOutput output) throws Exception {
        Fixture f = fixture(List.of("/"), 100);
        String billingOnly = token(f.tenant, "usera", "orders-writer", List.of("billing"));

        assertRejected(output, get(f.prefix + "/x", billingOnly), 403, "INSUFFICIENT_SCOPE");
    }

    @Test
    void testProxy_UserOverItsLimit_Rejected429_TenantQuotaUntouched(CapturedOutput output) throws Exception {
        // Arrange: tenant 100/min (burst 100), each user 60/min, i.e. a user bucket of 1
        Fixture f = fixture(List.of("/"), 100);
        Map<String, Object> perUser = new java.util.HashMap<>(tenantBody(f.tenant, false, List.of("/"), 100, 100));
        perUser.put("userRequestsPerMinute", 60);
        assertThat(admin("PUT", "/admin/tenants/" + f.tenant, perUser).statusCode()).isEqualTo(200);
        String rita = token(f.tenant, "rita", "orders-reader", List.of());

        // Act: rita's second request in the same second is over her limit; usera is unaffected
        assertThat(get(f.prefix + "/orders/1", rita).statusCode()).isEqualTo(200);
        UPSTREAM.resetRequests();
        HttpResponse<String> overLimit = get(f.prefix + "/orders/1", rita);
        HttpResponse<String> other = get(f.prefix + "/orders/1", f.token);

        // Assert: 429 for rita only, and her rejected request did not spend tenant quota (100 - 2 = 98 left)
        assertThat(overLimit.statusCode()).isEqualTo(429);
        assertThat(problemCode(overLimit)).isEqualTo("USER_RATE_LIMITED");
        assertThat(audit(output, overLimit).get("reason").asText()).isEqualTo("USER_RATE_LIMITED");
        assertThat(other.statusCode()).isEqualTo(200);
        assertThat(other.headers().firstValue("X-RateLimit-Remaining")).contains("98");
    }

    @Test
    void testAdmin_UserLimitAboveTenantLimit_Rejected400() throws Exception {
        Fixture f = fixture(List.of("/"), 100);
        Map<String, Object> body = new java.util.HashMap<>(tenantBody(f.tenant, false, List.of("/"), 100, 100));
        body.put("userRequestsPerMinute", 101);

        assertThat(admin("PUT", "/admin/tenants/" + f.tenant, body).statusCode()).isEqualTo(400);
    }

    @Test
    void testProxy_TenantAtItsConcurrencyLimit_Rejected429_OthersUnaffected(CapturedOutput output) throws Exception {
        // Arrange: a slow upstream, and a tenant allowed one request in flight
        WireMockServer slow = newUpstream();
        slow.stubFor(any(anyUrl()).willReturn(aResponse().withStatus(200).withFixedDelay(300)));
        Fixture f = fixture(List.of("/"), 100);
        Map<String, Object> oneAtATime = new java.util.HashMap<>(tenantBody(f.tenant, false, List.of("/"), 100, 100));
        oneAtATime.put("maxConcurrentRequests", 1);
        admin("PUT", "/admin/tenants/" + f.tenant, oneAtATime);
        admin("PUT", "/admin/routes/" + f.route, routeBody(f.route, f.prefix, "http://localhost:" + slow.port()));
        Fixture neighbour = fixture(List.of("/"), 100);

        // Act: the first request is in flight (held by the slow upstream) when the second arrives
        var first = http.sendAsync(HttpRequest.newBuilder(gateway(f.prefix + "/a")).header("Authorization", "Bearer " + f.token)
                .timeout(Duration.ofSeconds(10)).GET().build(), HttpResponse.BodyHandlers.ofString());
        await().atMost(Duration.ofSeconds(5)).until(() -> concurrency.inFlight(f.tenant) == 1);
        HttpResponse<String> second = get(f.prefix + "/b", f.token);
        HttpResponse<String> neighbours = get(neighbour.prefix + "/x", neighbour.token);

        // Assert: refused at once and never sent upstream; the neighbour is not affected
        assertThat(second.statusCode()).isEqualTo(429);
        assertThat(problemCode(second)).isEqualTo("CONCURRENCY_LIMITED");
        assertThat(audit(output, second).get("reason").asText()).isEqualTo("CONCURRENCY_LIMITED");
        assertThat(neighbours.statusCode()).isEqualTo(200);
        assertThat(first.get(5, java.util.concurrent.TimeUnit.SECONDS).statusCode()).isEqualTo(200);
        assertThat(slow.findAll(anyRequestedFor(urlPathMatching(".*/b")))).isEmpty();
        // ...and the slot is free again once the first response is done
        assertThat(get(f.prefix + "/c", f.token).statusCode()).isEqualTo(200);
    }

    // ---- RBAC on the data plane: scope = which service, role = which actions ---------------------

    @Test
    void testProxy_ReaderRole_ReadsButCannotWriteOrDelete(CapturedOutput output) throws Exception {
        // Arrange
        Fixture f = fixture(List.of("/"), 100);
        String rita = token(f.tenant, "rita", "orders-reader", List.of());

        // Act + Assert
        assertThat(get(f.prefix + "/orders/1", rita).statusCode()).isEqualTo(200);
        UPSTREAM.resetRequests();
        HttpResponse<String> delete = send(HttpRequest.newBuilder(gateway(f.prefix + "/orders/1"))
                .header("Authorization", "Bearer " + rita).DELETE());
        assertRejected(output, delete, 403, "PERMISSION_DENIED");
        assertThat(audit(output, delete).get("role").asText()).isEqualTo("orders-reader");
    }

    @Test
    void testProxy_RoleRevoked_NextRequestRejected403(CapturedOutput output) throws Exception {
        // Arrange: rita's token acts as orders-reader; then her assignment is removed
        Fixture f = fixture(List.of("/"), 100);
        String rita = token(f.tenant, "rita", "orders-reader", List.of());
        assertThat(get(f.prefix + "/orders/1", rita).statusCode()).isEqualTo(200);
        assertThat(admin("DELETE", "/admin/rbac/tenants/" + f.tenant + "/users/rita", null).statusCode()).isEqualTo(204);
        UPSTREAM.resetRequests();

        // Act + Assert: the token is still valid, but the decision is made now
        assertRejected(output, get(f.prefix + "/orders/1", rita), 403, "PERMISSION_DENIED");
    }

    @Test
    void testProxy_TenantDisabledAfterTokenIssued_Rejected403(CapturedOutput output) throws Exception {
        Fixture f = fixture(List.of("/"), 100);
        admin("PUT", "/admin/tenants/" + f.tenant, tenantBody(f.tenant, true, List.of("/"), 100));

        assertRejected(output, get(f.prefix + "/x", f.token), 403, "TENANT_DISABLED");
    }

    @Test
    void testProxy_QuotaExceeded_Rejected429WithRetryAfter(CapturedOutput output) throws Exception {
        Fixture f = fixture(List.of("/"), 3);
        for (int i = 0; i < 3; i++) {
            assertThat(get(f.prefix + "/x", f.token).statusCode()).isEqualTo(200);
        }

        HttpResponse<String> fourth = get(f.prefix + "/x", f.token);

        assertThat(fourth.statusCode()).isEqualTo(429);
        assertThat(problemCode(fourth)).isEqualTo("RATE_LIMITED");
        assertThat(fourth.headers().firstValue("Retry-After")).isPresent();
        UPSTREAM.verify(3, anyRequestedFor(anyUrl())); // the 4th never reached the upstream
    }

    @Test
    void testProxy_BodyOverLimit_Rejected413(CapturedOutput output) throws Exception {
        Fixture f = fixture(List.of("/"), 100);
        String twoKb = "x".repeat(2048); // test limit is 1KB

        // Known size: rejected up front, before quota is spent or anything is sent upstream.
        HttpResponse<String> declared = send(HttpRequest.newBuilder(gateway(f.prefix + "/upload"))
                .header("Authorization", "Bearer " + f.token)
                .POST(HttpRequest.BodyPublishers.ofString(twoKb)));
        assertRejected(output, declared, 413, "PAYLOAD_TOO_LARGE");

        // Chunked (size unknown): counted while streaming and aborted once over the limit.
        HttpResponse<String> chunked = send(HttpRequest.newBuilder(gateway(f.prefix + "/upload"))
                .header("Authorization", "Bearer " + f.token)
                .POST(HttpRequest.BodyPublishers.ofInputStream(() -> new java.io.ByteArrayInputStream(twoKb.getBytes()))));
        assertThat(chunked.statusCode()).isEqualTo(413);
        assertThat(problemCode(chunked)).isEqualTo("PAYLOAD_TOO_LARGE");
    }

    @Test
    void testProxy_BurstSpent_Rejected429BeforeMinuteQuota() throws Exception {
        // 6/min refills one token every 10s, so the real clock cannot refill during the test.
        Fixture f = fixture(List.of("/"), 6, 2);
        assertThat(get(f.prefix + "/x", f.token).statusCode()).isEqualTo(200);
        assertThat(get(f.prefix + "/x", f.token).statusCode()).isEqualTo(200);

        HttpResponse<String> third = get(f.prefix + "/x", f.token); // 3 of 6 per minute, but the burst is 2

        assertThat(third.statusCode()).isEqualTo(429);
        assertThat(problemCode(third)).isEqualTo("RATE_LIMITED");
        assertThat(third.headers().firstValue("Retry-After")).hasValueSatisfying(v -> assertThat(Integer.parseInt(v)).isPositive());
    }

    @Test
    void testAdmin_BurstLargerThanMinuteQuota_Rejected400() throws Exception {
        HttpResponse<String> response = admin("POST", "/admin/tenants",
                tenantBody("tenant-big-burst", false, List.of("/"), 10, 11));

        assertThat(response.statusCode()).isEqualTo(400);
        assertThat(problemCode(response)).isEqualTo("VALIDATION_ERROR");
    }

    @Test
    void testProxy_UnsafePathOrUnknownRoute_Rejected(CapturedOutput output) throws Exception {
        Fixture f = fixture(List.of("/"), 100);

        assertRejected(output, get(f.prefix + "/a/../../admin/tenants", f.token), 400, "BAD_PATH");
        assertRejected(output, get("/no-such-route-" + IDS.incrementAndGet(), f.token), 404, "ROUTE_NOT_FOUND");
    }

    // ---- dynamic routing ----------------------------------------------------------------------

    @Test
    void testRoutes_AddUpdateDelete_TakeEffectWithoutRestart(CapturedOutput output) throws Exception {
        WireMockServer second = new WireMockServer(options().dynamicPort());
        second.start();
        try {
            second.stubFor(any(anyUrl()).willReturn(aResponse().withStatus(200).withBody("second")));
            Fixture f = fixture(List.of("/"), 100);
            assertThat(get(f.prefix + "/x", f.token).body()).isEqualTo("ok");

            admin("PUT", "/admin/routes/" + f.route, routeBody(f.route, f.prefix, "http://localhost:" + second.port()));
            assertThat(get(f.prefix + "/x", f.token).body()).isEqualTo("second");

            assertThat(admin("DELETE", "/admin/routes/" + f.route, null).statusCode()).isEqualTo(204);
            UPSTREAM.resetRequests();
            assertRejected(output, get(f.prefix + "/x", f.token), 404, "ROUTE_NOT_FOUND");
            second.verify(1, anyRequestedFor(anyUrl())); // only the one call made before the delete
        } finally {
            second.stop();
        }
    }

    @Test
    void testRoutes_WeightedUpstreams_SplitTrafficAndAuditShowsVariant(CapturedOutput output) throws Exception {
        String base = upstreamUrl();
        Fixture f = fixture(List.of("/"), 100_000);
        admin("PUT", "/admin/routes/" + f.route,
                routeBody(f.route, f.prefix, Map.of(base + "/stable", 9, base + "/canary", 1)));

        List<HttpResponse<String>> responses = new java.util.ArrayList<>();
        for (int i = 0; i < 200; i++) {
            responses.add(get(f.prefix + "/x", f.token));
        }

        int canary = UPSTREAM.findAll(anyRequestedFor(urlPathMatching("/canary/.*"))).size();
        assertThat(canary).isBetween(5, 45); // expected ~20 of 200
        // The audit line records which variant served each request.
        assertThat(responses.stream().map(r -> audit(output, r).get("upstream").asText()).distinct())
                .containsExactlyInAnyOrder(base + "/stable", base + "/canary");
    }

    @Test
    void testRouteLimit_SharedByAllTenants_Rejected429_BadLimitRejected400(CapturedOutput output) throws Exception {
        // Arrange: a route that takes 2 requests at once (60/min), and two tenants with plenty of quota
        Fixture f = fixture(List.of("/"), 1000);
        Fixture other = fixture(List.of("/"), 1000);
        Map<String, Object> limited = new java.util.HashMap<>(routeBody(f.route, f.prefix, upstreamUrl()));
        limited.put("requestsPerMinute", 60);
        limited.put("burst", 2);
        assertThat(admin("PUT", "/admin/routes/" + f.route, limited).statusCode()).isEqualTo(200);
        assertThat(get(f.prefix + "/x", f.token).statusCode()).isEqualTo(200);
        assertThat(get(f.prefix + "/x", other.token).statusCode()).isEqualTo(200);
        UPSTREAM.resetRequests();

        // Act: the route's bucket is empty, whoever asks
        HttpResponse<String> response = get(f.prefix + "/x", f.token);
        limited.put("burst", 61);
        HttpResponse<String> badLimit = admin("PUT", "/admin/routes/" + f.route, limited);

        // Assert
        assertRejected(output, response, 429, "ROUTE_RATE_LIMITED");
        assertThat(response.headers().firstValue("Retry-After")).isPresent();
        assertThat(badLimit.statusCode()).isEqualTo(400);
        assertThat(problemCode(badLimit)).isEqualTo("VALIDATION_ERROR");
    }

    @Test
    void testRoutes_StickyCanary_EachTenantAlwaysOnItsOwnUpstream(CapturedOutput output) throws Exception {
        // Arrange: a 50/50 sticky route; two tenants
        String base = upstreamUrl();
        Fixture f = fixture(List.of("/"), 100_000);
        Fixture other = fixture(List.of("/"), 100_000);
        Map<String, Object> sticky = new java.util.HashMap<>(
                routeBody(f.route, f.prefix, Map.of(base + "/stable", 1, base + "/canary", 1)));
        sticky.put("sticky", true);
        assertThat(admin("PUT", "/admin/routes/" + f.route, sticky).statusCode()).isEqualTo(200);
        List<Route.Upstream> upstreams = List.of(new Route.Upstream(base + "/stable", 1), new Route.Upstream(base + "/canary", 1));

        // Act
        java.util.Set<String> servedF = new java.util.HashSet<>();
        java.util.Set<String> servedOther = new java.util.HashSet<>();
        for (int i = 0; i < 20; i++) {
            servedF.add(audit(output, get(f.prefix + "/x", f.token)).get("upstream").asText());
            servedOther.add(audit(output, get(f.prefix + "/x", other.token)).get("upstream").asText());
        }

        // Assert: one upstream per tenant (random per request: 2^-19 odds), the one the hash says
        assertThat(servedF).containsExactly(
                RendezvousHash.pick(f.tenant, upstreams, Route.Upstream::weight, Route.Upstream::url).url());
        assertThat(servedOther).containsExactly(
                RendezvousHash.pick(other.tenant, upstreams, Route.Upstream::weight, Route.Upstream::url).url());
        assertThat(json.readTree(admin("GET", "/admin/routes/" + f.route, null).body()).get("sticky").asBoolean()).isTrue();
    }

    @Test
    void testHedging_SlowFirstCall_HedgeAnswersFirstAndFirstCallIsCancelled(CapturedOutput output) throws Exception {
        // Arrange: the tenant's (sticky) upstream answers in 400ms, the other at once; hedge after 50ms
        Fixture f = fixture(List.of("/"), 100);
        WireMockServer[] up = hedgedRoute(f, 50);
        up[0].stubFor(any(anyUrl()).willReturn(aResponse().withStatus(200).withBody("slow").withFixedDelay(400)));
        up[1].stubFor(any(anyUrl()).willReturn(aResponse().withStatus(200).withBody("fast")));

        // Act
        HttpResponse<String> response = get(f.prefix + "/x", f.token);

        // Assert: served by the hedge, and the audit says so
        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.body()).isEqualTo("fast");
        JsonNode audit = audit(output, response);
        assertThat(audit.get("upstream").asText()).isEqualTo("http://localhost:" + up[1].port());
        assertThat(audit.get("detail").asText()).startsWith("hedged after 50ms, served by the hedge");
        assertThat(meters.get("gateway.upstream.hedges.won").tag("route", f.route).counter().count()).isEqualTo(1);
        up[1].verify(1, anyRequestedFor(anyUrl()));
        // Two calls, two child spans of the gateway's span, listed in the audit entry in the order sent
        String firstCall = up[0].getAllServeEvents().getFirst().getRequest().getHeader("traceparent");
        String hedgeCall = up[1].getAllServeEvents().getFirst().getRequest().getHeader("traceparent");
        String traceId = audit.get("traceId").asText();
        assertThat(firstCall).startsWith("00-" + traceId + "-");
        assertThat(hedgeCall).startsWith("00-" + traceId + "-");
        assertThat(json.convertValue(audit.get("upstreamSpans"), List.class))
                .containsExactly(firstCall.substring(36, 52), hedgeCall.substring(36, 52));
        // The slow call was cancelled: its pooled connection is back long before its answer would have come
        // (a leak here would also hang later tests on the 2-connection test pool).
        await().atMost(Duration.ofMillis(200)).until(() -> upstreamProxy.pool().getTotalStats().getLeased() == 0);
    }

    @Test
    void testTracing_ClientTraceContinued_OrNewTraceStarted(CapturedOutput output) throws Exception {
        // Arrange
        Fixture f = fixture(List.of("/"), 100);
        String clientTrace = "4bf92f3577b34da6a3ce929d0e0e4736";

        // Act: one request in the client's trace, one without
        HttpResponse<String> joined = send(HttpRequest.newBuilder(gateway(f.prefix + "/a"))
                .header("Authorization", "Bearer " + f.token)
                .header("traceparent", "00-" + clientTrace + "-00f067aa0ba902b7-01")
                .header("tracestate", "vendor=abc").GET());
        HttpResponse<String> fresh = get(f.prefix + "/b", f.token);

        // Assert: the upstream sees the client's trace, with the gateway's call as the parent span
        var joinedCall = UPSTREAM.findAll(anyRequestedFor(urlPathMatching(".*/a"))).getFirst();
        JsonNode joinedAudit = audit(output, joined);
        assertThat(joinedAudit.get("traceId").asText()).isEqualTo(clientTrace);
        String callSpan = joinedAudit.get("upstreamSpans").get(0).asText();
        assertThat(joinedCall.getHeader("traceparent")).isEqualTo("00-" + clientTrace + "-" + callSpan + "-01");
        assertThat(callSpan).isNotEqualTo(joinedAudit.get("spanId").asText()).isNotEqualTo("00f067aa0ba902b7");
        assertThat(joinedCall.getHeader("tracestate")).isEqualTo("vendor=abc");
        // ...and a request without one starts a trace at the gateway
        var freshCall = UPSTREAM.findAll(anyRequestedFor(urlPathMatching(".*/b"))).getFirst();
        JsonNode freshAudit = audit(output, fresh);
        assertThat(freshAudit.get("traceId").asText()).matches("[0-9a-f]{32}").isNotEqualTo(clientTrace);
        assertThat(freshCall.getHeader("traceparent"))
                .isEqualTo("00-" + freshAudit.get("traceId").asText() + "-" + freshAudit.get("upstreamSpans").get(0).asText() + "-01");
        assertThat(freshCall.containsHeader("tracestate")).isFalse();
    }

    @Test
    void testHedging_PostOrFastFirstCall_NoHedge(CapturedOutput output) throws Exception {
        // Arrange: as above
        Fixture f = fixture(List.of("/"), 100);
        WireMockServer[] up = hedgedRoute(f, 50);
        up[0].stubFor(any(urlPathMatching(".*/slow")).willReturn(aResponse().withStatus(200).withBody("slow").withFixedDelay(300)));
        up[0].stubFor(any(urlPathMatching(".*/fast")).willReturn(aResponse().withStatus(200).withBody("fast")));
        up[1].stubFor(any(anyUrl()).willReturn(aResponse().withStatus(200).withBody("other")));

        // Act
        HttpResponse<String> post = send(HttpRequest.newBuilder(gateway(f.prefix + "/slow"))
                .header("Authorization", "Bearer " + f.token).POST(HttpRequest.BodyPublishers.noBody()));
        HttpResponse<String> fast = get(f.prefix + "/fast", f.token);

        // Assert: a POST is never sent twice, however slow; a call answering in time is not hedged
        assertThat(post.body()).isEqualTo("slow");
        assertThat(fast.body()).isEqualTo("fast");
        assertThat(audit(output, post).get("detail").isNull()).isTrue();
        assertThat(audit(output, fast).get("detail").isNull()).isTrue();
        up[1].verify(0, anyRequestedFor(anyUrl()));
    }

    @Test
    void testRetry_GetAfterConnectionReset_RetriedAndServed_PostNotRetried(CapturedOutput output) throws Exception {
        // Arrange: an upstream whose first answer to each scenario is a reset connection, then 200
        WireMockServer flaky = newUpstream();
        for (String path : List.of("/get", "/post")) {
            String scenario = "flaky" + path;
            flaky.stubFor(any(urlPathMatching(".*" + path)).inScenario(scenario).whenScenarioStateIs(Scenario.STARTED)
                    .willReturn(aResponse().withFault(Fault.CONNECTION_RESET_BY_PEER)).willSetStateTo("recovered"));
            flaky.stubFor(any(urlPathMatching(".*" + path)).inScenario(scenario).whenScenarioStateIs("recovered")
                    .willReturn(aResponse().withStatus(200).withBody("ok")));
        }
        Fixture f = fixture(List.of("/"), 100);
        admin("PUT", "/admin/routes/" + f.route, routeBody(f.route, f.prefix, "http://localhost:" + flaky.port()));

        // Act
        HttpResponse<String> get = get(f.prefix + "/get", f.token);
        HttpResponse<String> post = send(HttpRequest.newBuilder(gateway(f.prefix + "/post"))
                .header("Authorization", "Bearer " + f.token).POST(HttpRequest.BodyPublishers.ofString("{}")));

        // Assert: the GET was retried once and served; the audit says why
        assertThat(get.statusCode()).isEqualTo(200);
        assertThat(flaky.findAll(anyRequestedFor(urlPathMatching(".*/get")))).hasSize(2);
        assertThat(audit(output, get).get("detail").asText()).startsWith("retried after UPSTREAM_ERROR");
        // ...the POST was not (it might have been processed): 502, and the upstream saw it once
        assertThat(post.statusCode()).isEqualTo(502);
        assertThat(flaky.findAll(anyRequestedFor(urlPathMatching(".*/post")))).hasSize(1);
    }

    @Test
    void testCircuitBreaker_CanaryBreakerOpen_TrafficFallsBackToStable(CapturedOutput output) throws Exception {
        // Arrange: stable and canary on different hosts (so different breakers); the canary always fails
        WireMockServer stable = newUpstream();
        WireMockServer canary = newUpstream();
        stable.stubFor(any(anyUrl()).willReturn(aResponse().withStatus(200).withBody("stable")));
        canary.stubFor(any(anyUrl()).willReturn(aResponse().withStatus(500)));
        Fixture f = fixture(List.of("/"), 100_000);
        admin("PUT", "/admin/routes/" + f.route, routeBody(f.route, f.prefix,
                Map.of("http://localhost:" + stable.port(), 1, "http://localhost:" + canary.port(), 9))); // canary-heavy: picked ~90%
        // Send until the canary has failed 4 times (test settings: 4 of 4 -> its breaker opens).
        for (int i = 0; i < 200 && canary.findAll(anyRequestedFor(anyUrl())).size() < 4; i++) {
            get(f.prefix + "/x", f.token);
        }
        assertThat(canary.findAll(anyRequestedFor(anyUrl()))).hasSize(4);
        canary.resetRequests();

        // Act: ~9 in 10 of these pick the canary by weight (10 requests, well inside the 1s open window)
        List<HttpResponse<String>> responses = new java.util.ArrayList<>();
        for (int i = 0; i < 10; i++) {
            responses.add(get(f.prefix + "/x", f.token));
        }

        // Assert: every request was served by stable; the canary was not called; the audit says why
        assertThat(responses).allSatisfy(r -> assertThat(r.body()).isEqualTo("stable"));
        assertThat(canary.findAll(anyRequestedFor(anyUrl()))).isEmpty();
        assertThat(responses.stream().map(r -> audit(output, r).path("detail").asText()))
                .contains("breaker-open:http://localhost:" + canary.port());
    }

    @Test
    void testAdmin_RouteToCloudMetadataAddress_Rejected400() throws Exception {
        HttpResponse<String> response = admin("POST", "/admin/routes",
                routeBody("r" + IDS.incrementAndGet(), "/meta" + IDS.incrementAndGet(), "http://169.254.169.254"));

        assertThat(response.statusCode()).isEqualTo(400);
        assertThat(response.body()).contains("link-local");
    }

    @Test
    void testAdmin_InvalidRoutes_Rejected() throws Exception {
        String upstream = upstreamUrl();
        Fixture f = fixture(List.of("/"), 100);

        assertThat(admin("POST", "/admin/routes", routeBody("r" + IDS.incrementAndGet(), "/admin/x", upstream)).statusCode())
                .isEqualTo(400); // shadows the control plane
        assertThat(admin("POST", "/admin/routes", routeBody("r" + IDS.incrementAndGet(), "/x/", upstream)).statusCode())
                .isEqualTo(400); // not canonical
        assertThat(admin("POST", "/admin/routes", routeBody("r" + IDS.incrementAndGet(), "/y", "ftp://host")).statusCode())
                .isEqualTo(400); // not http(s)
        assertThat(admin("POST", "/admin/routes", routeBody("r" + IDS.incrementAndGet(), f.prefix, upstream)).statusCode())
                .isEqualTo(409); // prefix already taken
    }

    // ---- upstream failures --------------------------------------------------------------------

    @Test
    void testProxy_UpstreamUnreachable_Returns502(CapturedOutput output) throws Exception {
        int closedPort;
        try (ServerSocket socket = new ServerSocket(0)) {
            closedPort = socket.getLocalPort();
        }
        Fixture f = fixture(List.of("/"), 100);
        admin("PUT", "/admin/routes/" + f.route, routeBody(f.route, f.prefix, "http://localhost:" + closedPort));

        HttpResponse<String> response = get(f.prefix + "/x", f.token);

        assertThat(response.statusCode()).isEqualTo(502);
        assertThat(response.body()).doesNotContain("localhost"); // no internal topology leaked to tenants
        assertThat(audit(output, response).get("decision").asText()).isEqualTo("ALLOW");
        assertThat(audit(output, response).get("reason").asText()).isEqualTo("UPSTREAM_ERROR");
    }

    @Test
    void testProxy_UpstreamTooSlow_Returns504() throws Exception {
        // Own upstream: failures must not count against the shared UPSTREAM's circuit breaker.
        WireMockServer slow = newUpstream();
        slow.stubFor(any(anyUrl()).willReturn(aResponse().withStatus(200).withFixedDelay(1500)));
        Fixture f = fixture(List.of("/"), 100);
        admin("PUT", "/admin/routes/" + f.route, routeBody(f.route, f.prefix, "http://localhost:" + slow.port()));

        assertThat(get(f.prefix + "/x", f.token).statusCode()).isEqualTo(504); // test timeout is 500ms
    }

    @Test
    void testProxy_ConnectionPoolFull_Returns503Saturated() throws Exception {
        WireMockServer slow = newUpstream();
        // 250ms: well inside the 500ms response timeout (no flaky 504 on a slow CI runner), well above
        // the 100ms pool lease timeout (so the third request must give up waiting).
        slow.stubFor(any(anyUrl()).willReturn(aResponse().withStatus(200).withFixedDelay(250)));
        Fixture f = fixture(List.of("/"), 100);
        admin("PUT", "/admin/routes/" + f.route, routeBody(f.route, f.prefix, "http://localhost:" + slow.port()));

        // 3 concurrent requests, 2 pooled connections per upstream (test settings): one has to wait,
        // gives up after the 100ms lease timeout and gets 503 instead of queueing forever.
        List<java.util.concurrent.CompletableFuture<HttpResponse<String>>> inFlight = new java.util.ArrayList<>();
        for (int i = 0; i < 3; i++) {
            inFlight.add(http.sendAsync(HttpRequest.newBuilder(gateway(f.prefix + "/x"))
                    .header("Authorization", "Bearer " + f.token).timeout(Duration.ofSeconds(5)).build(),
                    HttpResponse.BodyHandlers.ofString()));
        }
        List<Integer> statuses = inFlight.stream().map(java.util.concurrent.CompletableFuture::join)
                .map(HttpResponse::statusCode).sorted().toList();

        assertThat(statuses).containsExactly(200, 200, 503);
    }

    @Test
    void testCircuitBreaker_OpensOnFailures_FailsFast_RecoversAfterProbe(CapturedOutput output) throws Exception {
        WireMockServer flaky = newUpstream();
        flaky.stubFor(any(anyUrl()).willReturn(aResponse().withStatus(500)));
        Fixture f = fixture(List.of("/"), 100);
        admin("PUT", "/admin/routes/" + f.route, routeBody(f.route, f.prefix, "http://localhost:" + flaky.port()));

        // 4 failures in a window of 4 (test settings) -> the breaker opens.
        for (int i = 0; i < 4; i++) {
            assertThat(get(f.prefix + "/x", f.token).statusCode()).isEqualTo(500); // upstream 500 passed through
        }
        HttpResponse<String> open = get(f.prefix + "/x", f.token);

        assertThat(open.statusCode()).isEqualTo(503);
        assertThat(problemCode(open)).isEqualTo("CIRCUIT_OPEN");
        assertThat(open.headers().firstValue("Retry-After")).contains("1");
        flaky.verify(4, anyRequestedFor(anyUrl())); // the 5th request never reached the upstream
        assertThat(audit(output, open).get("decision").asText()).isEqualTo("DENY");

        // The upstream recovers; after the open duration one probe goes through and closes the breaker.
        flaky.resetAll();
        flaky.stubFor(any(anyUrl()).willReturn(aResponse().withStatus(200).withBody("back")));
        await().atMost(Duration.ofSeconds(3)).pollInterval(Duration.ofMillis(200))
                .until(() -> get(f.prefix + "/x", f.token).statusCode() == 200);
        assertThat(get(f.prefix + "/x", f.token).body()).isEqualTo("back");
    }

    // ---- observability ------------------------------------------------------------------------

    @Test
    void testMetrics_PrometheusEndpoint_ExposesRequestPoolBreakerAndAuditMetrics() throws Exception {
        Fixture f = fixture(List.of("/"), 100);
        get(f.prefix + "/x", f.token);

        String metrics = send(HttpRequest.newBuilder(URI.create("http://localhost:" + managementPort + "/actuator/prometheus")).GET())
                .body();

        assertThat(metrics).contains(
                "gateway_requests_seconds_count{decision=\"ALLOW\",reason=\"none\",route=\"" + f.route + "\",status=\"2xx\"}",
                "httpcomponents_httpclient_pool_total_connections",
                "gateway_circuit_state{name=\"rate-limiter\"}", // the breaker around the rate limiter
                "gateway_circuit_state{name=\"http://localhost:", // and the upstream's
                "gateway_audit_queue_size",
                "gateway_audit_dropped_total",
                "gateway_upstream_connections_opened_total",
                "gateway_circuit_state{name=\"config-db\"}");
        assertThat(metrics).doesNotContain(f.tenant); // no per-tenant labels (cardinality)
    }

    @Test
    void testUpstreamConnections_SequentialRequests_ReuseOneConnection() throws Exception {
        // Arrange: an upstream nobody has called yet
        WireMockServer fresh = newUpstream();
        fresh.stubFor(any(anyUrl()).willReturn(aResponse().withStatus(200)));
        Fixture f = fixture(List.of("/"), 100);
        admin("PUT", "/admin/routes/" + f.route, routeBody(f.route, f.prefix, "http://localhost:" + fresh.port()));
        double before = meters.get("gateway.upstream.connections.opened").functionCounter().count();

        // Act
        for (int i = 0; i < 5; i++) {
            assertThat(get(f.prefix + "/x", f.token).statusCode()).isEqualTo(200);
        }

        // Assert: one TCP connection for all five (kept alive and reused, no churn into TIME_WAIT)
        assertThat(meters.get("gateway.upstream.connections.opened").functionCounter().count() - before).isEqualTo(1);
    }

    @Test
    void testAlertRules_ReferOnlyToMetricsTheGatewayExports() throws Exception {
        // Arrange: traffic, so the request metrics exist; the rules file as shipped
        Fixture f = fixture(List.of("/"), 100);
        get(f.prefix + "/x", f.token);
        String metrics = send(HttpRequest.newBuilder(URI.create("http://localhost:" + managementPort + "/actuator/prometheus")).GET())
                .body();
        String rules = java.nio.file.Files.readString(java.nio.file.Path.of("ops/prometheus/gateway-rules.yml"));

        // Act: every metric name the rules use, without the histogram suffixes
        java.util.Set<String> used = new java.util.TreeSet<>();
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("\\b(?:gateway|httpcomponents)_[a-z_]+").matcher(rules);
        while (m.find()) {
            used.add(m.group().replaceAll("_(bucket|count|sum)$", ""));
        }

        // Assert: a renamed metric breaks this test rather than silently disabling an alert.
        // (retries_denied is registered on its first increment, so it is only checked by name.)
        assertThat(used).contains("gateway_requests_seconds", "gateway_upstream_connections_opened_total");
        used.remove("gateway_upstream_retries_denied_total");
        assertThat(used).allSatisfy(name -> assertThat(metrics).contains(name));
    }

    // ---- helpers ------------------------------------------------------------------------------

    private final List<WireMockServer> extraUpstreams = new java.util.ArrayList<>();

    @org.junit.jupiter.api.AfterEach
    void stopExtraUpstreams() {
        extraUpstreams.forEach(WireMockServer::stop);
        extraUpstreams.clear();
    }

    private WireMockServer newUpstream() {
        WireMockServer server = new WireMockServer(options().dynamicPort());
        server.start();
        extraUpstreams.add(server);
        return server;
    }

    /** Every fixture tenant may reach these services; its routes are the "orders" service. */
    static final List<String> SERVICES = List.of("orders", "billing");
    /** Every fixture tenant gets these users (through the RBAC API): usera may read/write/delete orders, rita may only read. */
    static final Map<String, List<String>> USERS = Map.of("usera", List.of("orders-writer"), "rita", List.of("orders-reader"));

    /** {@code token} is usera's, acting as orders-writer, with every service of the tenant. */
    record Fixture(String tenant, String route, String prefix, String token) {}

    /** A fresh tenant + route + token per test, so tests never share quota or routes. */
    private Fixture fixture(List<String> allowedPaths, int perMinute) throws Exception {
        return fixture(allowedPaths, perMinute, perMinute); // whole quota at once
    }

    private Fixture fixture(List<String> allowedPaths, int perMinute, int burst) throws Exception {
        int n = IDS.incrementAndGet();
        String tenant = "tenant-" + n;
        String route = "route-" + n;
        String prefix = "/svc" + n;
        assertThat(admin("POST", "/admin/tenants", tenantBody(tenant, false, allowedPaths, perMinute, burst)).statusCode())
                .isEqualTo(201);
        for (Map.Entry<String, List<String>> user : USERS.entrySet()) {
            assertThat(admin("PUT", "/admin/rbac/tenants/" + tenant + "/users/" + user.getKey(),
                    Map.of("roles", user.getValue())).statusCode()).isEqualTo(200);
        }
        assertThat(admin("POST", "/admin/routes", routeBody(route, prefix, upstreamUrl())).statusCode())
                .isEqualTo(201);
        return new Fixture(tenant, route, prefix, token(tenant, "usera", "orders-writer", List.of()));
    }

    /**
     * Points the fixture's route at two fresh upstreams, sticky (so the first call's upstream is known)
     * and hedged. @return {the tenant's upstream, the other one}
     */
    private WireMockServer[] hedgedRoute(Fixture f, int hedgeAfterMs) throws Exception {
        WireMockServer a = newUpstream();
        WireMockServer b = newUpstream();
        String urlA = "http://localhost:" + a.port();
        String urlB = "http://localhost:" + b.port();
        Map<String, Object> body = new java.util.HashMap<>(routeBody(f.route, f.prefix, Map.of(urlA, 1, urlB, 1)));
        body.put("sticky", true);
        body.put("hedgeAfterMs", hedgeAfterMs);
        assertThat(admin("PUT", "/admin/routes/" + f.route, body).statusCode()).isEqualTo(200);
        String first = RendezvousHash.pick(f.tenant, List.of(new Route.Upstream(urlA, 1), new Route.Upstream(urlB, 1)),
                Route.Upstream::weight, Route.Upstream::url).url();
        return first.equals(urlA) ? new WireMockServer[] {a, b} : new WireMockServer[] {b, a};
    }

    /** A token for one of the tenant's users, acting as one of their roles. Empty scopes = all services. */
    private String token(String tenant, String user, String role, List<String> scopes) throws Exception {
        HttpResponse<String> issued = admin("POST", "/admin/tenants/" + tenant + "/tokens",
                Map.of("user", user, "role", role, "scopes", scopes));
        assertThat(issued.statusCode()).as(issued.body()).isEqualTo(201);
        return json.readTree(issued.body()).get("token").asText();
    }

    private static Map<String, Object> tenantBody(String id, boolean disabled, List<String> paths, int perMinute) {
        return tenantBody(id, disabled, paths, perMinute, perMinute);
    }

    private static Map<String, Object> tenantBody(String id, boolean disabled, List<String> paths, int perMinute, int burst) {
        return Map.of("id", id, "name", id, "disabled", disabled, "requestsPerMinute", perMinute,
                "burst", burst, "allowedPathPrefixes", paths, "grantableScopes", SERVICES);
    }

    private static Map<String, Object> routeBody(String id, String prefix, String upstream) {
        return routeBody(id, prefix, Map.of(upstream, 1));
    }

    private static Map<String, Object> routeBody(String id, String prefix, Map<String, Integer> weightedUpstreams) {
        List<Map<String, Object>> upstreams = weightedUpstreams.entrySet().stream()
                .map(e -> Map.<String, Object>of("url", e.getKey(), "weight", e.getValue()))
                .toList();
        return Map.of("id", id, "pathPrefix", prefix, "upstreams", upstreams, "service", "orders");
    }

    private static String upstreamUrl() {
        return "http://localhost:" + UPSTREAM.port();
    }

    private URI gateway(String path) {
        return URI.create("http://localhost:" + port + path);
    }

    private HttpResponse<String> get(String path, String token) throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder(gateway(path)).GET();
        if (token != null) {
            request.header("Authorization", "Bearer " + token);
        }
        return send(request);
    }

    private static volatile String adminSession;

    /** Logs in once as test user "ops" with its "admin" role, and reuses the session. */
    private String adminSession() throws Exception {
        if (adminSession == null) {
            adminSession = sessionToken(login("ops", "test-ops-key", "admin"));
        }
        return adminSession;
    }

    private HttpResponse<String> login(String user, String key, String role) throws Exception {
        return send(HttpRequest.newBuilder(gateway("/admin/login"))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(
                        json.writeValueAsString(Map.of("user", user, "key", key, "role", role)))));
    }

    private String sessionToken(HttpResponse<String> login) throws Exception {
        assertThat(login.statusCode()).isEqualTo(200);
        return json.readTree(login.body()).get("token").asText();
    }

    private HttpResponse<String> admin(String method, String path, Object body) throws Exception {
        return adminAs(adminSession(), method, path, body);
    }

    private HttpResponse<String> adminAs(String session, String method, String path, Object body) throws Exception {
        return send(HttpRequest.newBuilder(gateway(path))
                .header("Authorization", "Bearer " + session)
                .header("Content-Type", "application/json")
                .method(method, body == null
                        ? HttpRequest.BodyPublishers.noBody()
                        : HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body))));
    }

    private HttpResponse<String> send(HttpRequest.Builder request) throws Exception {
        return http.send(request.timeout(Duration.ofSeconds(10)).build(), HttpResponse.BodyHandlers.ofString());
    }

    private static String requestId(HttpResponse<?> response) {
        return response.headers().firstValue("X-Request-Id").orElseThrow();
    }

    private String problemCode(HttpResponse<String> response) throws Exception {
        return json.readTree(response.body()).get("code").asText();
    }

    /** Finds this request's JSON audit line in the captured log output. */
    private JsonNode audit(CapturedOutput output, HttpResponse<?> response) {
        String marker = "\"requestId\":\"" + requestId(response) + "\"";
        String line = await().atMost(Duration.ofSeconds(2)).until(
                () -> output.getOut().lines().filter(l -> l.startsWith("{") && l.contains(marker)).findFirst().orElse(null),
                l -> l != null);
        try {
            return json.readTree(line);
        } catch (Exception e) {
            throw new AssertionError(e);
        }
    }

    /** Status + problem code + never reached the upstream + audited as DENY with that reason. */
    private void assertRejected(CapturedOutput output, HttpResponse<String> response, int status, String code) throws Exception {
        assertThat(response.statusCode()).isEqualTo(status);
        assertThat(response.headers().firstValue("Content-Type")).contains("application/problem+json");
        assertThat(problemCode(response)).isEqualTo(code);
        UPSTREAM.verify(0, anyRequestedFor(anyUrl()));
        JsonNode audit = audit(output, response);
        assertThat(audit.get("decision").asText()).isEqualTo("DENY");
        assertThat(audit.get("reason").asText()).isEqualTo(code);
        assertThat(audit.get("upstreamStatus").isNull()).isTrue();
    }
}
