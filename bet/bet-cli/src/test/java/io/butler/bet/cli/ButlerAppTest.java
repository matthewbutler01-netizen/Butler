package io.butler.bet.cli;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ButlerAppTest {
    @Test
    void parsesMixedTradeAssetsAndKeepsBarePlayerCompatibility() {
        var parsed = ButlerApp.parseTradePackage(
            " player:p1, pick:d1, p2, PICK:d2 ", "side-a-assets");

        assertEquals(java.util.List.of("p1", "p2"), parsed.playerIds());
        assertEquals(java.util.List.of("d1", "d2"), parsed.draftPickIds());
    }

    @Test
    void rejectsBlankIdsAndUnknownAssetPrefixes() {
        assertThrows(IllegalArgumentException.class,
            () -> ButlerApp.parseTradePackage("pick:", "side-a-assets"));
        assertThrows(IllegalArgumentException.class,
            () -> ButlerApp.parseTradePackage("future:d1", "side-a-assets"));
        assertThrows(IllegalArgumentException.class,
            () -> ButlerApp.parseTradePackage("p1,", "side-a-assets"));
    }
}
