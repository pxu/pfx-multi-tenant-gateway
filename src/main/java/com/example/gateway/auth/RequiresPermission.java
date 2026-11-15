package com.example.gateway.auth;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * The permission ({@code resource:action}) an admin endpoint needs, checked by
 * {@link PermissionInterceptor} against the session's active role. On paths the interceptor guards,
 * an endpoint without it is refused (fail closed); {@code AdminControllerPermissionsTest} fails the
 * build if an admin endpoint is added without it.
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.METHOD)
public @interface RequiresPermission {
    String value();
}
