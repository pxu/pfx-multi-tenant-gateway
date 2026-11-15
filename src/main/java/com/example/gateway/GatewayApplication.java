package com.example.gateway;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

/**
 * Multi-tenant API gateway.
 *
 * <p>Request path (see README for the diagram):
 * {@code AuditFilter -> AuthFilter -> ProxyController (tenant, route, policy) -> UpstreamProxy}.
 * Control plane: {@code /admin/**} manages tenants, routes and tokens at runtime.
 */
@SpringBootApplication
@ConfigurationPropertiesScan
public class GatewayApplication {

    public static void main(String[] args) {
        SpringApplication.run(GatewayApplication.class, args);
    }
}
