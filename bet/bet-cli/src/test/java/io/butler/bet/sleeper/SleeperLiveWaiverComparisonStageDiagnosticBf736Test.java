package io.butler.bet.sleeper;

import io.butler.bet.domain.PlayerSeasonProduction;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SleeperLiveWaiverComparisonStageDiagnosticBf736Test {
    @Test
    void measuresExistingComparisonSourcesOnceWithoutChangingBundleResult() throws Exception {
        var candidates = new SleeperLiveWaiverComparisonExecutionBundle.CandidateFrame(
            "L", "M", 1, 1, List.of(candidate("c1", "BC1")));
        var roster = new SleeperLiveWaiverComparisonExecutionBundle.RosterFrame(
            "L", "M", "W", "S", "owner", 1,
            1, 0, 1, 0, 0,
            List.of(roster("r1", "BR1")));

        AtomicInteger methodologyCalls = new AtomicInteger();
        AtomicInteger candidateCalls = new AtomicInteger();
        AtomicInteger rosterCalls = new AtomicInteger();
        AtomicInteger productionCalls = new AtomicInteger();

        var diagnostic = new SleeperLiveWaiverComparisonStageDiagnostic(
            (league, owner) -> {
                methodologyCalls.incrementAndGet();
                return methodology();
            },
            league -> {
                candidateCalls.incrementAndGet();
                return candidates;
            },
            (league, owner) -> {
                rosterCalls.incrementAndGet();
                return roster;
            },
            (playerIds, season) -> {
                productionCalls.incrementAndGet();
                assertEquals(2025, season);
                assertEquals(java.util.Set.of("BC1", "BR1"), playerIds);
                return List.of(
                    production("c1", "BC1", 200),
                    production("r1", "BR1", 100));
            });

        var measured = diagnostic.measure("L", "owner");

        assertEquals(1, methodologyCalls.get());
        assertEquals(1, candidateCalls.get());
        assertEquals(1, rosterCalls.get());
        assertEquals(1, productionCalls.get());
        assertEquals(1, measured.bundle().comparisons().pairCount());
        assertEquals(1, measured.bundle().comparisons().pairCounts().candidateDirectionallySupported());
        assertTrue(measured.timing().methodologyMs() >= 0);
        assertTrue(measured.timing().candidateFrameMs() >= 0);
        assertTrue(measured.timing().rosterFrameMs() >= 0);
        assertTrue(measured.timing().productionLoadMs() >= 0);
        assertTrue(measured.timing().residualMs() >= 0);
        assertTrue(measured.timing().totalMs() >= 0);
    }

    private static SleeperLiveWaiverComparisonExecutionBundle.CandidateEntry candidate(
        String sleeperId, String butlerId) {
        return new SleeperLiveWaiverComparisonExecutionBundle.CandidateEntry(
            sleeperId, "Candidate " + sleeperId, "RB", butlerId, true,
            10, 2, 8, "ADD_ONLY", "TM", "Active", null, "RB", 2);
    }

    private static SleeperLiveWaiverComparisonExecutionBundle.RosterEntry roster(
        String sleeperId, String butlerId) {
        return new SleeperLiveWaiverComparisonExecutionBundle.RosterEntry(
            sleeperId, "Roster " + sleeperId, "RB", "BENCH", butlerId, true);
    }

    private static SleeperLiveWaiverCandidateRosterComparisonMethodology.MethodologyReport methodology() {
        return new SleeperLiveWaiverCandidateRosterComparisonMethodology.MethodologyReport(
            SleeperLiveWaiverCandidateRosterComparisonMethodology.POLICY_ID,
            "L", "owner", "M", "W", "S", 2026, "in_season", 1, 1,
            1, 1, 1, 0,
            1, 0, 1, 0, 0, 1, 0,
            List.of(),
            scoring(), Map.of(), Map.of(),
            List.of("pass_td", "pass_yd", "rec", "rec_td", "rec_yd", "rush_td", "rush_yd"),
            List.of("BENCH", "RESERVE"),
            "EXACT_POSITION_ONLY",
            "COMMON_2025_SOURCE_ONLY_ALL_COMMON_SOURCES_MUST_AGREE",
            "SUPPORTED_LEAGUE_SCORING_SUBTOTAL_PER_GAME_NOT_FULL_FANTASY_POINTS",
            "NEWCOMER_REVIEW_NONNUMERIC",
            "MISSING_TARGET_PRIOR_PRODUCTION_PROTECTED_UNRESOLVED",
            "MARKET_TEAM_STATUS_INJURY_DEPTH_DESCRIPTIVE_ONLY_NOT_ARITHMETIC",
            SleeperLiveWaiverCandidateRosterComparisonMethodology.MethodologyState.METHODOLOGY_FROZEN_NO_SELECTION);
    }

    private static Map<String, Double> scoring() {
        Map<String, Double> values = new LinkedHashMap<>();
        values.put("pass_yd", 0.04);
        values.put("pass_td", 4.0);
        values.put("rush_yd", 0.1);
        values.put("rush_td", 6.0);
        values.put("rec", 1.0);
        values.put("rec_yd", 0.1);
        values.put("rec_td", 6.0);
        values.put("rush_2pt", 2.0);
        return values;
    }

    private static PlayerSeasonProduction production(String id, String playerId, int rushingYards) {
        return new PlayerSeasonProduction(
            id, playerId, 2025, 10,
            0, 0, 0,
            rushingYards, 0,
            0, 0, 0,
            0, "nflverse", LocalDate.of(2026, 9, 1));
    }
}
