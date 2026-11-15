package com.example.gateway.auth;

import com.example.gateway.error.ErrorCode;
import com.example.gateway.error.GatewayException;
import com.example.gateway.store.Tenant;
import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JOSEObjectType;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import com.nimbusds.jose.jwk.source.ImmutableJWKSet;
import com.nimbusds.jose.proc.BadJOSEException;
import com.nimbusds.jose.proc.JWSVerificationKeySelector;
import com.nimbusds.jose.proc.SecurityContext;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import com.nimbusds.jwt.proc.BadJWTException;
import com.nimbusds.jwt.proc.DefaultJWTProcessor;
import java.security.KeyFactory;
import java.security.interfaces.RSAPrivateCrtKey;
import java.security.interfaces.RSAPublicKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.RSAPublicKeySpec;
import java.text.ParseException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.Base64;
import java.util.Date;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Issues and verifies tenant tokens: RS256 JWTs carrying {@code tid} (tenant), {@code sub} (the
 * tenant's user), {@code role} (the one role the token acts as) and {@code scope} (the services it
 * may reach). Scopes answer "which services"; the role's permissions answer "which actions".
 *
 * <ul>
 *   <li>Local verification, no network call per request.
 *   <li>RS256 (asymmetric): a verifier only needs the public key. With HS256 anything that can
 *       verify a token can also mint one.
 *   <li>The key selector accepts RS256 only. That closes the two classic JWT attacks: {@code alg:none}
 *       and algorithm confusion (an HS256 token "signed" with our public key as the secret).
 *   <li>Least privilege at issuance: scopes must be a subset of what the tenant may grant; TTL is capped.
 * </ul>
 */
public class TokenService {

    /** The verified caller. Comes only from a signature-checked token, never from request headers. */
    public record Principal(String tenantId, String user, String role, Set<String> scopes, String tokenId) {
        public static final String ATTRIBUTE = Principal.class.getName();
    }

    public record IssuedToken(String token, String tokenId, String user, String role, Instant expiresAt, Set<String> scopes) {}

    /** Why a token was rejected. Clients only see the coarse message; the audit log gets the reason. */
    public enum Failure {
        MISSING_TOKEN("Missing bearer token"),
        MALFORMED("Invalid token"),
        INVALID_SIGNATURE("Invalid token"),
        INVALID_CLAIMS("Invalid token"),
        EXPIRED("Token expired"),
        REVOKED("Token revoked");

        public final String publicMessage;

        Failure(String publicMessage) {
            this.publicMessage = publicMessage;
        }
    }

    public static class InvalidToken extends Exception {
        public final Failure failure;

        public InvalidToken(Failure failure) {
            super(failure.name(), null, false, false);
            this.failure = failure;
        }
    }

    private static final String TENANT = "tid";
    private static final String SCOPE = "scope";
    private static final String ROLE = "role";
    private static final Duration CLOCK_SKEW = Duration.ofSeconds(30);

    private final RSAKey key;
    private final String issuer;
    private final String audience;
    private final Duration defaultTtl;
    private final Duration maxTtl;
    private final Clock clock;
    private final DefaultJWTProcessor<SecurityContext> processor = new DefaultJWTProcessor<>();

    public TokenService(RSAKey key, String issuer, String audience, Duration defaultTtl, Duration maxTtl, Clock clock) {
        this.key = key;
        this.issuer = issuer;
        this.audience = audience;
        this.defaultTtl = defaultTtl;
        this.maxTtl = maxTtl;
        this.clock = clock;
        processor.setJWSKeySelector(new JWSVerificationKeySelector<>(
                JWSAlgorithm.RS256, new ImmutableJWKSet<>(new JWKSet(key.toPublicJWK()))));
        processor.setJWTClaimsSetVerifier((claims, context) -> checkClaims(claims));
    }

    // ---- issue --------------------------------------------------------------------------------

