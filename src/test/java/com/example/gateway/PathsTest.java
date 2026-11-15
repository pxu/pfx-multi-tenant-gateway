package com.example.gateway;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.example.gateway.error.GatewayException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

class PathsTest {

    @ParameterizedTest
    @CsvSource({"/orders/1, /orders/1", "/orders/, /orders/", "/caf%C3%A9, /café", "/, /"})
    void testNormalize_SafePaths_Decoded(String raw, String expected) {
        assertThat(Paths.normalize(raw)).isEqualTo(expected);
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "/public/../admin", "/public/%2e%2e/admin", "/a/./b", "/a%2fb", "/a%5cb", "/a%252e", "/a//b",
        "/a;jsessionid=1", "/a%00b", "relative", "/%zz"
    })
    void testNormalize_AmbiguousPaths_Rejected(String raw) {
        assertThatThrownBy(() -> Paths.normalize(raw)).isInstanceOf(GatewayException.class);
    }

    @Test
    void testMatches_RespectsSegmentBoundaries() {
        assertThat(Paths.matches("/api/users", "/api/users")).isTrue();
        assertThat(Paths.matches("/api/users/1", "/api/users")).isTrue();
        assertThat(Paths.matches("/api/usersX", "/api/users")).isFalse();
        assertThat(Paths.matches("/anything", "/")).isTrue();
    }

    @Test
    void testIsReserved() {
        assertThat(Paths.isReserved("/admin/routes")).isTrue();
        assertThat(Paths.isReserved("/administrator")).isFalse();
    }
}
