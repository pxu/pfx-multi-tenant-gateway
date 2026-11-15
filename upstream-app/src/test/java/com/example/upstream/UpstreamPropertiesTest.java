package com.example.upstream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class UpstreamPropertiesTest {

    @Test
    void testConstructor_failPctAbove100_throws() {
        assertThatThrownBy(() -> new UpstreamProperties("v", 101, 1000, false, 0, 0))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("FAIL_PCT");
    }

    @Test
    void testConstructor_negativeMaxDelay_throws() {
        assertThatThrownBy(() -> new UpstreamProperties("v", 20, -5, false, 0, 0))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("MAX_DELAY_MS");
    }

    @Test
    void testConstructor_delayMinAboveMax_throws() {
        assertThatThrownBy(() -> new UpstreamProperties("v", 0, 1000, false, 500, 100))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("DELAY_MIN_MS");
    }

    @Test
    void testConstructor_blankVersion_defaultsToStable() {
        // Act
        UpstreamProperties properties = new UpstreamProperties(" ", 20, 1000, false, 0, 0);

        // Assert
        assertThat(properties.version()).isEqualTo("stable");
        assertThat(properties.failPct()).isEqualTo(20);
        assertThat(properties.delaysEveryRequest()).isFalse();
    }
}
