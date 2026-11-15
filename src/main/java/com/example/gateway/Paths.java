package com.example.gateway;

import com.example.gateway.error.ErrorCode;
import com.example.gateway.error.GatewayException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Locale;
import org.springframework.web.util.UriUtils;

/** Path canonicalisation and segment-aware prefix matching. */
public final class Paths {

    /** Control-plane prefixes: never routed to upstreams, never accept tenant tokens. */
    public static final List<String> RESERVED = List.of("/admin", "/actuator", "/error");

    private Paths() {}

    /**
     * Returns the decoded path, or throws 400 for anything ambiguous.
     *
     * <p>Gateway bypasses come from two layers disagreeing about a path: the gateway checks
     * {@code /public/../admin} against the allowlist, the upstream resolves it to {@code /admin}. So
     * we refuse dot-segments, encoded slashes, double encoding, empty segments, path parameters and
     * control characters instead of trying to "fix" them. Everything after this sees one canonical path.
     */
    public static String normalize(String rawPath) {
        if (rawPath == null || !rawPath.startsWith("/") || rawPath.length() > 2048) {
            throw bad("path must start with '/' and be at most 2048 characters");
        }
        String lower = rawPath.toLowerCase(Locale.ROOT);
        if (lower.contains("%2f") || lower.contains("%5c") || lower.contains("%25") || rawPath.contains(";")) {
            throw bad("encoded separators, double encoding and path parameters are not allowed");
        }
        String decoded;
        try {
            decoded = UriUtils.decode(rawPath, StandardCharsets.UTF_8);
        } catch (IllegalArgumentException e) {
            throw bad("invalid percent-encoding");
        }
        if (decoded.chars().anyMatch(c -> c < 0x20 || c == 0x7f || c == '\\')) {
            throw bad("control characters are not allowed");
        }
        String[] segments = decoded.split("/", -1);
        for (int i = 1; i < segments.length; i++) {
            boolean last = i == segments.length - 1;
            if ((segments[i].isEmpty() && !last) || segments[i].equals(".") || segments[i].equals("..")) {
                throw bad("empty or dot path segments are not allowed");
            }
        }
        return decoded;
    }

    /** {@code /api/users} matches {@code /api/users} and {@code /api/users/1}, not {@code /api/usersX}. */
    public static boolean matches(String path, String prefix) {
        return prefix.equals("/")
                || path.equals(prefix)
                || (path.startsWith(prefix) && path.charAt(prefix.length()) == '/');
    }

    public static boolean isReserved(String path) {
        return RESERVED.stream().anyMatch(r -> matches(path, r));
    }

    private static GatewayException bad(String detail) {
        return new GatewayException(ErrorCode.BAD_PATH, detail);
    }
}
