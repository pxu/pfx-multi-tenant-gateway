package com.example.gateway.routing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class RendezvousHashTest {

    record Item(String id, int weight) {}

    private static final int KEYS = 20_000;

    private static Item pick(String key, List<Item> items) {
        return RendezvousHash.pick(key, items, Item::weight, Item::id);
    }

    private static Map<String, String> assign(List<Item> items) {
        Map<String, String> byKey = new HashMap<>();
        for (int i = 0; i < KEYS; i++) {
            byKey.put("tenant-" + i, pick("tenant-" + i, items).id());
        }
        return byKey;
    }

    private static double share(Map<String, String> assignment, String id) {
        return assignment.values().stream().filter(id::equals).count() / (double) KEYS;
    }

    @Test
    void testPick_SameKey_AlwaysSameItem() {
        List<Item> items = List.of(new Item("stable", 1), new Item("canary", 1));

        Item first = pick("acme", items);

        for (int i = 0; i < 100; i++) {
            assertThat(pick("acme", items)).isEqualTo(first);
        }
        // order of the list does not matter either
        assertThat(pick("acme", List.of(items.get(1), items.get(0)))).isEqualTo(first);
    }

    @Test
    void testPick_ManyKeys_SplitByWeight() {
        Map<String, String> assignment = assign(List.of(new Item("stable", 9), new Item("canary", 1)));

        assertThat(share(assignment, "canary")).isBetween(0.09, 0.11); // expected 0.10
    }

    @Test
    void testPick_CanaryWeightRaised_KeysOnlyMoveOntoTheCanary() {
        // Arrange
        Map<String, String> before = assign(List.of(new Item("stable", 9), new Item("canary", 1)));

        // Act: 10% -> ~18% (weights 9:2)
        Map<String, String> after = assign(List.of(new Item("stable", 9), new Item("canary", 2)));

        // Assert: every tenant on the canary is still there; the new ones came from stable
        before.forEach((key, id) -> {
            if (id.equals("canary")) {
                assertThat(after.get(key)).as(key).isEqualTo("canary");
            }
        });
        assertThat(share(after, "canary")).isBetween(0.17, 0.19); // expected 2/11
    }

    @Test
    void testPick_ItemRemoved_OnlyItsKeysMove() {
        // Arrange
        Map<String, String> before = assign(List.of(new Item("a", 1), new Item("b", 1), new Item("c", 1)));

        // Act
        Map<String, String> after = assign(List.of(new Item("a", 1), new Item("b", 1)));

        // Assert
        before.forEach((key, id) -> {
            if (!id.equals("c")) {
                assertThat(after.get(key)).as(key).isEqualTo(id);
            }
        });
    }

    @Test
    void testPick_Empty_Rejected() {
        assertThatThrownBy(() -> pick("acme", List.of())).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void testUnit_ExtremeHashes_StayStrictlyInsideZeroOne() {
        assertThat(RendezvousHash.unit(0L)).isGreaterThan(0).isLessThan(1);
        assertThat(RendezvousHash.unit(-1L)).isGreaterThan(0).isLessThan(1);
    }
}
