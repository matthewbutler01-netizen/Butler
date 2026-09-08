package io.butler.bet.sleeper;

import io.butler.bet.domain.PlayerSeasonProduction;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SleeperLiveWaiverCandidateRosterComparisonMethodologyTest {

    @Test
    void freezesMethodologyAndRetainsUnsupportedLeagueScoringAsExplicitExclusions() throws Exception {
        Map<String, Double> scoring = coreScoring();
        scoring.put("bonus_rec_te", 0.5d);
        scoring.put("pass_2pt", 2.0d);

        var report = subject(readiness(), scoring).audit("league-1", "owner-1");

        assertEquals(
            SleeperLiveWaiverCandidateRosterComparisonMethodology.MethodologyState.METHODOLOGY_FROZEN_NO_SELECTION,
            report.state());
        assertEquals(42, report.reviewableCandidateCount());
        assertEquals(13, report.targetPriorProductionPresent());
        assertEquals(2, report.targetPriorProductionMissing());
        assertEquals(List.of("BENCH", "RESERVE"), report.replacementSlots());
        assertTrue(report.supportedScoringRules().containsKey("pass_2pt"));
        assertEquals(2, report.supportedScoringRules().get("pass_2pt").minimumRawSchemaVersion());
        assertEquals(0.5d, report.unsupportedScoringSettings().get("bonus_rec_te"));
        assertEquals(2, report.protectedMissingProduction().size());
        assertEquals("NEWCOMER_REVIEW_NONNUMERIC", report.newcomerRule());
        assertEquals("MISSING_TARGET_PRIOR_PRODUCTION_PROTECTED_UNRESOLVED", report.missingRosterProductionRule());
    }

    @Test
    void missingCoreLeagueScoringKeyFailsClosed() {
        Map<String, Double> scoring = coreScoring();
        scoring.remove("rec");

        var error = assertThrows(IllegalStateException.class,
            () -> subject(readiness(), scoring).audit("league-1", "owner-1"));

        assertTrue(error.getMessage().contains("core supported league scoring keys are missing"));
        assertTrue(error.getMessage().contains("rec"));
    }

    @Test
    void schemaIneligibleExtendedScoringDimensionIsExcludedRatherThanAssumedZero() {
        Map<String, Double> scoring = coreScoring();
        scoring.put("pass_2pt", 2.0d);
        scoring.put("fum_lost", -2.0d);
        PlayerSeasonProduction legacy = new PlayerSeasonProduction(
            "prod-1", "player-1", 2025, 10,
            1000, 10, 2,
            200, 2,
            30, 300, 3,
            1, "nflverse", LocalDate.of(2026, 9, 8));

        var subtotal = SleeperLiveWaiverCandidateRosterComparisonMethodology.supportedSubtotal(legacy, scoring);

        assertEquals("COMPARABLE_SUPPORTED_SUBTOTAL", subtotal.state());
        assertTrue(subtotal.includedScoringKeys().contains("pass_yd"));
        assertTrue(subtotal.includedScoringKeys().contains("fum_lost"));
        assertFalse(subtotal.includedScoringKeys().contains("pass_2pt"));
        assertEquals(List.of("pass_2pt"), subtotal.schemaExcludedScoringKeys());
        assertEquals(subtotal.supportedSubtotal() / 10.0d, subtotal.supportedSubtotalPerGame());
    }

    @Test
    void nonpositiveGamesRemainNonnumeric() {
        PlayerSeasonProduction production = new PlayerSeasonProduction(
            "prod-1", "player-1", 2025, 0,
            0, 0, 0, 0, 0, 0, 0, 0, 0,
            "nflverse", LocalDate.of(2026, 9, 8));

        var subtotal = SleeperLiveWaiverCandidateRosterComparisonMethodology.supportedSubtotal(
            production, coreScoring());

        assertEquals("GAMES_PLAYED_NONPOSITIVE", subtotal.state());
        assertEquals(null, subtotal.supportedSubtotal());
        assertEquals(null, subtotal.supportedSubtotalPerGame());
    }

    @Test
    void replacementPoolIsBenchAndReserveOnly() {
        assertTrue(SleeperLiveWaiverCandidateRosterComparisonMethodology.eligibleReplacementSlot("BENCH"));
        assertTrue(SleeperLiveWaiverCandidateRosterComparisonMethodology.eligibleReplacementSlot("reserve"));
        assertFalse(SleeperLiveWaiverCandidateRosterComparisonMethodology.eligibleReplacementSlot("STARTER"));
        assertFalse(SleeperLiveWaiverCandidateRosterComparisonMethodology.eligibleReplacementSlot("TAXI"));
    }

    @Test
    void firstComparisonLaneRequiresExactSamePosition() {
        assertTrue(SleeperLiveWaiverCandidateRosterComparisonMethodology.samePosition("RB", "rb"));
        assertFalse(SleeperLiveWaiverCandidateRosterComparisonMethodology.samePosition("RB", "WR"));
    }

    @Test
    void allCommonSourcesMustAgreeBeforeDirectionalSupportExists() {
        assertEquals(
            SleeperLiveWaiverCandidateRosterComparisonMethodology.PairDirection.CANDIDATE_DIRECTIONALLY_SUPPORTED,
            SleeperLiveWaiverCandidateRosterComparisonMethodology.directionAcrossCommonSources(
                Map.of("a", 12.0d, "b", 11.0d), Map.of("a", 10.0d, "b", 9.0d)));
        assertEquals(
            SleeperLiveWaiverCandidateRosterComparisonMethodology.PairDirection.SOURCE_DIRECTION_UNRESOLVED,
            SleeperLiveWaiverCandidateRosterComparisonMethodology.directionAcrossCommonSources(
                Map.of("a", 12.0d, "b", 8.0d), Map.of("a", 10.0d, "b", 9.0d)));
        assertEquals(
            SleeperLiveWaiverCandidateRosterComparisonMethodology.PairDirection.NO_COMMON_SOURCE,
            SleeperLiveWaiverCandidateRosterComparisonMethodology.directionAcrossCommonSources(
                Map.of("a", 12.0d), Map.of("b", 9.0d)));
    }

    @Test
    void readinessWithoutReplacementComparatorFailsClosed() {
        var frame = new SleeperLiveWaiverCandidateRosterComparisonMethodology.ReadinessFrame(
            "league-1", "owner-1", "market-1", "waiver-1", "sleeper-1",
            2026, "in_season", 1, 1,
            51, 42, 32, 10,
            9, 9, 0, 0, 0,
            9, 0, List.of(),
            SleeperLiveWaiverCandidateRosterComparisonReadinessAudit.AuthorizationState.READY_FOR_COMPARISON_METHODOLOGY);

        var error = assertThrows(IllegalStateException.class,
            () -> subject(frame, coreScoring()).audit("league-1", "owner-1"));
        assertTrue(error.getMessage().contains("no BENCH/RESERVE replacement comparators"));
    }

    private static SleeperLiveWaiverCandidateRosterComparisonMethodology subject(
        SleeperLiveWaiverCandidateRosterComparisonMethodology.ReadinessFrame frame,
        Map<String, Double> scoring) {
        return new SleeperLiveWaiverCandidateRosterComparisonMethodology(
            (leagueId, ownerId) -> frame,
            leagueId -> scoring);
    }

    private static SleeperLiveWaiverCandidateRosterComparisonMethodology.ReadinessFrame readiness() {
        return new SleeperLiveWaiverCandidateRosterComparisonMethodology.ReadinessFrame(
            "league-1", "owner-1", "market-1", "waiver-1", "sleeper-1",
            2026, "in_season", 1, 1,
            51, 42, 32, 10,
            15, 9, 5, 1, 0,
            13, 2,
            List.of(
                new SleeperLiveWaiverCandidateRosterComparisonMethodology.ProtectedMissingProduction(
                    "13288", "Nicholas Singleton", "RB", "BENCH"),
                new SleeperLiveWaiverCandidateRosterComparisonMethodology.ProtectedMissingProduction(
                    "13281", "Jordyn Tyson", "WR", "RESERVE")),
            SleeperLiveWaiverCandidateRosterComparisonReadinessAudit.AuthorizationState.READY_FOR_COMPARISON_METHODOLOGY);
    }

    private static Map<String, Double> coreScoring() {
        Map<String, Double> result = new LinkedHashMap<>();
        result.put("pass_yd", 0.04d);
        result.put("pass_td", 4.0d);
        result.put("pass_int", -2.0d);
        result.put("rush_yd", 0.1d);
        result.put("rush_td", 6.0d);
        result.put("rec", 1.0d);
        result.put("rec_yd", 0.1d);
        result.put("rec_td", 6.0d);
        return result;
    }
}
