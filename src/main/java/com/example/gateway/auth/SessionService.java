package com.example.gateway.auth;

import com.example.gateway.auth.TokenService.Failure;
import com.example.gateway.auth.TokenService.InvalidToken;
import com.example.gateway.error.ErrorCode;
import com.example.gateway.error.GatewayException;
import com.example.gateway.rbac.Authorizer;
import com.example.gateway.rbac.Subject;
import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JOSEObjectType;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.source.ImmutableJWKSet;
import com.nimbusds.jose.proc.BadJOSEException;
import com.nimbusds.jose.proc.JWSVerificationKeySelector;
import com.nimbusds.jose.proc.SecurityContext;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import com.nimbusds.jwt.proc.BadJWTException;
import com.nimbusds.jwt.proc.DefaultJWTProcessor;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.text.ParseException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.Map;
import java.util.UUID;

/**
 * Operator login and sessions (authentication only; what a session may do is the {@link Authorizer}'s
 * call). An operator logs in with their key and picks <b>one</b> of their roles; the session token
 * (an RS256 JWT) carries the user and that role, never permissions.
 *
 * <p>Same signing key as tenant tokens, but a different audience ({@value #AUDIENCE}), so neither kind
 * of token is accepted where the other is expected. Keys are stored as SHA-256: they are random,
 * high-entropy API keys, not human passwords (those would need a slow hash like bcrypt).
 */
public class SessionService {

    public static final String AUDIENCE = "gateway-session";
    private static final String ROLE = "role";
    private static final Duration CLOCK_SKEW = Duration.ofSeconds(30);
    private static final byte[] NO_MATCH = new byte[32];

    public record Issued(String token, String user, String role, Instant expiresAt) {}

    private final RSAKey key;
    private final String issuer;
    private final Map<String, byte[]> keyHashes;
    private final Authorizer authorizer;
    private final Duration ttl;
    private final Clock clock;
    private final DefaultJWTProcessor<SecurityContext> processor = new DefaultJWTProcessor<>();

    /** @param keySha256 user → hex SHA-256 of their key; blank = that user cannot log in */
    public SessionService(RSAKey key, String issuer, Map<String, String> keySha256, Authorizer authorizer, Duration ttl,
            Clock clock) {
        this.key = key;
        this.issuer = issuer;
        this.authorizer = authorizer;
        this.ttl = ttl;
        this.clock = clock;
        this.keyHashes = new HashMap<>();
        keySha256.forEach((user, hex) -> {
            if (hex == null || hex.isBlank()) {
                return;
            }
            if (!hex.matches("^[0-9a-f]{64}$")) {
                throw new IllegalArgumentException("user '" + user + "': key-sha256 must be 64 lowercase hex characters");
            }
            keyHashes.put(user, HexFormat.of().parseHex(hex));
        });
        processor.setJWSKeySelector(new JWSVerificationKeySelector<>(
                JWSAlgorithm.RS256, new ImmutableJWKSet<>(new JWKSet(key.toPublicJWK()))));
        processor.setJWTClaimsSetVerifier((claims, context) -> checkClaims(claims));
    }

    /** 401 for a wrong user or key (same message for both), 403 if the user does not hold that role. */
    public Issued login(String user, String userKey, String role) {
        byte[] expected = keyHashes.getOrDefault(user, NO_MATCH); // unknown user costs the same as a wrong key
        if (!MessageDigest.isEqual(expected, sha256(userKey)) || expected == NO_MATCH) {
            throw new GatewayException(ErrorCode.ADMIN_UNAUTHORIZED, "invalid user or key");
        }
        if (!authorizer.holdsRole(Subject.operator(user, role))) {
            throw new GatewayException(ErrorCode.PERMISSION_DENIED, "role '" + role + "' is not assigned to this user");
        }
        Instant now = clock.instant();
        JWTClaimsSet claims = new JWTClaimsSet.Builder()
                .issuer(issuer)
                .audience(AUDIENCE)
                .subject(user)
                .claim(ROLE, role)
                .issueTime(Date.from(now))
                .expirationTime(Date.from(now.plus(ttl)))
                .jwtID(UUID.randomUUID().toString())
                .build();
        SignedJWT jwt = new SignedJWT(
                new JWSHeader.Builder(JWSAlgorithm.RS256).keyID(key.getKeyID()).type(JOSEObjectType.JWT).build(), claims);
        try {
            jwt.sign(new RSASSASigner(key));
        } catch (JOSEException e) {
            throw new IllegalStateException("session signing failed", e);
        }
        return new Issued(jwt.serialize(), user, role, now.plus(ttl));
    }

    /** The session's user and role. Whether that role is still assigned is checked on each decision. */
    public Subject verify(String token) throws InvalidToken {
        try {
            JWTClaimsSet claims = processor.process(token, null); // signature first, then checkClaims
            return Subject.operator(claims.getSubject(), claims.getStringClaim(ROLE));
        } catch (ClaimRejected e) {
            throw new InvalidToken(e.failure);
        } catch (ParseException e) {
            throw new InvalidToken(Failure.MALFORMED);
        } catch (BadJOSEException | JOSEException e) {
            throw new InvalidToken(Failure.INVALID_SIGNATURE);
        }
    }

    private void checkClaims(JWTClaimsSet claims) throws BadJWTException {
        if (!issuer.equals(claims.getIssuer())
                || claims.getAudience() == null || !claims.getAudience().contains(AUDIENCE)
                || claims.getSubject() == null || claims.getExpirationTime() == null
                || !(claims.getClaim(ROLE) instanceof String)) {
            throw new ClaimRejected(Failure.INVALID_CLAIMS);
        }
        if (clock.instant().isAfter(claims.getExpirationTime().toInstant().plus(CLOCK_SKEW))) {
            throw new ClaimRejected(Failure.EXPIRED);
        }
    }

    private static byte[] sha256(String value) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
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
}
