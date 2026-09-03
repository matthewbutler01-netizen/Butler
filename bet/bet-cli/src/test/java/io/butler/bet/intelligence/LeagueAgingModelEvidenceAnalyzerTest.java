package io.butler.bet.intelligence;

import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class LeagueAgingModelEvidenceAnalyzerTest {
    @Test
    void usesExactSeptemberFirstSeasonAgeAndFailsClosedForUnsupportedEvidence() {
        var players = List.of(
            new LeaguePlayerProfileCoverageAnalyzer.PlayerEvidence(
                "qb1", "Quarterback", "QB", "starter", LocalDate.of(2000, 9, 1), null, 5,
                "canonical-birth-date", LocalDate.of(2026, 8, 1), false),
            new LeaguePlayerProfileCoverageAnalyzer.PlayerEvidence(
                "wr1", "Receiver", "WR", "starter", LocalDate.of(1986, 8, 31), null, 15,
                "canonical-birth-date", LocalDate.of(2026, 8, 1), false),
            new LeaguePlayerProfileCoverageAnalyzer.PlayerEvidence(
                "rb1", "Reported Age Only", "RB", "bench", null, 26, 3,
                "sleeper", LocalDate.of(2026, 8, 1), false),
            new LeaguePlayerProfileCoverageAnalyzer.PlayerEvidence(
                "k1", "Kicker", "K", "starter", LocalDate.of(1995, 1, 1), null, 8,
                "canonical-birth-date", LocalDate.of(2026, 8, 1), false));

        var counts = new LeaguePlayerProfileCoverageAnalyzer.Counts(4, 4, 3, 1, 4, 0, 0, 0);
        var team = new LeaguePlayerProfileCoverageAnalyzer.TeamCoverage(
            "t1", "Team One", counts, Map.of(), players);
        var profileReport = new LeaguePlayerProfileCoverageAnalyzer.CoverageReport(
            "league-1", "sleeper", null, List.of(team));

        List<AgingModelLocalSmootherAnalyzer.SmoothedCell> cells = new ArrayList<>();
        addPositionCells(cells, "QB", 25, 5);
        addPositionCells(cells, "WR", 39, 4);
        var smootherReport = new AgingModelLocalSmootherAnalyzer.LocalSmootherReport(
            "nflverse-players", "nflverse", cells);

        var report = LeagueAgingModelEvidenceAnalyzer.compose(profileReport, smootherReport, 2025);
        assertEquals(LocalDate.of(2025, 9, 1), report.modelAgeAsOf());
        assertEquals(4, report.totalPlayers());
        assertEquals(1, report.fullPlayers());
        assertEquals(1, report.belowSupportPlayers());
        assertEquals(1, report.exactAgeUnavailablePlayers());
        assertEquals(1, report.unsupportedPositionPlayers());

        var resultPlayers = report.teams().getFirst().players();
        var qb = resultPlayers.stream().filter(player -> player.playerId().equals("qb1")).findFirst().orElseThrow();
        assertEquals(25, qb.modelAge());
        assertEquals(LeagueAgingModelEvidenceAnalyzer.Status.FULL, qb.status());
        assertEquals(5, qb.evidence().publishedMetrics());

        var wr = resultPlayers.stream().filter(player -> player.playerId().equals("wr1")).findFirst().orElseThrow();
        assertEquals(39, wr.modelAge());
        assertEquals(LeagueAgingModelEvidenceAnalyzer.Status.BELOW_SUPPORT, wr.status());
        assertEquals(0, wr.evidence().publishedMetrics());
        assertEquals(6, wr.evidence().belowSupportMetrics());

        var rb = resultPlayers.stream().filter(player -> player.playerId().equals("rb1")).findFirst().orElseThrow();
        assertEquals(LeagueAgingModelEvidenceAnalyzer.Status.EXACT_AGE_UNAVAILABLE, rb.status());
        assertNull(rb.modelAge());
        assertNull(rb.evidence());

        var kicker = resultPlayers.stream().filter(player -> player.playerId().equals("k1")).findFirst().orElseThrow();
        assertEquals(LeagueAgingModelEvidenceAnalyzer.Status.UNSUPPORTED_POSITION, kicker.status());
        assertNull(kicker.modelAge());
        assertNull(kicker.evidence());
    }

    private static void addPositionCells(List<AgingModelLocalSmootherAnalyzer.SmoothedCell> cells,
                                         String position, int age, int transitions) {
        List<AgingModelSampleAuditAnalyzer.Metric> metrics = switch (position) {
            case "QB" -> List.of(
                AgingModelSampleAuditAnalyzer.Metric.PASSING_YARDS_PER_GAME,
                AgingModelSampleAuditAnalyzer.Metric.PASSING_TOUCHDOWNS_PER_GAME,
                AgingModelSampleAuditAnalyzer.Metric.INTERCEPTIONS_PER_GAME,
                AgingModelSampleAuditAnalyzer.Metric.RUSHING_YARDS_PER_GAME,
                AgingModelSampleAuditAnalyzer.Metric.RUSHING_TOUCHDOWNS_PER_GAME);
            case "WR" -> List.of(
                AgingModelSampleAuditAnalyzer.Metric.RECEPTIONS_PER_GAME,
                AgingModelSampleAuditAnalyzer.Metric.RECEIVING_YARDS_PER_GAME,
                AgingModelSampleAuditAnalyzer.Metric.RECEIVING_TOUCHDOWNS_PER_GAME,
                AgingModelSampleAuditAnalyzer.Metric.RUSHING_YARDS_PER_GAME,
                AgingModelSampleAuditAnalyzer.Metric.RUSHING_TOUCHDOWNS_PER_GAME,
                AgingModelSampleAuditAnalyzer.Metric.FUMBLES_LOST_PER_GAME);
            default -> throw new IllegalArgumentException("unsupported test position");
        };
        for (var metric : metrics) {
            cells.add(new AgingModelLocalSmootherAnalyzer.SmoothedCell(
                position, metric, age, 10, 20, 12, transitions, List.of(age - 1, age, age + 1),
                -0.10, 0.0, 0.10));
        }
    }
}
