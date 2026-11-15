package com.example.gateway.auth;

import com.example.gateway.audit.AuditRecord;
import com.example.gateway.error.ErrorCode;
import com.example.gateway.error.GatewayException;
import com.example.gateway.rbac.Authorizer;
import com.example.gateway.rbac.Permission;
import com.example.gateway.rbac.Subject;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerInterceptor;

/**
 * The enforcement point for the management API: asks the {@link Authorizer} whether the session's
 * user, acting as its one active role, has the endpoint's {@link RequiresPermission}. Registered
 * (in {@code GatewayConfig}) on the paths that use sessions, today {@code /admin/**} except login.
 * {@code AuthFilter} has already verified the session; this runs once Spring has picked the
 * handler, which is what makes the handler's annotation readable.
 *
 * <p>Fail closed: a handler without the annotation is refused, including the proxy's catch-all
 * when no admin endpoint matched (a typo gets 403, not a route lookup). 401 = no valid session,
 * 403 {@code PERMISSION_DENIED} = the active role lacks the permission. Both are thrown, so they are
 * rendered and audited like any other rejection, and the handler is never reached.
 */
public class PermissionInterceptor implements HandlerInterceptor {

    private final Authorizer authorizer;

    public PermissionInterceptor(Authorizer authorizer) {
        this.authorizer = authorizer;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        if (!(handler instanceof HandlerMethod method)) {
            return true; // not a controller method (e.g. static resources): nothing to authorize
        }
        Subject session = (Subject) request.getAttribute(Subject.ATTRIBUTE);
        if (session == null) {
            throw new GatewayException(ErrorCode.ADMIN_UNAUTHORIZED, "missing session");
        }
        RequiresPermission rule = method.getMethodAnnotation(RequiresPermission.class);
        if (rule == null) {
            throw new GatewayException(ErrorCode.PERMISSION_DENIED, "no permission is declared for this endpoint");
        }
        Permission needed = Permission.parse(rule.value());
        if (!authorizer.allowed(session, needed)) {
            throw new GatewayException(ErrorCode.PERMISSION_DENIED, authorizer.holdsRole(session)
                    ? "role '" + session.role() + "' does not have " + needed
                    : "role '" + session.role() + "' is no longer assigned to this user");
        }
        AuditRecord audit = AuditRecord.of(request);
        if (audit != null) {
            audit.allow();
        }
        return true;
    }
}
