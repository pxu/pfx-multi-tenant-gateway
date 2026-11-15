package com.example.gateway.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.example.gateway.auth.TokenService.Failure;
import com.example.gateway.auth.TokenService.InvalidToken;
import com.example.gateway.store.Tenant;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.PlainHeader;
import com.nimbusds.jose.crypto.MACSigner;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.PlainJWT;
import com.nimbusds.jwt.SignedJWT;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

class TokenServiceTest {

    private static final Instant NOW = Instant.parse("2026-01-01T00:00:00Z");
    private static final RSAKey KEY = TokenService.generateKey("k1");

    private final TokenService tokens = new TokenService(KEY, "iss", "aud", Duration.ofMinutes(15), Duration.ofHours(1),
            Clock.fixed(NOW, ZoneOffset.UTC));
    private final Tenant acme = new Tenant("acme", "Acme", false, 100, 0, List.of("/"), Set.of("orders", "billing"));

    private static JWTClaimsSet.Builder validClaims() {
        return new JWTClaimsSet.Builder().issuer("iss").audience("aud").subject("usera")
                .claim("tid", "acme").claim("role", "orders-reader").claim("scope", "orders")
                .expirationTime(Date.from(NOW.plusSeconds(600))).jwtID("t-1");
    }

    private static String sign(JWTClaimsSet claims, RSAKey key) throws Exception {
        SignedJWT jwt = new SignedJWT(new JWSHeader(JWSAlgorithm.RS256), claims);
        jwt.sign(new RSASSASigner(key));
        return jwt.serialize();
    }

    private Failure failureOf(String token) {
        try {
            tokens.verify(token);
            return null;
        } catch (InvalidToken e) {
            return e.failure;
        }
    }

    @Test
    void testVerify_IssuedToken_ReturnsTenantUserRoleAndScopes() throws Exception {
        // Arrange
        TokenService.IssuedToken issued = tokens.issue(acme, "usera", "orders-reader", Set.of("orders"), null);
        String token = issued.token();

        // Act
        TokenService.Principal principal = tokens.verify(token);

        // Assert
        assertThat(principal.tenantId()).isEqualTo("acme");
        assertThat(principal.user()).isEqualTo("usera");
        assertThat(principal.role()).isEqualTo("orders-reader");
        assertThat(principal.scopes()).containsExactly("orders");
        assertThat(principal.tokenId()).isEqualTo(issued.tokenId());
    }

    @Test
    void testVerify_Expired_Rejected() throws Exception {
        String token = sign(validClaims().expirationTime(Date.from(NOW.minusSeconds(31))).build(), KEY);

        assertThat(failureOf(token)).isEqualTo(Failure.EXPIRED);
    }

    @Test
    void testVerify_ExpiredWithinClockSkew_Accepted() throws Exception {
        String token = sign(validClaims().expirationTime(Date.from(NOW.minusSeconds(10))).build(), KEY);

        assertThat(tokens.verify(token).tenantId()).isEqualTo("acme");
    }

    @Test
    void testVerify_SignedByAnotherKey_Rejected() throws Exception {
        String token = sign(validClaims().build(), TokenService.generateKey("k1"));

        assertThat(failureOf(token)).isEqualTo(Failure.INVALID_SIGNATURE);
    }

    @Test
    void testVerify_AlgNone_Rejected() {
        String token = new PlainJWT(new PlainHeader(), validClaims().build()).serialize();

        assertThat(failureOf(token)).isEqualTo(Failure.INVALID_SIGNATURE);
    }

    @Test
    void testVerify_Hs256SignedWithPublicKey_Rejected() throws Exception {
        // Algorithm confusion: attacker uses our (public) RSA modulus as an HMAC secret.
        SignedJWT jwt = new SignedJWT(new JWSHeader(JWSAlgorithm.HS256), validClaims().build());
        jwt.sign(new MACSigner(KEY.getModulus().decode()));

        assertThat(failureOf(jwt.serialize())).isEqualTo(Failure.INVALID_SIGNATURE);
    }

    @Test
    void testVerify_WrongIssuerAudienceTenantOrMissingUserRole_Rejected() throws Exception {
        assertThat(failureOf(sign(validClaims().subject(null).build(), KEY))).isEqualTo(Failure.INVALID_CLAIMS);
        assertThat(failureOf(sign(validClaims().claim("role", null).build(), KEY))).isEqualTo(Failure.INVALID_CLAIMS);
        assertThat(failureOf(sign(validClaims().issuer("evil").build(), KEY))).isEqualTo(Failure.INVALID_CLAIMS);
        assertThat(failureOf(sign(validClaims().audience("other").build(), KEY))).isEqualTo(Failure.INVALID_CLAIMS);
        assertThat(failureOf(sign(validClaims().claim("tid", null).build(), KEY))).isEqualTo(Failure.INVALID_CLAIMS);
        assertThat(failureOf(sign(validClaims().claim("tid", "../admin").build(), KEY))).isEqualTo(Failure.INVALID_CLAIMS);
    }

    @Test
    void testVerify_NoTokenId_Rejected() throws Exception {
        // A token without a jti could never be revoked
        assertThat(failureOf(sign(validClaims().jwtID(null).build(), KEY))).isEqualTo(Failure.INVALID_CLAIMS);
        assertThat(failureOf(sign(validClaims().jwtID("x".repeat(37)).build(), KEY))).isEqualTo(Failure.INVALID_CLAIMS);
    }

    @Test
    void testLatestExpiry_IsMaxTtlPlusSkewFromNow() {
        assertThat(tokens.latestExpiry()).isEqualTo(NOW.plus(Duration.ofHours(1)).plusSeconds(30));
    }

    @Test
    void testVerify_Garbage_Rejected() {
        assertThat(failureOf("not-a-jwt")).isEqualTo(Failure.MALFORMED);
    }

    @Test
    void testIssue_NoScopesRequested_GrantsAllGrantable() throws Exception {
        TokenService.IssuedToken issued = tokens.issue(acme, "usera", "orders-reader", Set.of(), null);

        assertThat(tokens.verify(issued.token()).scopes()).containsExactlyInAnyOrder("orders", "billing");
        assertThat(issued.expiresAt()).isEqualTo(NOW.plus(Duration.ofMinutes(15)));
    }

    @Test
    void testIssue_ScopeNotGrantable_Rejected() {
        assertThatThrownBy(() -> tokens.issue(acme, "usera", "orders-reader", Set.of("payroll"), null))
                .hasMessageContaining("not grantable");
    }

    @Test
    void testIssue_TtlAboveMaximum_Rejected() {
        assertThatThrownBy(() -> tokens.issue(acme, "usera", "orders-reader", null, Duration.ofHours(2)))
                .hasMessageContaining("ttl");
    }

    @Test
    void testIssue_DisabledTenant_Rejected() {
        Tenant disabled = new Tenant("acme", "Acme", true, 100, 0, List.of("/"), Set.of("orders"));

        assertThatThrownBy(() -> tokens.issue(disabled, "usera", "orders-reader", null, null)).hasMessageContaining("disabled");
    }
}
