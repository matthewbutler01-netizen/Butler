package io.butler.bet.sleeper;

import io.butler.bet.domain.PlayerSeasonProduction;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SleeperLiveWaiverComparisonExecutionBundleTest {
    @Test
    void executesPairwiseEvidenceBuildsSeparateShortlistLanesAndAuthorizesNextMethod() throws Exception {
        var bundle = standardBundle();

        var report = bundle.run("L", "owner");

        assertEquals(SleeperLiveWaiverComparisonExecutionBundle.ComparisonState.COMPARISONS_EXECUTED_EVIDENCE_ONLY,
            report.comparisons().state());
        assertEquals(6, report.comparisons().reviewableCandidateCount());
        assertEquals(3, report.comparisons().replacementPoolCount());
        assertEquals(8, report.comparisons().pairCount());
        assertEquals(2, report.comparisons().pairCounts().candidateDirectionallySupported());
        assertEquals(1, report.comparisons().pairCounts().rosterDirectionallySupported());
        assertEquals(1, report.comparisons().pairCounts().noCommonSource());
        assertEquals(3, report.comparisons().pairCounts().targetPriorProductionProtected());
        assertEquals(1, report.comparisons().pairCounts().newcomerNonnumeric());

        assertEquals(SleeperLiveWaiverComparisonExecutionBundle.ShortlistState.SHORTLIST_BUILT_EVIDENCE_ONLY,
            report.shortlist().state());
        assertEquals(2, report.shortlist().historicalShortlistCount());
        assertEquals(1, report.shortlist().newcomerShortlistCount());
        assertEquals(3, report.shortlist().shortlist().size());
        assertEquals(List.of("c1", "c3", "c4"), report.shortlist().shortlist().stream()
            .map(value -> value.candidate().sleeperPlayerId()).toList());

        var c1 = decision(report, "c1");
        assertEquals(SleeperLiveWaiverComparisonExecutionBundle.CandidateShortlistState.HISTORICAL_DIRECTIONAL_SHORTLIST,
            c1.state());
        assertEquals(List.of("r1"), c1.candidateSupportedComparatorSleeperIds());
        assertEquals(List.of("r1", "r2"), c1.eligibleComparatorSleeperIds());

        var c2 = decision(report, "c2");
        assertEquals(SleeperLiveWaiverComparisonExecutionBundle.CandidateShortlistState.HISTORICAL_ROSTER_DIRECTION_CONFLICT,
            c2.state());
        var c4 = decision(report, "c4");
        assertEquals(SleeperLiveWaiverComparisonExecutionBundle.CandidateShortlistState.NEWCOMER_REVIEW_SHORTLIST,
            c4.state());
        var c5 = decision(report, "c5");
        assertEquals(SleeperLiveWaiverComparisonExecutionBundle.CandidateShortlistState.NO_ELIGIBLE_REPLACEMENT_COMPARATOR,
            c5.state());
        var c6 = decision(report, "c6");
        assertEquals(SleeperLiveWaiverComparisonExecutionBundle.CandidateShortlistState.HISTORICAL_NO_DIRECTIONAL_SUPPORT,
            c6.state());

        assertEquals(SleeperLiveWaiverComparisonExecutionBundle.FinalDecisionAuthorizationState.READY_FOR_FINAL_WAIVER_DECISION_METHOD,
            report.decisionReadiness().state());
        assertEquals(3, report.decisionReadiness().shortlistCount());

        assertFalse(report.comparisons().candidates().stream()
            .flatMap(value -> value.eligibleComparatorSleeperIds().stream())
            .anyMatch("rtaxi"::equals));
    }

    @Test
    void latest2025ObservationPerSourceControlsDirectionInsteadOfOlderRow() throws Exception {
        var report = standardBundle().run("L", "owner");
        var pair = report.comparisons().pairs().stream()
            .filter(value -> value.candidate().sleeperPlayerId().equals("c1"))
            .filter(value -> value.roster().sleeperPlayerId().equals("r1"))
            .findFirst().orElseThrow();

        assertEquals(SleeperLiveWaiverComparisonExecutionBundle.PairState.CANDIDATE_DIRECTIONALLY_SUPPORTED,
            pair.state());
        assertEquals(LocalDate.of(2026, 9, 1), pair.sourceComparisons().get(0).candidateAsOf());
    }

    @Test
    void commonSourcesThatDisagreeRemainUnresolvedAndCannotEnterHistoricalShortlist() throws Exception {
        Map<String, List<PlayerSeasonProduction>> production = new LinkedHashMap<>();
        production.put("bc", List.of(
            v1("c-a", "bc", "a", LocalDate.of(2026, 9, 1), 200, 0, 0),
            v1("c-b", "bc", "b", LocalDate.of(2026, 9, 1), 50, 0, 0)));
        production.put("br", List.of(
            v1("r-a", "br", "a", LocalDate.of(2026, 9, 1), 100, 0, 0),
            v1("r-b", "br", "b", LocalDate.of(2026, 9, 1), 100, 0, 0)));
        var bundle = onePairBundle(true, true, production);

        var report = bundle.run("L", "owner");

        assertEquals(SleeperLiveWaiverComparisonExecutionBundle.PairState.SOURCE_DIRECTION_UNRESOLVED,
            report.comparisons().pairs().get(0).state());
        assertEquals(SleeperLiveWaiverComparisonExecutionBundle.CandidateShortlistState.HISTORICAL_SOURCE_DIRECTION_UNRESOLVED,
            report.shortlist().decisions().get(0).state());
        assertTrue(report.shortlist().shortlist().isEmpty());
        assertEquals(SleeperLiveWaiverComparisonExecutionBundle.FinalDecisionAuthorizationState.NO_SHORTLIST_EVIDENCE_FOR_FINAL_DECISION_METHOD,
            report.decisionReadiness().state());
    }

    @Test
    void schemaSupportMismatchRemainsUnresolvedRatherThanComparingUnequalDimensions() throws Exception {
        Map<String, List<PlayerSeasonProduction>> production = new LinkedHashMap<>();
        production.put("bc", List.of(v1("c", "bc", "nflverse", LocalDate.of(2026, 9, 1), 100, 0, 0)));
        production.put("br", List.of(v2("r", "br", "nflverse", LocalDate.of(2026, 9, 1), 100, 0, 1)));
        var bundle = onePairBundle(true, true, production);

        var report = bundle.run("L", "owner");
        var pair = report.comparisons().pairs().get(0);

        assertEquals(SleeperLiveWaiverComparisonExecutionBundle.PairState.SOURCE_DIRECTION_UNRESOLVED, pair.state());
        assertEquals("SCHEMA_SUPPORT_MISMATCH", pair.sourceComparisons().get(0).sourceState());
    }

    @Test
    void missingTargetProductionIsProtectedAndNewcomerIsNeverNumericallyPenalized() throws Exception {
        Map<String, List<PlayerSeasonProduction>> production = new LinkedHashMap<>();
        production.put("bc", List.of(v1("c", "bc", "nflverse", LocalDate.of(2026, 9, 1), 100, 0, 0)));
        var protectedTarget = onePairBundle(true, false, production).run("L", "owner");
        assertEquals(SleeperLiveWaiverComparisonExecutionBundle.PairState.TARGET_PRIOR_PRODUCTION_PROTECTED,
            protectedTarget.comparisons().pairs().get(0).state());

        var newcomer = onePairBundle(false, true, Map.of()).run("L", "owner");
        assertEquals(SleeperLiveWaiverComparisonExecutionBundle.PairState.NEWCOMER_NONNUMERIC,
            newcomer.comparisons().pairs().get(0).state());
        assertEquals(SleeperLiveWaiverComparisonExecutionBundle.CandidateShortlistState.NEWCOMER_REVIEW_SHORTLIST,
            newcomer.shortlist().decisions().get(0).state());
    }

    private static SleeperLiveWaiverComparisonExecutionBundle.CandidateShortlistDecision decision(
        SleeperLiveWaiverComparisonExecutionBundle.BundleReport report, String id) {
        return report.shortlist().decisions().stream()
            .filter(value -> value.candidate().sleeperPlayerId().equals(id))
            .findFirst().orElseThrow();
    }

    private static SleeperLiveWaiverComparisonExecutionBundle standardBundle() {
        var candidates = new SleeperLiveWaiverComparisonExecutionBundle.CandidateFrame(
            "L", "M", 6, 6, List.of(
                candidate("c1", "BC1", "RB", true),
                candidate("c2", "BC2", "RB", true),
                candidate("c3", "BC3", "WR", true),
                candidate("c4", "BC4", "WR", false),
                candidate("c5", "BC5", "QB", true),
                candidate("c6", "BC6", "RB", true)));
        var roster = new SleeperLiveWaiverComparisonExecutionBundle.RosterFrame(
            "L", "M", "W", "S", "owner", 1, 6, 2, 2, 1, 1, List.of(
                roster("r1", "BR1", "RB", "BENCH", true),
                roster("r2", "BR2", "RB", "RESERVE", false),
                roster("w1", "BW1", "WR", "BENCH", true),
                roster("q1", "BQ1", "QB", "STARTER", true),
                roster("t1", "BT1", "TE", "STARTER", true),
                roster("rtaxi", "BRT", "RB", "TAXI", true)));

        Map<String, List<PlayerSeasonProduction>> production = new LinkedHashMap<>();
        production.put("BC1", List.of(
            v1("c1-old", "BC1", "nflverse", LocalDate.of(2026, 8, 1), 50, 0, 0),
            v1("c1-new", "BC1", "nflverse", LocalDate.of(2026, 9, 1), 200, 0, 0)));
        production.put("BC2", List.of(v1("c2", "BC2", "nflverse", LocalDate.of(2026, 9, 1), 50, 0, 0)));
        production.put("BC3", List.of(v1("c3", "BC3", "nflverse", LocalDate.of(2026, 9, 1), 0, 80, 800)));
        production.put("BC5", List.of(v1("c5", "BC5", "nflverse", LocalDate.of(2026, 9, 1), 0, 0, 0)));
        production.put("BC6", List.of(v1("c6", "BC6", "other", LocalDate.of(2026, 9, 1), 300, 0, 0)));
        production.put("BR1", List.of(v1("r1", "BR1", "nflverse", LocalDate.of(2026, 9, 1), 100, 0, 0)));
        production.put("BW1", List.of(v1("w1", "BW1", "nflverse", LocalDate.of(2026, 9, 1), 0, 40, 400)));

        return new SleeperLiveWaiverComparisonExecutionBundle(
            (league, owner) -> methodology(6, 6, 5, 1, 6, 2, 2, 1, 1, 5, 1),
            ignored -> candidates,
            (league, owner) -> roster,
            playerId -> production.getOrDefault(playerId, List.of()));
    }

    private static SleeperLiveWaiverComparisonExecutionBundle onePairBundle(
        boolean candidatePrior,
        boolean rosterPrior,
        Map<String, List<PlayerSeasonProduction>> production) {
        var candidates = new SleeperLiveWaiverComparisonExecutionBundle.CandidateFrame(
            "L", "M", 1, 1, List.of(candidate("c", "bc", "RB", candidatePrior)));
        var roster = new SleeperLiveWaiverComparisonExecutionBundle.RosterFrame(
            "L", "M", "W", "S", "owner", 1, 1, 0, 1, 0, 0,
            List.of(roster("r", "br", "RB", "BENCH", rosterPrior)));
        return new SleeperLiveWaiverComparisonExecutionBundle(
            (league, owner) -> methodology(1, 1, candidatePrior ? 1 : 0, candidatePrior ? 0 : 1,
                1, 0, 1, 0, 0, rosterPrior ? 1 : 0, rosterPrior ? 0 : 1),
            ignored -> candidates,
            (league, owner) -> roster,
            playerId -> production.getOrDefault(playerId, List.of()));
    }

    private static SleeperLiveWaiverComparisonExecutionBundle.CandidateEntry candidate(
        String sleeperId, String butlerId, String position, boolean prior) {
        return new SleeperLiveWaiverComparisonExecutionBundle.CandidateEntry(
            sleeperId, "Candidate " + sleeperId, position, butlerId, prior,
            10, 2, 8, "ADD_ONLY", "TM", "Active", null, position, 2);
    }

    private static SleeperLiveWaiverComparisonExecutionBundle.RosterEntry roster(
        String sleeperId, String butlerId, String position, String slot, boolean prior) {
        return new SleeperLiveWaiverComparisonExecutionBundle.RosterEntry(
            sleeperId, "Roster " + sleeperId, position, slot, butlerId, prior);
    }

    private static SleeperLiveWaiverCandidateRosterComparisonMethodology.MethodologyReport methodology(
        int candidates, int reviewable, int candidatePrior, int candidateMissing,
        int target, int starters, int bench, int reserve, int taxi, int targetPrior, int targetMissing) {
        return new SleeperLiveWaiverCandidateRosterComparisonMethodology.MethodologyReport(
            SleeperLiveWaiverCandidateRosterComparisonMethodology.POLICY_ID,
            "L", "owner", "M", "W", "S", 2026, "in_season", 1, 1,
            candidates, reviewable, candidatePrior, candidateMissing,
            target, starters, bench, reserve, taxi, targetPrior, targetMissing,
            targetMissing == 0 ? List.of() : List.of(
                new SleeperLiveWaiverCandidateRosterComparisonMethodology.ProtectedMissingProduction(
                    "missing", "Missing", "RB", "RESERVE")),
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

    private static PlayerSeasonProduction v1(
        String id, String playerId, String source, LocalDate date,
        int rushingYards, int receptions, int receivingYards) {
        return new PlayerSeasonProduction(
            id, playerId, 2025, 10,
            0, 0, 0,
            rushingYards, 0,
            receptions, receivingYards, 0,
            0, source, date);
    }

    private static PlayerSeasonProduction v2(
        String id, String playerId, String source, LocalDate date,
        int rushingYards, int receptions, int rushingTwoPointConversions) {
        return new PlayerSeasonProduction(
            id, playerId, 2025, 10,
            0, 0, 0,
            rushingYards, 0,
            receptions, 0, 0,
            0, 0, 0, rushingTwoPointConversions, 0, 0, 0,
            2, source, date);
    }
}
