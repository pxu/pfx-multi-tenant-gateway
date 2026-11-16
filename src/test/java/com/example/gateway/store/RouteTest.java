package com.example.gateway.store;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import org.junit.jupiter.api.Test;

class RouteTest {

    private final ObjectMapper json = new ObjectMapper();

    @Test
    void testFallback_AbsentFromJson_DefaultsToTrue() throws Exception {
        // Arrange: a route as stored before the setting existed
        String stored = "{\"id\":\"orders\",\"pathPrefix\":\"/orders\",\"upstreams\":[{\"url\":\"http://a\",\"weight\":1}]}";

        // Act
        Route route = json.readValue(stored, Route.class);

        // Assert
        assertThat(route.fallback()).isTrue();
    }

    @Test
    void testFallback_FalseInJson_KeptAndSurvivesWithId() throws Exception {
        // Arrange
        String body = "{\"id\":\"orders\",\"pathPrefix\":\"/orders\",\"upstreams\":[{\"url\":\"http://a\",\"weight\":1}],"
                + "\"fallback\":false}";

        // Act
        Route route = json.readValue(body, Route.class).withId("orders-2");

        // Assert
        assertThat(route.fallback()).isFalse();
    }

    @Test
    void testConstructor_WithoutFallbackArgument_FallbackOn() {
        // Arrange + Act
        Route route = new Route("orders", "/orders", List.of(new Route.Upstream("http://a", 1)), null, false, 0, 0, 0);

        // Assert
        assertThat(route.fallback()).isTrue();
    }
}
