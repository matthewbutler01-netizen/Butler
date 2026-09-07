package io.butler.bet.cli;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ButlerSleeperProviderNativeLineupSensitivityConfigurationLineageCliTest {

    @Test
    void rejectsSelectionArguments() {
        ButlerSleeperProviderNativeLineupSensitivityConfigurationLineageCli.parse(new String[0]);
        ButlerSleeperProviderNativeLineupSensitivityConfigurationLineageCli.parse(null);
        assertThrows(IllegalArgumentException.class,
            () -> ButlerSleeperProviderNativeLineupSensitivityConfigurationLineageCli
                .parse(new String[] {"provider-league"}));
    }

    @Test
    void identifiesUniqueAdjacentSeasonFlexAdditionWithoutRewritingHistory() throws Exception {
        Map<String, String> payloads = Map.of(
            "p2024", leagueJson(
                "p2024", 2024, null,
                List.of("QB", "RB", "TE", "SUPER_FLEX", "BN", "BN")),
            "p2025", leagueJson(
                "p2025", 2025, "p2024",
                List.of("QB", "RB", "TE", "FLEX", "SUPER_FLEX", "BN", "BN")),
            "p2026", leagueJson(
                "p2026", 2026, "p2025",
                List.of("QB", "RB", "TE", "FLEX", "SUPER_FLEX", "BN", "BN")));

        var diagnostic = ButlerSleeperProviderNativeLineupSensitivityConfigurationLineageCli.analyzeLineage(
            "p2025",
            2025,
            "p2026",
            2026,
            List.of("QB", "RB", "TE", "FLEX", "SUPER_FLEX", "BN", "BN"),
            providerId -> requiredPayload(payloads, providerId));

        assertTrue(diagnostic.persistedMatchesProviderLive());
        assertEquals(List.of("QB", "RB", "TE", "FLEX", "SUPER_FLEX"),
            diagnostic.provider().supportedStartingSlots());
        assertEquals(List.of("QB", "RB", "TE", "SUPER_FLEX"),
            diagnostic.predecessor().orElseThrow().supportedStartingSlots());
        assertEquals(
            List.of(new ButlerSleeperProviderNativeLineupSensitivityConfigurationLineageCli.SlotDelta(3, "FLEX")),
            diagnostic.providerAdditions());
        assertEquals(
            ButlerSleeperProviderNativeLineupSensitivityConfigurationLineageCli.PredecessorEvidenceState
                .PREDECESSOR_UNIQUE_SINGLE_SLOT_ADDITION,
            diagnostic.predecessorEvidenceState());
        assertEquals(
            ButlerSleeperProviderNativeLineupSensitivityConfigurationLineageCli.SuccessorState.FOUND,
            diagnostic.successorSearch().state());
        assertEquals("p2026", diagnostic.successorSearch().successor().orElseThrow().id());
        assertTrue(diagnostic.successorAdditions().isEmpty());
    }

    @Test
    void preservesDuplicateOrdinalAmbiguity() {
        var additions = ButlerSleeperProviderNativeLineupSensitivityConfigurationLineageCli
            .exactSingleSlotAdditions(
                List.of("QB", "RB", "RB", "WR"),
                List.of("QB", "RB", "WR"));

        assertEquals(List.of(
            new ButlerSleeperProviderNativeLineupSensitivityConfigurationLineageCli.SlotDelta(1, "RB"),
            new ButlerSleeperProviderNativeLineupSensitivityConfigurationLineageCli.SlotDelta(2, "RB")), additions);
    }

    @Test
    void normalizesSleeperZeroPreviousLeagueSentinelWithoutInventingPredecessorOrSuccessor() throws Exception {
        String provider = leagueJson(
            "p2025", 2025, "0",
            List.of("QB", "RB", "TE", "SUPER_FLEX", "BN"));

        var diagnostic = ButlerSleeperProviderNativeLineupSensitivityConfigurationLineageCli.analyzeLineage(
            "p2025",
            2025,
            "p2025",
            2025,
            List.of("QB", "RB", "TE", "SUPER_FLEX", "BN"),
            providerId -> {
                if (!"p2025".equals(providerId)) throw new IllegalArgumentException("unexpected id");
                return provider;
            });

        assertTrue(diagnostic.predecessor().isEmpty());
        assertEquals(
            ButlerSleeperProviderNativeLineupSensitivityConfigurationLineageCli.PredecessorEvidenceState
                .PREDECESSOR_UNAVAILABLE,
            diagnostic.predecessorEvidenceState());
        assertEquals(
            ButlerSleeperProviderNativeLineupSensitivityConfigurationLineageCli.SuccessorState.CURRENT_IS_PROVIDER,
            diagnostic.successorSearch().state());
        assertTrue(diagnostic.successorSearch().successor().isEmpty());
        assertFalse(diagnostic.provider().previousLeagueId() != null);
    }

    private static String requiredPayload(Map<String, String> payloads, String providerId) {
        String value = payloads.get(providerId);
        if (value == null) throw new IllegalArgumentException("unexpected provider id: " + providerId);
        return value;
    }

    private static String leagueJson(String id, int season, String previous, List<String> rosterPositions) {
        String previousJson = previous == null ? "null" : "\"" + previous + "\"";
        String positions = rosterPositions.stream()
            .map(value -> "\"" + value + "\"")
            .reduce((left, right) -> left + "," + right)
            .orElse("");
        return "{"
            + "\"league_id\":\"" + id + "\","
            + "\"name\":\"League " + season + "\","
            + "\"season\":\"" + season + "\","
            + "\"previous_league_id\":" + previousJson + ","
            + "\"roster_positions\":[" + positions + "],"
            + "\"settings\":{\"type\":2,\"draft_rounds\":3},"
            + "\"scoring_settings\":{}"
            + "}";
    }
}