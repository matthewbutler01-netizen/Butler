package io.butler.bet.intelligence;

import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LeagueAgeOutlookEvidenceAnalyzerTest {
    @Test
    void attachesMatchingOutlookToValidatedPublishedMetric() {
        int age = 38;
        var metric = AgingModelSampleAuditAnalyzer.Metric.RECEIVING_YARDS_PER_GAME;
        var cell = cell(metric, age);
        var lookup = new AgingModelPublishedCellLookup.LookupResult(
            AgingModelPublishedCellLookup.Status.PUBLISHED, AgingModelSupportPolicy.POLICY_ID,
            AgingModelSupportPolicy.MINIMUM_DISTINCT_SEASON_TRANSITIONS, cell);
        var metricEvidence = new AgingModelPositionAgeEvidenceAnalyzer.MetricEvidence(metric, lookup);
        var validatedCell = validated(cell);
        var validatedMetric = new LeagueValidatedAgingModelEvidenceAnalyzer.ValidatedMetricEvidence(metricEvidence, validatedCell);
        var basePlayer = new LeagueAgingModelEvidenceAnalyzer.PlayerAgingModelEvidence(
            "p1", "Player One", "WR", "STARTER", age, LeagueAgingModelEvidenceAnalyzer.Status.FULL,
            new AgingModelPositionAgeEvidenceAnalyzer.PositionAgeEvidenceReport(
                "WR", age, AgingModelSupportPolicy.POLICY_ID,
                AgingModelSupportPolicy.MINIMUM_DISTINCT_SEASON_TRANSITIONS,
                "nflverse-players", "nflverse", List.of(metricEvidence)));
        var validatedPlayer = new LeagueValidatedAgingModelEvidenceAnalyzer.ValidatedPlayerEvidence(
            basePlayer, List.of(validatedMetric));
        var league = new LeagueValidatedAgingModelEvidenceAnalyzer.ValidatedLeagueReport(
            "league-1", 2026, LocalDate.of(2026, 9, 1), "sleeper",
            AgingModelSupportPolicy.POLICY_ID, AgingModelSupportPolicy.MINIMUM_DISTINCT_SEASON_TRANSITIONS,
            "nflverse-players", "nflverse", 1, 1,
            List.of(new LeagueValidatedAgingModelEvidenceAnalyzer.ValidatedTeamEvidence(
                "t1", "Team One", List.of(validatedPlayer))));
        var outlookCell = new AgingModelAgeOutlookAnalyzer.OutlookCell(
            validatedCell, AgingModelAgeOutlookPolicy.Direction.HIGHER_IS_FAVORABLE,
            AgingModelAgeOutlookPolicy.MetricOutlook.UNFAVORABLE);
        var outlook = new AgingModelAgeOutlookAnalyzer.AgeOutlookReport(
            "nflverse-players", "nflverse", AgingModelSupportPolicy.POLICY_ID,
            AgingModelAgeOutlookPolicy.POLICY_ID, 1, 1, List.of(outlookCell));

        var result = LeagueAgeOutlookEvidenceAnalyzer.compose(league, outlook);

        assertEquals(AgingModelAgeOutlookPolicy.POLICY_ID, result.outlookPolicyId());
        assertTrue(result.allPublishedModelCellsHaveOutlook());
        var player = result.teams().getFirst().players().getFirst();
        assertEquals(1, player.outlookAvailableMetrics());
        assertEquals(1, player.unfavorableMetrics());
        assertEquals(AgingModelAgeOutlookPolicy.MetricOutlook.UNFAVORABLE,
            player.metrics().getFirst().label());
    }

    @Test
    void failsClosedWhenValidatedPublishedMetricHasNoOutlookCell() {
        int age = 38;
        var metric = AgingModelSampleAuditAnalyzer.Metric.RECEIVING_YARDS_PER_GAME;
        var cell = cell(metric, age);
        var lookup = new AgingModelPublishedCellLookup.LookupResult(
            AgingModelPublishedCellLookup.Status.PUBLISHED, AgingModelSupportPolicy.POLICY_ID,
            AgingModelSupportPolicy.MINIMUM_DISTINCT_SEASON_TRANSITIONS, cell);
        var metricEvidence = new AgingModelPositionAgeEvidenceAnalyzer.MetricEvidence(metric, lookup);
        var validatedCell = validated(cell);
        var basePlayer = new LeagueAgingModelEvidenceAnalyzer.PlayerAgingModelEvidence(
            "p1", "Player One", "WR", "STARTER", age, LeagueAgingModelEvidenceAnalyzer.Status.FULL,
            new AgingModelPositionAgeEvidenceAnalyzer.PositionAgeEvidenceReport(
                "WR", age, AgingModelSupportPolicy.POLICY_ID,
                AgingModelSupportPolicy.MINIMUM_DISTINCT_SEASON_TRANSITIONS,
                "nflverse-players", "nflverse", List.of(metricEvidence)));
        var league = new LeagueValidatedAgingModelEvidenceAnalyzer.ValidatedLeagueReport(
            "league-1", 2026, LocalDate.of(2026, 9, 1), "sleeper",
            AgingModelSupportPolicy.POLICY_ID, AgingModelSupportPolicy.MINIMUM_DISTINCT_SEASON_TRANSITIONS,
            "nflverse-players", "nflverse", 1, 1,
            List.of(new LeagueValidatedAgingModelEvidenceAnalyzer.ValidatedTeamEvidence(
                "t1", "Team One", List.of(new LeagueValidatedAgingModelEvidenceAnalyzer.ValidatedPlayerEvidence(
                    basePlayer, List.of(new LeagueValidatedAgingModelEvidenceAnalyzer.ValidatedMetricEvidence(metricEvidence, validatedCell)))))));
        var emptyOutlook = new AgingModelAgeOutlookAnalyzer.AgeOutlookReport(
            "nflverse-players", "nflverse", AgingModelSupportPolicy.POLICY_ID,
            AgingModelAgeOutlookPolicy.POLICY_ID, 1, 1, List.of());

        assertThrows(IllegalStateException.class,
            () -> LeagueAgeOutlookEvidenceAnalyzer.compose(league, emptyOutlook));
    }

    @Test
    void rejectsSupportPolicySourceOrPublishedCountDrift() {
        var league = new LeagueValidatedAgingModelEvidenceAnalyzer.ValidatedLeagueReport(
            "league-1", 2026, LocalDate.of(2026, 9, 1), "sleeper",
            AgingModelSupportPolicy.POLICY_ID, AgingModelSupportPolicy.MINIMUM_DISTINCT_SEASON_TRANSITIONS,
            "nflverse-players", "nflverse", 411, 411, List.of());

        assertThrows(IllegalStateException.class, () -> LeagueAgeOutlookEvidenceAnalyzer.compose(league,
            new AgingModelAgeOutlookAnalyzer.AgeOutlookReport(
                "nflverse-players", "nflverse", "other-policy", AgingModelAgeOutlookPolicy.POLICY_ID,
                411, 411, List.of())));
        assertThrows(IllegalStateException.class, () -> LeagueAgeOutlookEvidenceAnalyzer.compose(league,
            new AgingModelAgeOutlookAnalyzer.AgeOutlookReport(
                "other-source", "nflverse", AgingModelSupportPolicy.POLICY_ID, AgingModelAgeOutlookPolicy.POLICY_ID,
                411, 411, List.of())));
        assertThrows(IllegalStateException.class, () -> LeagueAgeOutlookEvidenceAnalyzer.compose(league,
            new AgingModelAgeOutlookAnalyzer.AgeOutlookReport(
                "nflverse-players", "nflverse", AgingModelSupportPolicy.POLICY_ID, AgingModelAgeOutlookPolicy.POLICY_ID,
                410, 410, List.of())));
    }

    private static AgingModelLocalSmootherAnalyzer.SmoothedCell cell(
        AgingModelSampleAuditAnalyzer.Metric metric, int age) {
        return new AgingModelLocalSmootherAnalyzer.SmoothedCell(
            "WR", metric, age, 10, 30, 25, 8, List.of(age - 1, age, age + 1),
            -2.0, -1.0, -0.2);
    }

    private static AgingModelPublicationValidationAnalyzer.ValidatedCell validated(
        AgingModelLocalSmootherAnalyzer.SmoothedCell cell) {
        var span = new AgingModelPublicationValidationAnalyzer.TrainingSpan(2005, 2025, 30);
        var holdout = new AgingModelTemporalHoldoutAnalyzer.DimensionDiagnostic(
            cell.position(), cell.metric(), 100, 0.0, 1.0, 0.8, 1.2);
        var stability = new AgingModelNormalizedStabilityAnalyzer.NormalizedCell(
            cell.position(), cell.metric(), cell.age(), 30, 8, 8, 0, cell.medianDelta(),
            0.05, 0.08, 0.10, 1.0, 0.05, 0.08, 0.10, 2012, 2013);
        return new AgingModelPublicationValidationAnalyzer.ValidatedCell(cell, span, holdout, stability, true);
    }
}
