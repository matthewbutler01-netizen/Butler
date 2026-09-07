package io.butler.bet.cli;

import io.butler.bet.data.Database;
import io.butler.bet.data.LeagueConfigurationObservationRepository;
import io.butler.bet.data.LeagueRepository;
import io.butler.bet.data.PlayerFantasyPositionObservationRepository;
import io.butler.bet.data.PlayerRepository;
import io.butler.bet.data.ProviderPlayerWeekPointsEvidenceRepository;
import io.butler.bet.data.TeamRepository;
import io.butler.bet.data.TeamWeekRosterEvidenceRepository;
import io.butler.bet.domain.League;
import io.butler.bet.domain.LeagueConfigurationObservation;
import io.butler.bet.domain.Player;
import io.butler.bet.domain.PlayerFantasyPositionObservation;
import io.butler.bet.domain.ProviderPlayerWeekPointsEvidence;
import io.butler.bet.domain.Team;
import io.butler.bet.domain.TeamWeekRosterEvidence;
import io.butler.bet.intelligence.HistoricalScoringLaneSelector;
import io.butler.bet.intelligence.LeagueSeasonLineupCaptureCommonUniverseEvidenceAnalyzer;
import io.butler.bet.sleeper.SleeperProviderNativeSeasonScoringAudit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.math.BigDecimal;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertTrue;

class ButlerLeagueSeasonLineupCaptureCommonUniverseHistoricalScoringLaneCliTest {
    @TempDir Path tempDir;

    @Test
    void rendersProviderNativeCommonUniverseProvenanceExactRatesAndNeutralBoundary() throws Exception {
        Database database = new Database(tempDir.resolve("provider-common-cli.db"));
        database.initialize();
        new LeagueRepository(database).save(new League("l1", "L1", "League", 2026));
        TeamRepository teams = new TeamRepository(database);
        teams.save(new Team("tb", "2", "l1", "Beta Team"));
        teams.save(new Team("ta", "1", "l1", "Alpha Team"));

        PlayerRepository players = new PlayerRepository(database);
        players.save(new Player("pa1", "a1", "A Quarterback", "QB", "CHI"));
        players.save(new Player("pa2", "a2", "A Receiver Two", "WR", "DET"));
        players.save(new Player("pa3", "a3", "A Receiver Three", "WR", "MIN"));
        players.save(new Player("pb1", "b1", "B Quarterback", "QB", "GB"));
        players.save(new Player("pb2", "b2", "B Receiver Two", "WR", "SEA"));
        players.save(new Player("pb3", "b3", "B Receiver Three", "WR", "LAR"));

        new LeagueConfigurationObservationRepository(database).replace(new LeagueConfigurationObservation(
            "l1", "sleeper", AS_OF, 2026,
            List.of("QB", "WR", "BN"), Map.of("pass_td", 4.0, "rec_td", 6.0)));
        PlayerFantasyPositionObservationRepository eligibility =
            new PlayerFantasyPositionObservationRepository(database);
        eligibility.replace(new PlayerFantasyPositionObservation("pa1", "sleeper", AS_OF, List.of("QB")));
        eligibility.replace(new PlayerFantasyPositionObservation("pa2", "sleeper", AS_OF, List.of("WR")));
        eligibility.replace(new PlayerFantasyPositionObservation("pa3", "sleeper", AS_OF, List.of("WR")));
        eligibility.replace(new PlayerFantasyPositionObservation("pb1", "sleeper", AS_OF, List.of("QB")));
        eligibility.replace(new PlayerFantasyPositionObservation("pb2", "sleeper", AS_OF, List.of("WR")));
        eligibility.replace(new PlayerFantasyPositionObservation("pb3", "sleeper", AS_OF, List.of("WR")));

        TeamWeekRosterEvidenceRepository rosters = new TeamWeekRosterEvidenceRepository(database);
        rosters.save(TeamWeekRosterEvidence.create(
            "l1", "ta", 2026, 1, List.of("a1", "a2", "a3"), List.of("a1", "a2"), "sleeper", AS_OF));
        rosters.save(TeamWeekRosterEvidence.create(
            "l1", "tb", 2026, 1, List.of("b1", "b2", "b3"), List.of("b1", "b2"), "sleeper", AS_OF));

        Map<String, BigDecimal> points = Map.of(
            "a1", new BigDecimal("4.0"), "a2", new BigDecimal("6.0"), "a3", new BigDecimal("12.0"),
            "b1", new BigDecimal("8.0"), "b2", new BigDecimal("6.0"), "b3", new BigDecimal("12.0"));
        List<ProviderPlayerWeekPointsEvidence> rows = new ArrayList<>();
        for (var entry : points.entrySet().stream().sorted(Map.Entry.comparingByKey()).toList()) {
            boolean alpha = entry.getKey().startsWith("a");
            rows.add(ProviderPlayerWeekPointsEvidence.create(
                "l1", alpha ? "ta" : "tb", alpha ? "1" : "2", "provider-l1", 2026, 1,
                entry.getKey(), entry.getValue(), "sleeper",
                SleeperProviderNativeSeasonScoringAudit.EXPECTED_SOURCE_SURFACE, PROVIDER_AS_OF));
        }
        new ProviderPlayerWeekPointsEvidenceRepository(database).replaceSeasonSnapshot(
            "l1", 2026, "sleeper", PROVIDER_AS_OF, rows);

        var report = new LeagueSeasonLineupCaptureCommonUniverseEvidenceAnalyzer(database).analyze("l1", 2026);
        String output = capture(() -> ButlerLeagueSeasonLineupCaptureCommonUniverseEvidenceCli.print(report));

        assertTrue(output.contains("Scoring lane selector: " + HistoricalScoringLaneSelector.POLICY_ID));
        assertTrue(output.contains("Scoring lane: SLEEPER_PROVIDER_NATIVE"));
        assertTrue(output.contains("Scoring policy: " + HistoricalScoringLaneSelector.PROVIDER_NATIVE_SCORING_POLICY_ID));
        assertTrue(output.contains("Common comparable weeks: [1]"));
        assertTrue(output.contains("Alpha Team [ta] | 1 | 1 | none | 1 | 10 | 16 | 6 | 0.625000 (62.50%)"));
        assertTrue(output.contains("Beta Team [tb] | 1 | 1 | none | 1 | 14 | 20 | 6 | 0.700000 (70.00%)"));
        assertTrue(output.indexOf("Alpha Team [ta]") < output.indexOf("Beta Team [tb]"));
        assertTrue(output.contains("provider points as-of: " + PROVIDER_AS_OF));
        assertTrue(output.contains("provider points source surface: "
            + SleeperProviderNativeSeasonScoringAudit.EXPECTED_SOURCE_SURFACE));
        assertTrue(output.contains("provider league id: provider-l1"));
        assertTrue(output.contains("under one governed league-season historical scoring lane"));
        assertTrue(output.contains("computes no rank, tier, percentile"));
        assertTrue(output.contains("not manager efficiency"));
    }

    private static String capture(Runnable runnable) {
        PrintStream previous = System.out;
        var bytes = new ByteArrayOutputStream();
        try {
            System.setOut(new PrintStream(bytes));
            runnable.run();
            return bytes.toString();
        } finally {
            System.setOut(previous);
        }
    }

    private static final LocalDate AS_OF = LocalDate.of(2026, 9, 5);
    private static final LocalDate PROVIDER_AS_OF = LocalDate.of(2026, 9, 6);
}
