package io.butler.bet.sleeper;

import io.butler.bet.domain.PlayerSeasonProduction;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;

class SleeperLiveWaiverComparisonExecutionBundleBf734BatchProductionTest {
    @Test
    void loadsRequiredHistoricalProductionOnceAndDeduplicatesSharedComparator() throws Exception {
        var candidates = new SleeperLiveWaiverComparisonExecutionBundle.CandidateFrame(
            "L", "M", 2, 2, List.of(
                candidate("c1", "BC1"),
                candidate("c2", "BC2")));
        var roster = new SleeperLiveWaiverComparisonExecutionBundle.RosterFrame(
            "L", "M", "W", "S", "owner", 1,
            1, 0, 1, 0, 0,
            List.of(roster("r1", "BR1")));

        AtomicInteger calls = new AtomicInteger();
        AtomicReference<Set<String>> requestedIds = new AtomicReference<>();
        var bundle = new SleeperLiveWaiverComparisonExecutionBundle(
            (league, owner) -> methodology(),
            ignored -> candidates,
            (league, owner) -> roster,
            (playerIds, season) -> {
                calls.incrementAndGet();
                requestedIds.set(new LinkedHashSet<>(playerIds));
                assertEquals(2025, season);
                return List.of(
                    production("c1", "BC1", 200),
                    production("c2", "BC2", 50),
                    production("r1", "BR1", 100));
            });

        var report = bundle.run("L", "owner");

        assertEquals(1, calls.get());
        assertEquals(Set.of("BC1", "BC2", "BR1"), requestedIds.get());
        assertEquals(2, report.comparisons().pairCount());
        assertEquals(1, report.comparisons().pairCounts().candidateDirectionallySupported());
        assertEquals(1, report.comparisons().pairCounts().rosterDirectionallySupported());
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
            2, 2, 2, 0,
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
