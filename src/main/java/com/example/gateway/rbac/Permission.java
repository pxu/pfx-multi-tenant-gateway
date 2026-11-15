package com.example.gateway.rbac;

/**
 * What a role allows: an {@code action} on a {@code resource}, written {@code resource:action}.
 * The same shape for the management API ({@code routes:write}) and for upstream services
 * ({@code orders:delete}). Granted to roles only, never to users.
 */
public record Permission(String resource, String action) {

    private static final String NAME = "[a-z0-9][a-z0-9-]{0,62}";

    public Permission {
        if (resource == null || !resource.matches(NAME) || action == null || !action.matches(NAME)) {
            throw new IllegalArgumentException("invalid permission '" + resource + ":" + action + "'");
        }
    }

    /** Parses {@code resource:action}; fails on anything else, so a typo in config stops startup. */
    public static Permission parse(String value) {
        int colon = value == null ? -1 : value.indexOf(':');
        if (colon < 0) {
            throw new IllegalArgumentException("invalid permission '" + value + "': expected resource:action");
        }
        return new Permission(value.substring(0, colon), value.substring(colon + 1));
    }

    @Override
    public String toString() {
        return resource + ":" + action;
    }
}
