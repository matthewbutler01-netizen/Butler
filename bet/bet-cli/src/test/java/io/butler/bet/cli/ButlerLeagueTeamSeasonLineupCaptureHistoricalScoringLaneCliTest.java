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
import io.butler.bet.intelligence.LeagueTeamSeasonLineupCaptureEvidenceAnalyzer;
import io.butler.bet.sleeper.SleeperProviderNativeSeasonScoringAudit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.math.BigDecimal;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertTrue;

class ButlerLeagueTeamSeasonLineupCaptureHistoricalScoringLaneCliTest {
    @TempDir Path tempDir;

    @Test
    void rendersProviderNativeLaneProvenanceAndExactCaptureRate() throws Exception {
        Database database = new Database(tempDir.resolve("provider-capture-cli.db"));
        database.initialize();
        new LeagueRepository(database).save(new League("l1", "L1", "League", 2026));
        new TeamRepository(database).save(new Team("t1", "1", "l1", "Team One"));
        PlayerRepository players = new PlayerRepository(database);
        players.save(new Player("p1", "s1", "Quarterback", "QB", "CHI"));
        players.save(new Player("p2", "s2", "Receiver Two", "WR", "DET"));
        players.save(new Player("p3", "s3", "Receiver Three", "WR", "MIN"));
        new LeagueConfigurationObservationRepository(database).replace(new LeagueConfigurationObservation(
            "l1", "sleeper", AS_OF, 2026,
            List.of("QB", "WR", "BN"), Map.of("pass_td", 4.0, "rec_td", 6.0)));
        PlayerFantasyPositionObservationRepository eligibility =
            new PlayerFantasyPositionObservationRepository(database);
        eligibility.replace(new PlayerFantasyPositionObservation("p1", "sleeper", AS_OF, List.of("QB")));
        eligibility.replace(new PlayerFantasyPositionObservation("p2", "sleeper", AS_OF, List.of("WR")));
        eligibility.replace(new PlayerFantasyPositionObservation("p3", "sleeper", AS_OF, List.of("WR")));
        new TeamWeekRosterEvidenceRepository(database).save(TeamWeekRosterEvidence.create(
            "l1", "t1", 2026, 1, List.of("s1", "s2", "s3"), List.of("s1", "s2"), "sleeper", AS_OF));
        List<ProviderPlayerWeekPointsEvidence> rows = List.of(
            providerPoints("s1", new BigDecimal("4.0")),
            providerPoints("s2", new BigDecimal("6.0")),
            providerPoints("s3", new BigDecimal("12.0")));
        new ProviderPlayerWeekPointsEvidenceRepository(database).replaceSeasonSnapshot(
            "l1", 2026, "sleeper", PROVIDER_AS_OF, rows);

        var report = new LeagueTeamSeasonLineupCaptureEvidenceAnalyzer(database).analyze("l1", "t1", 2026);
        String output = capture(() -> ButlerLeagueTeamSeasonLineupCaptureEvidenceCli.print(report));

        assertTrue(output.contains("Scoring lane selector: " + HistoricalScoringLaneSelector.POLICY_ID));
        assertTrue(output.contains("Scoring lane: SLEEPER_PROVIDER_NATIVE"));
        assertTrue(output.contains("Scoring policy: " + HistoricalScoringLaneSelector.PROVIDER_NATIVE_SCORING_POLICY_ID));
        assertTrue(output.contains("provider points as-of: " + PROVIDER_AS_OF));
        assertTrue(output.contains("provider points source surface: "
            + SleeperProviderNativeSeasonScoringAudit.EXPECTED_SOURCE_SURFACE));
        assertTrue(output.contains("provider league id: provider-l1"));
        assertTrue(output.contains("started points: 10"));
        assertTrue(output.contains("retrospective potential points: 16"));
        assertTrue(output.contains("Lineup capture rate: 0.625000"));
        assertTrue(output.contains("Lineup capture percentage: 62.50%"));
        assertTrue(output.contains("under one governed league-season historical scoring lane"));
    }

    private static ProviderPlayerWeekPointsEvidence providerPoints(String playerId, BigDecimal points) {
        return ProviderPlayerWeekPointsEvidence.create(
            "l1", "t1", "1", "provider-l1", 2026, 1, playerId, points,
            "sleeper", SleeperProviderNativeSeasonScoringAudit.EXPECTED_SOURCE_SURFACE, PROVIDER_AS_OF);
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
