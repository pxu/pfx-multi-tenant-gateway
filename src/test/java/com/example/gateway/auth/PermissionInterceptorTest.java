package com.example.gateway.auth;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.gateway.error.ErrorCode;
import com.example.gateway.error.GatewayException;
import com.example.gateway.rbac.RbacAuthorizer;
import com.example.gateway.rbac.Subject;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.web.method.HandlerMethod;

class PermissionInterceptorTest {

    static class Handlers {
        @RequiresPermission("routes:write")
        public void writeRoutes() {}

        public void undeclared() {}
    }

    private final Map<String, List<String>> operatorRoles = new HashMap<>(Map.of("alice", List.of("viewer", "operator")));
    private final PermissionInterceptor interceptor = new PermissionInterceptor(new RbacAuthorizer(
            Map.of("viewer", List.of("routes:read"), "operator", List.of("routes:read", "routes:write")),
            Map::of, (tenant, user) -> operatorRoles.getOrDefault(user, List.of())));

    private ErrorCode rejection(Subject session, String handlerName) throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest();
        if (session != null) {
            request.setAttribute(Subject.ATTRIBUTE, session);
        }
        HandlerMethod handler = new HandlerMethod(new Handlers(), Handlers.class.getMethod(handlerName));
        try {
            interceptor.preHandle(request, new MockHttpServletResponse(), handler);
            return null;
        } catch (GatewayException e) {
            return e.code();
        }
    }

    @Test
    void testPreHandle_ActiveRoleHasPermission_Proceeds() throws Exception {
        assertThat(rejection(Subject.operator("alice", "operator"), "writeRoutes")).isNull();
    }

    @Test
    void testPreHandle_ActiveRoleLacksPermission_Rejected403() throws Exception {
        // alice also holds operator, but this session acts as viewer
        assertThat(rejection(Subject.operator("alice", "viewer"), "writeRoutes")).isEqualTo(ErrorCode.PERMISSION_DENIED);
    }

    @Test
    void testPreHandle_RoleRevokedDuringSession_Rejected403() throws Exception {
        operatorRoles.put("alice", List.of("viewer"));

        assertThat(rejection(Subject.operator("alice", "operator"), "writeRoutes")).isEqualTo(ErrorCode.PERMISSION_DENIED);
    }

    @Test
    void testPreHandle_NoAnnotation_FailsClosed() throws Exception {
        assertThat(rejection(Subject.operator("alice", "operator"), "undeclared")).isEqualTo(ErrorCode.PERMISSION_DENIED);
    }

    @Test
    void testPreHandle_NoSession_Rejected401() throws Exception {
        assertThat(rejection(null, "writeRoutes")).isEqualTo(ErrorCode.ADMIN_UNAUTHORIZED);
    }
}
