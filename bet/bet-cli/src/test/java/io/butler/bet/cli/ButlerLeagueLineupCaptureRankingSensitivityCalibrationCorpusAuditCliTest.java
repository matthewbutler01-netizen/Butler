package io.butler.bet.cli;

import io.butler.bet.data.Database;
import io.butler.bet.data.LeagueConfigurationObservationRepository;
import io.butler.bet.data.LeagueRepository;
import io.butler.bet.data.PlayerFantasyPositionObservationRepository;
import io.butler.bet.data.PlayerRepository;
import io.butler.bet.data.PlayerWeekProductionCoverageRepository;
import io.butler.bet.data.PlayerWeekProductionRepository;
import io.butler.bet.data.TeamRepository;
import io.butler.bet.data.TeamWeekRosterEvidenceRepository;
import io.butler.bet.domain.League;
import io.butler.bet.domain.LeagueConfigurationObservation;
import io.butler.bet.domain.Player;
import io.butler.bet.domain.PlayerFantasyPositionObservation;
import io.butler.bet.domain.PlayerWeekProduction;
import io.butler.bet.domain.PlayerWeekProductionCoverage;
import io.butler.bet.domain.Team;
import io.butler.bet.domain.TeamWeekRosterEvidence;
import io.butler.bet.intelligence.LeagueLineupCaptureRankingSensitivityCalibrationCorpusAuditAnalyzer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.net.URI;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ButlerLeagueLineupCaptureRankingSensitivityCalibrationCorpusAuditCliTest {
    @TempDir Path tempDir;

    @Test
    void parsesExactHistoricalAuditCommand() {
        var options = ButlerLeagueLineupCaptureRankingSensitivityCalibrationCorpusAuditCli.parse(new String[] {
            "league", "lineup-capture-ranking-sensitivity-calibration-corpus-audit", "2024", "2026"
        });

        assertEquals(2024, options.startSeason());
        assertEquals(2026, options.endSeason());
        assertTrue(ButlerLeagueLineupCaptureRankingSensitivityCalibrationCorpusAuditCli.isCommand(new String[] {
            "LEAGUE", "LINEUP-CAPTURE-RANKING-SENSITIVITY-CALIBRATION-CORPUS-AUDIT", "2024", "2026"
        }));
        assertThrows(IllegalArgumentException.class,
            () -> ButlerLeagueLineupCaptureRankingSensitivityCalibrationCorpusAuditCli.parse(new String[] {
                "league", "lineup-capture-ranking-sensitivity-calibration-corpus-audit", "2027", "2026"
            }));
    }

    @Test
    void rendersCorpusBreadthTemporalRowsAndNonCalibrationBoundary() throws Exception {
        Fixture fixture = initializedFixture();
        fixture.saveConfiguration();
        fixture.saveEligibility();
        for (int week = 1; week <= 9; week++) {
            fixture.saveCompleteWeek(week);
            fixture.saveCoverage(week);
            if (week <= 5) fixture.saveBaselineProduction(week);
            else fixture.saveFutureHoldoutProduction(week);
        }
        var report = new LeagueLineupCaptureRankingSensitivityCalibrationCorpusAuditAnalyzer(fixture.database())
            .analyze(2026, 2026);

        String output = capture(() ->
            ButlerLeagueLineupCaptureRankingSensitivityCalibrationCorpusAuditCli.print(report));

        assertTrue(output.contains("Historical lineup-capture rank-sensitivity calibration corpus audit"));
        assertTrue(output.contains("Requested seasons: 2026..2026"));
        assertTrue(output.contains("available temporal cutoffs: 1"));
        assertTrue(output.contains("excluded temporal cutoffs: 7"));
        assertTrue(output.contains("cutoff after week 5 | baseline=[1, 2, 3, 4, 5] | future-holdout=[6, 7, 8, 9] | state=AVAILABLE"));
        assertTrue(output.contains("baseline BF-508 sensitivity class: LOW_SENSITIVITY"));
        assertTrue(output.contains("baseline changed scenarios: 0 of 5"));
        assertTrue(output.contains("baseline rank-change frequency: 0.000000"));
        assertTrue(output.contains("temporal absolute rank displacement: 1"));
        assertTrue(output.contains("exact numeric rank retained: false"));
        assertTrue(output.contains("historical corpus audit, not a calibrated model"));
        assertTrue(output.contains("later holdout rank is not a true or corrected rank"));
        assertTrue(output.contains("fits no qualitative frequency threshold"));
        assertTrue(output.contains("no arbitrary sample-size sufficiency threshold is declared"));
    }

    private Fixture initializedFixture() throws Exception {
        Database database = new Database(tempDir.resolve("audit-cli.db"));
        database.initialize();
        new LeagueRepository(database).save(new League("l1", "L1", "League", 2026));
        TeamRepository teams = new TeamRepository(database);
        teams.save(new Team("ta", "1", "l1", "Alpha Team"));
        teams.save(new Team("tb", "2", "l1", "Beta Team"));

        PlayerRepository players = new PlayerRepository(database);
        for (String prefix : List.of("a", "b")) {
            players.save(new Player("p" + prefix + "1", prefix + "1", prefix + " QB", "QB", "CHI"));
            players.save(new Player("p" + prefix + "2", prefix + "2", prefix + " WR2", "WR", "DET"));
            players.save(new Player("p" + prefix + "3", prefix + "3", prefix + " WR3", "WR", "MIN"));
        }
        return new Fixture(database);
    }

    private static final LocalDate AS_OF = LocalDate.of(2026, 9, 5);
    private static final List<String> COVERED_PLAYERS = List.of("pa1", "pa2", "pa3", "pb1", "pb2", "pb3");

    private record Fixture(Database database) {
        void saveConfiguration() throws Exception {
            new LeagueConfigurationObservationRepository(database).replace(new LeagueConfigurationObservation(
                "l1", "sleeper", AS_OF, 2026, List.of("QB", "WR", "BN"),
                Map.of("pass_td", 4.0, "rec_td", 6.0)));
        }

        void saveEligibility() throws Exception {
            PlayerFantasyPositionObservationRepository repository =
                new PlayerFantasyPositionObservationRepository(database);
            for (String prefix : List.of("a", "b")) {
                repository.replace(new PlayerFantasyPositionObservation(
                    "p" + prefix + "1", "sleeper", AS_OF, List.of("QB")));
                repository.replace(new PlayerFantasyPositionObservation(
                    "p" + prefix + "2", "sleeper", AS_OF, List.of("WR")));
                repository.replace(new PlayerFantasyPositionObservation(
                    "p" + prefix + "3", "sleeper", AS_OF, List.of("WR")));
            }
        }

        void saveCompleteWeek(int week) throws Exception {
            TeamWeekRosterEvidenceRepository rosters = new TeamWeekRosterEvidenceRepository(database);
            rosters.save(TeamWeekRosterEvidence.create(
                "l1", "ta", 2026, week, List.of("a1", "a2", "a3"), List.of("a1", "a2"),
                "sleeper", AS_OF));
            rosters.save(TeamWeekRosterEvidence.create(
                "l1", "tb", 2026, week, List.of("b1", "b2", "b3"), List.of("b1", "b2"),
                "sleeper", AS_OF));
        }

        void saveCoverage(int week) throws Exception {
            new PlayerWeekProductionCoverageRepository(database).replace(new PlayerWeekProductionCoverage(
                2026, week, "nflverse", URI.create("https://example.test/stats_player_week_2026.csv"),
                AS_OF, 100, COVERED_PLAYERS.size(), 0, COVERED_PLAYERS));
        }

        void saveBaselineProduction(int week) throws Exception {
            PlayerWeekProductionRepository production = new PlayerWeekProductionRepository(database);
            save(production, week, "pa1", 1, 0);
            save(production, week, "pa2", 0, 1);
            save(production, week, "pa3", 0, 2);
            save(production, week, "pb1", 1, 0);
            save(production, week, "pb2", 0, 2);
            save(production, week, "pb3", 0, 1);
        }

        void saveFutureHoldoutProduction(int week) throws Exception {
            PlayerWeekProductionRepository production = new PlayerWeekProductionRepository(database);
            save(production, week, "pa1", 1, 0);
            save(production, week, "pa2", 0, 2);
            save(production, week, "pa3", 0, 1);
            save(production, week, "pb1", 1, 0);
            save(production, week, "pb2", 0, 1);
            save(production, week, "pb3", 0, 2);
        }

        private static void save(
            PlayerWeekProductionRepository repository,
            int week,
            String playerId,
            int passingTouchdowns,
            int receivingTouchdowns) throws Exception {
            repository.save(PlayerWeekProduction.create(
                playerId, 2026, week,
                0, passingTouchdowns, 0,
                0, 0,
                0, 0, receivingTouchdowns,
                0, "nflverse", AS_OF));
        }
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
}
