package com.example.gateway.routing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.SplittableRandom;
import org.junit.jupiter.api.Test;

class AliasTableTest {

    @Test
    void testSample_ManyDraws_ProportionalToWeights() {
        // Arrange
        AliasTable<String> table = new AliasTable<>(List.of("a", "b", "c"), new int[] {70, 20, 10});
        SplittableRandom random = new SplittableRandom(42);
        Map<String, Integer> counts = new HashMap<>();

        // Act
        for (int i = 0; i < 100_000; i++) {
            counts.merge(table.sample(random), 1, Integer::sum);
        }

        // Assert: within 1 percentage point of the weights
        assertThat(counts.get("a")).isBetween(69_000, 71_000);
        assertThat(counts.get("b")).isBetween(19_000, 21_000);
        assertThat(counts.get("c")).isBetween(9_000, 11_000);
    }

    @Test
    void testSample_SingleItem_AlwaysChosen() {
        AliasTable<String> table = new AliasTable<>(List.of("only"), new int[] {5});

        assertThat(table.sample(new SplittableRandom())).isEqualTo("only");
    }

    @Test
    void testConstructor_NonPositiveWeight_Rejected() {
        assertThatThrownBy(() -> new AliasTable<>(List.of("a", "b"), new int[] {1, 0}))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
