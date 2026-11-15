package com.example.gateway.rbac;

/**
 * Who is asking, as authentication established it: a user and the <b>one</b> role they are acting
 * as (chosen at login, or when their token was issued).
 *
 * @param tenant the tenant the user belongs to; {@code null} for operators of the gateway itself
 */
public record Subject(String tenant, String user, String role) {

    /** Request attribute holding the operator's subject on admin calls. */
    public static final String ATTRIBUTE = Subject.class.getName();

    public static Subject operator(String user, String role) {
        return new Subject(null, user, role);
    }
}
