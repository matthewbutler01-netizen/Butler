package io.butler.bet.cli;

import io.butler.bet.intelligence.CoveredProductionScoringPolicy;
import io.butler.bet.intelligence.HistoricalScoringLaneSelector;
import io.butler.bet.intelligence.LeagueTeamWeekPotentialLineupAnalyzer;
import io.butler.bet.intelligence.LeagueTeamWeekPotentialLineupCoverageAnalyzer;
import io.butler.bet.intelligence.LeagueTeamWeekStartedLineupEvidenceAnalyzer;
import io.butler.bet.intelligence.LineupSlotEligibilityPolicy;
import io.butler.bet.sleeper.SleeperProviderNativeSeasonScoringAudit;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.math.BigDecimal;
import java.net.URI;
import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ButlerLeagueTeamWeekStartedLineupEvidenceCliTest {
    private static final LocalDate AS_OF = LocalDate.of(2026, 9, 5);
    private static final LocalDate PROVIDER_AS_OF = LocalDate.of(2026, 9, 6);

    @Test
    void parsesExactCommandShape() {
        var args = new String[]{
            "league", "team-week-started-lineup-evidence", "l1", "t1", "2026", "3"};

        var options = ButlerLeagueTeamWeekStartedLineupEvidenceCli.parse(args);

        assertEquals("l1", options.leagueId());
        assertEquals("t1", options.teamId());
        assertEquals(2026, options.season());
        assertEquals(3, options.week());
    }

    @Test
    void rejectsMalformedArguments() {
        assertThrows(IllegalArgumentException.class,
            () -> ButlerLeagueTeamWeekStartedLineupEvidenceCli.parse(new String[]{
                "league", "team-week-started-lineup-evidence", "l1", "t1", "bad", "3"}));
        assertThrows(IllegalArgumentException.class,
            () -> ButlerLeagueTeamWeekStartedLineupEvidenceCli.parse(new String[]{
                "league", "team-week-started-lineup-evidence", "l1", "t1", "2026", "0"}));
        assertThrows(IllegalArgumentException.class,
            () -> ButlerLeagueTeamWeekStartedLineupEvidenceCli.parse(new String[]{
                "league", "team-week-started-lineup-evidence", "l1", "t1", "2026"}));
    }

    @Test
    void rendersEmptyStarterDistinctFromIdentityCoveredZeroAndStatesBoundary() {
        var zeroScore = new LeagueTeamWeekPotentialLineupAnalyzer.PlayerScoreEvidence(
            "s1",
            "p1",
            AS_OF,
            List.of("QB"),
            HistoricalScoringLaneSelector.Lane.NFLVERSE_EXACT,
            LeagueTeamWeekPotentialLineupCoverageAnalyzer.ProductionState.IDENTITY_COVERED_ZERO,
            null,
            AS_OF,
            null,
            null,
            BigDecimal.ZERO);
        var report = new LeagueTeamWeekStartedLineupEvidenceAnalyzer.StartedLineupReport(
            LeagueTeamWeekStartedLineupEvidenceAnalyzer.POLICY_ID,
            LeagueTeamWeekStartedLineupEvidenceAnalyzer.METRIC_SCOPE,
            LeagueTeamWeekPotentialLineupCoverageAnalyzer.POLICY_ID,
            CoveredProductionScoringPolicy.POLICY_ID,
            LineupSlotEligibilityPolicy.POLICY_ID,
            "l1",
            "t1",
            2026,
            3,
            AS_OF,
            AS_OF,
            AS_OF,
            URI.create("https://example.test/week.csv"),
            List.of(
                LeagueTeamWeekStartedLineupEvidenceAnalyzer.StartedSlotEvidence.filled(0, "QB", zeroScore),
                LeagueTeamWeekStartedLineupEvidenceAnalyzer.StartedSlotEvidence.empty(1, "WR")),
            1,
            2,
            false,
            BigDecimal.ZERO);

        String output = render(report);

        assertTrue(output.contains("league-season historical scoring lane"));
        assertTrue(output.contains("scoring lane selector: " + HistoricalScoringLaneSelector.POLICY_ID));
        assertTrue(output.contains("scoring lane: NFLVERSE_EXACT"));
        assertTrue(output.contains("production coverage as-of: " + AS_OF));
        assertTrue(output.contains("#0 QB -> Sleeper s1 / Butler p1 | 0"));
        assertTrue(output.contains("production state: IDENTITY_COVERED_ZERO"));
        assertTrue(output.contains("production id: none (identity-covered zero)"));
        assertTrue(output.contains("#1 WR -> EMPTY (Sleeper starter sentinel 0; no player score assigned)"));
        assertTrue(output.contains("Filled starter slots: 1/2"));
        assertTrue(output.contains("Complete observed starting lineup: false"));
        assertTrue(output.contains("Total started points: 0"));
        assertFalse(output.contains("not provider-reported matchup points"));
        assertTrue(output.contains("no potential-vs-started comparison"));
        assertTrue(output.contains("manager-efficiency score, rank, tier, recommendation, or intent inference"));
    }

    @Test
    void rendersProviderNativeProvenanceWithoutMislabelingItAsProduction() {
        var providerScore = new LeagueTeamWeekPotentialLineupAnalyzer.PlayerScoreEvidence(
            "s1",
            "p1",
            AS_OF,
            List.of("QB"),
            HistoricalScoringLaneSelector.Lane.SLEEPER_PROVIDER_NATIVE,
            LeagueTeamWeekPotentialLineupCoverageAnalyzer.ProductionState.NOT_EVALUATED,
            null,
            null,
            "provider-evidence-1",
            HistoricalScoringLaneSelector.PROVIDER_NATIVE_SCORING_POLICY_ID,
            new BigDecimal("12.5"));
        var report = new LeagueTeamWeekStartedLineupEvidenceAnalyzer.StartedLineupReport(
            LeagueTeamWeekStartedLineupEvidenceAnalyzer.POLICY_ID,
            LeagueTeamWeekStartedLineupEvidenceAnalyzer.METRIC_SCOPE,
            LeagueTeamWeekPotentialLineupCoverageAnalyzer.POLICY_ID,
            HistoricalScoringLaneSelector.POLICY_ID,
            HistoricalScoringLaneSelector.Lane.SLEEPER_PROVIDER_NATIVE,
            HistoricalScoringLaneSelector.PROVIDER_NATIVE_SCORING_POLICY_ID,
            LineupSlotEligibilityPolicy.POLICY_ID,
            "l1",
            "t1",
            2026,
            3,
            AS_OF,
            AS_OF,
            null,
            null,
            PROVIDER_AS_OF,
            SleeperProviderNativeSeasonScoringAudit.EXPECTED_SOURCE_SURFACE,
            "provider-l1",
            List.of(LeagueTeamWeekStartedLineupEvidenceAnalyzer.StartedSlotEvidence.filled(
                0, "QB", providerScore)),
            1,
            1,
            true,
            new BigDecimal("12.5"));

        String output = render(report);

        assertTrue(output.contains("scoring lane: SLEEPER_PROVIDER_NATIVE"));
        assertTrue(output.contains("provider points as-of: " + PROVIDER_AS_OF));
        assertTrue(output.contains("provider points source surface: "
            + SleeperProviderNativeSeasonScoringAudit.EXPECTED_SOURCE_SURFACE));
        assertTrue(output.contains("provider league id: provider-l1"));
        assertTrue(output.contains("provider points evidence id: provider-evidence-1"));
        assertTrue(output.contains("scoring policy: "
            + HistoricalScoringLaneSelector.PROVIDER_NATIVE_SCORING_POLICY_ID));
        assertTrue(output.contains("Total started points: 12.5"));
        assertFalse(output.contains("production coverage as-of"));
        assertFalse(output.contains("identity-covered zero"));
    }

    private static String render(
        LeagueTeamWeekStartedLineupEvidenceAnalyzer.StartedLineupReport report) {
        PrintStream original = System.out;
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try {
            System.setOut(new PrintStream(bytes));
            ButlerLeagueTeamWeekStartedLineupEvidenceCli.print(report);
        } finally {
            System.setOut(original);
        }
        return bytes.toString();
    }
}
