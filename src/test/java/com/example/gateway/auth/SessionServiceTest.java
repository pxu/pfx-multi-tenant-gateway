package com.example.gateway.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.example.gateway.auth.TokenService.Failure;
import com.example.gateway.auth.TokenService.InvalidToken;
import com.example.gateway.error.ErrorCode;
import com.example.gateway.error.GatewayException;
import com.example.gateway.rbac.Authorizer;
import com.example.gateway.rbac.RbacAuthorizer;
import com.example.gateway.rbac.Subject;
import com.example.gateway.store.Tenant;
import com.nimbusds.jose.jwk.RSAKey;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

class SessionServiceTest {

    private static final Instant NOW = Instant.parse("2026-01-01T00:00:00Z");
    private static final RSAKey KEY = TokenService.generateKey("k1");
    private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);
    static final String ALICE_KEY_SHA256 = "72ee9d4355ccb9d3a4c9dbf37382e38e75c1b1a225b5bd1f729ee91bbda30c20"; // "alice-key"

    private static final Authorizer AUTHORIZER = new RbacAuthorizer(
            Map.of("viewer", List.of("routes:read"), "operator", List.of("routes:read", "routes:write"), "admin", List.of()),
            Map::of, (tenant, user) -> tenant == null && user.equals("alice") ? List.of("viewer", "operator") : List.of());

    private static SessionService sessions(Clock clock) {
        return new SessionService(KEY, "iss", Map.of("alice", ALICE_KEY_SHA256, "locked", ""), AUTHORIZER,
                Duration.ofHours(1), clock);
    }

    private final SessionService sessions = sessions(CLOCK);

    private ErrorCode loginError(String user, String key, String role) {
        try {
            sessions.login(user, key, role);
            return null;
        } catch (GatewayException e) {
            return e.code();
        }
    }

    private static Failure verifyFailure(SessionService s, String token) {
        try {
            s.verify(token);
            return null;
        } catch (InvalidToken e) {
            return e.failure;
        }
    }

    @Test
    void testLogin_ChosenRole_SessionCarriesUserAndThatRole() throws Exception {
        // Act
        String token = sessions.login("alice", "alice-key", "viewer").token();
        Subject session = sessions.verify(token);

        // Assert: alice also holds operator, but this session acts as viewer
        assertThat(session).isEqualTo(Subject.operator("alice", "viewer"));
    }

    @Test
    void testLogin_WrongKeyUnknownUserOrNoKey_Rejected401() {
        assertThat(loginError("alice", "guess", "viewer")).isEqualTo(ErrorCode.ADMIN_UNAUTHORIZED);
        assertThat(loginError("mallory", "alice-key", "viewer")).isEqualTo(ErrorCode.ADMIN_UNAUTHORIZED);
        assertThat(loginError("locked", "", "viewer")).isEqualTo(ErrorCode.ADMIN_UNAUTHORIZED);
    }

    @Test
    void testLogin_RoleNotAssigned_Rejected403() {
        assertThat(loginError("alice", "alice-key", "admin")).isEqualTo(ErrorCode.PERMISSION_DENIED);
    }

    @Test
    void testConstructor_MalformedKeyHash_FailsStartup() {
        assertThatThrownBy(() -> new SessionService(KEY, "iss", Map.of("bob", "not-hex"), AUTHORIZER, Duration.ofHours(1), CLOCK))
                .hasMessageContaining("key-sha256");
    }

    @Test
    void testVerify_Expired_Rejected() {
        String token = sessions.login("alice", "alice-key", "viewer").token();

        assertThat(verifyFailure(sessions(Clock.offset(CLOCK, Duration.ofHours(2))), token)).isEqualTo(Failure.EXPIRED);
    }

    @Test
    void testVerify_TenantTokenSameKey_RejectedBothWays() {
        // Arrange: tenant tokens are signed with the same key, only the audience tells them apart
        TokenService tenantTokens = new TokenService(KEY, "iss", "gateway", Duration.ofMinutes(15), Duration.ofHours(1), CLOCK);
        Tenant acme = new Tenant("acme", "Acme", false, 100, 0, List.of("/"), Set.of());
        String tenantToken = tenantTokens.issue(acme, "usera", "viewer", Set.of(), null).token();
        String sessionToken = sessions.login("alice", "alice-key", "operator").token();

        // Act + Assert: a tenant token is not a session, and a session is not a tenant token
        assertThat(verifyFailure(sessions, tenantToken)).isEqualTo(Failure.INVALID_CLAIMS);
        assertThatThrownBy(() -> tenantTokens.verify(sessionToken)).isInstanceOf(InvalidToken.class);
    }

    @Test
    void testVerify_OtherKeyOrGarbage_Rejected() {
        SessionService otherKey = new SessionService(TokenService.generateKey("k1"), "iss", Map.of(), AUTHORIZER,
                Duration.ofHours(1), CLOCK);

        assertThat(verifyFailure(otherKey, sessions.login("alice", "alice-key", "viewer").token()))
                .isEqualTo(Failure.INVALID_SIGNATURE);
        assertThat(verifyFailure(sessions, "not.a.jwt")).isEqualTo(Failure.MALFORMED);
    }
}