    /** The caller checks that {@code user} holds {@code role} (that is the RBAC module's decision). */
    public IssuedToken issue(Tenant tenant, String user, String role, Set<String> requestedScopes, Duration requestedTtl) {
        if (tenant.disabled()) {
            throw new GatewayException(ErrorCode.CONFLICT, "tenant '" + tenant.id() + "' is disabled");
        }
        Duration ttl = requestedTtl == null ? defaultTtl : requestedTtl;
        if (ttl.isNegative() || ttl.isZero() || ttl.compareTo(maxTtl) > 0) {
            throw new GatewayException(ErrorCode.VALIDATION_ERROR, "ttl must be between 1s and " + maxTtl.toSeconds() + "s");
        }
        Set<String> scopes = new TreeSet<>(requestedScopes == null || requestedScopes.isEmpty()
                ? tenant.grantableScopes() : requestedScopes);
        if (!tenant.grantableScopes().containsAll(scopes)) {
            throw new GatewayException(ErrorCode.VALIDATION_ERROR, "scopes not grantable for this tenant: " + scopes);
        }
        Instant now = clock.instant();
        String tokenId = UUID.randomUUID().toString();
        JWTClaimsSet claims = new JWTClaimsSet.Builder()
                .issuer(issuer)
                .audience(audience)
                .subject(user)
                .claim(TENANT, tenant.id())
                .claim(ROLE, role)
                .claim(SCOPE, String.join(" ", scopes))
                .issueTime(Date.from(now))
                .expirationTime(Date.from(now.plus(ttl)))
                .jwtID(tokenId)
                .build();
        SignedJWT jwt = new SignedJWT(
                new JWSHeader.Builder(JWSAlgorithm.RS256).keyID(key.getKeyID()).type(JOSEObjectType.JWT).build(), claims);
        try {
            jwt.sign(new RSASSASigner(key));
        } catch (JOSEException e) {
            throw new IllegalStateException("token signing failed", e);
        }
        return new IssuedToken(jwt.serialize(), tokenId, user, role, now.plus(ttl), Set.copyOf(scopes));
    }

    /**
     * The latest moment any token issued until now can still be accepted: a revocation must be kept
     * until then, and can be forgotten after.
     */
    public Instant latestExpiry() {
        return clock.instant().plus(maxTtl).plus(CLOCK_SKEW);
    }

    // ---- verify -------------------------------------------------------------------------------

    public Principal verify(String token) throws InvalidToken {
        try {
            JWTClaimsSet claims = processor.process(token, null); // signature first, then checkClaims
            String scope = claims.getStringClaim(SCOPE);
            Set<String> scopes = scope == null || scope.isBlank()
                    ? Set.of() : Arrays.stream(scope.trim().split("\\s+")).collect(Collectors.toSet());
            return new Principal(claims.getStringClaim(TENANT), claims.getSubject(), claims.getStringClaim(ROLE),
                    Set.copyOf(scopes), claims.getJWTID());
        } catch (ClaimRejected e) {
            throw new InvalidToken(e.failure);
        } catch (ParseException e) {
            throw new InvalidToken(Failure.MALFORMED);
        } catch (BadJOSEException | JOSEException e) {
            throw new InvalidToken(Failure.INVALID_SIGNATURE); // alg:none, wrong algorithm, wrong key
        }
    }

    private void checkClaims(JWTClaimsSet claims) throws BadJWTException {
        boolean validTenant = claims.getClaim(TENANT) instanceof String tid && tid.matches(Tenant.ID);
        if (!issuer.equals(claims.getIssuer())
                || claims.getAudience() == null || !claims.getAudience().contains(audience)
                || claims.getExpirationTime() == null || !validTenant
                || claims.getSubject() == null || !(claims.getClaim(ROLE) instanceof String)
                || claims.getJWTID() == null || claims.getJWTID().length() > 36 // the id revocation works by
                || (claims.getClaim(SCOPE) != null && !(claims.getClaim(SCOPE) instanceof String))) {
            throw new ClaimRejected(Failure.INVALID_CLAIMS);
        }
        if (clock.instant().isAfter(claims.getExpirationTime().toInstant().plus(CLOCK_SKEW))) {
            throw new ClaimRejected(Failure.EXPIRED);
        }
    }

    /** Carries the precise failure out of Nimbus' claims-verifier callback. */
    private static final class ClaimRejected extends BadJWTException {
        private final transient Failure failure;

        ClaimRejected(Failure failure) {
            super(failure.name());
            this.failure = failure;
        }
    }

    // ---- keys ---------------------------------------------------------------------------------

    /** Loads a PKCS#8 PEM ({@code -----BEGIN PRIVATE KEY-----}); the public key is derived from it. */
    public static RSAKey loadKey(String pem, String keyId) {
        try {
            String base64 = pem.replaceAll("-----(BEGIN|END) PRIVATE KEY-----", "").replaceAll("\\s", "");
            KeyFactory rsa = KeyFactory.getInstance("RSA");
            RSAPrivateCrtKey priv = (RSAPrivateCrtKey) rsa.generatePrivate(new PKCS8EncodedKeySpec(Base64.getDecoder().decode(base64)));
            RSAPublicKey pub = (RSAPublicKey) rsa.generatePublic(new RSAPublicKeySpec(priv.getModulus(), priv.getPublicExponent()));
            return new RSAKey.Builder(pub).privateKey(priv).keyID(keyId).build();
        } catch (Exception e) {
            throw new IllegalStateException("not a valid PKCS#8 RSA private key", e);
        }
    }

    /** Ephemeral key for local runs and tests. */
    public static RSAKey generateKey(String keyId) {
        try {
            return new RSAKeyGenerator(2048).keyID(keyId).generate();
        } catch (JOSEException e) {
            throw new IllegalStateException(e);
        }
    }
}
