package com.example.gateway.admin;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.gateway.auth.RequiresPermission;
import com.example.gateway.rbac.Permission;
import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.Properties;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.config.YamlPropertiesFactoryBean;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.core.io.ClassPathResource;
import org.springframework.web.bind.annotation.RequestMapping;

/** Build-time guard for fail-closed RBAC: every admin endpoint except login declares its permission. */
class AdminControllerPermissionsTest {

    @Test
    void testEndpoints_EveryHandlerExceptLogin_DeclaresAPermission() {
        // Arrange
        List<Method> endpoints = Stream.of(AdminController.class, RbacController.class).flatMap(c -> Arrays.stream(c.getDeclaredMethods()))
                .filter(m -> AnnotatedElementUtils.hasAnnotation(m, RequestMapping.class))
                .toList();

        // Act
        List<String> undeclared = endpoints.stream()
                .filter(m -> m.getAnnotation(RequiresPermission.class) == null)
                .map(Method::getName)
                .toList();

        // Assert
        assertThat(endpoints).hasSizeGreaterThanOrEqualTo(18);
        assertThat(undeclared).as("admin endpoints without @RequiresPermission").containsExactly("login");
    }

    @Test
    void testEndpoints_EveryDeclaredPermission_IsWellFormedAndGrantedBySomeConfiguredRole() {
        // Arrange: every permission any role in application.yml grants
        YamlPropertiesFactoryBean yaml = new YamlPropertiesFactoryBean();
        yaml.setResources(new ClassPathResource("application.yml"));
        Properties config = yaml.getObject();
        Set<String> granted = config.stringPropertyNames().stream()
                .filter(k -> k.startsWith("gateway.rbac.roles."))
                .map(config::getProperty)
                .collect(Collectors.toSet());

        // Act: declared permissions that no role grants (a typo would make the endpoint unreachable)
        List<String> unreachable = Stream.of(AdminController.class, RbacController.class).flatMap(c -> Arrays.stream(c.getDeclaredMethods()))
                .map(m -> m.getAnnotation(RequiresPermission.class))
                .filter(Objects::nonNull)
                .map(RequiresPermission::value)
                .peek(Permission::parse) // throws if not resource:action
                .filter(p -> !granted.contains(p))
                .toList();

        // Assert
        assertThat(granted).isNotEmpty();
        assertThat(unreachable).isEmpty();
    }
}
