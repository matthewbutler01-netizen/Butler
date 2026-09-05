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
import io.butler.bet.intelligence.LeagueTeamPairSeasonLineupCaptureContrastEvidenceAnalyzer;
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

class ButlerLeagueTeamPairLineupCaptureContrastEvidenceCliTest {
    @TempDir Path tempDir;

    @Test
    void parsesExactCommandShape() {
        var options = ButlerLeagueTeamPairLineupCaptureContrastEvidenceCli.parse(new String[]{
            "league", "team-pair-lineup-capture-contrast-evidence", "l1", "ta", "tb", "2026"});

        assertEquals("l1", options.leagueId());
        assertEquals("ta", options.teamAId());
        assertEquals("tb", options.teamBId());
        assertEquals(2026, options.season());
    }

    @Test
    void rejectsMalformedOrSameTeamArguments() {
        assertThrows(IllegalArgumentException.class,
            () -> ButlerLeagueTeamPairLineupCaptureContrastEvidenceCli.parse(new String[]{
                "league", "team-pair-lineup-capture-contrast-evidence", "l1", "ta", "ta", "2026"}));
        assertThrows(IllegalArgumentException.class,
            () -> ButlerLeagueTeamPairLineupCaptureContrastEvidenceCli.parse(new String[]{
                "league", "team-pair-lineup-capture-contrast-evidence", "l1", "ta", "tb", "bad"}));
    }

    @Test
    void rendersSharedWeekRatesCoverageContrastAndNonAttributionBoundary() throws Exception {
        Database database = fixture();
        var report = new LeagueTeamPairSeasonLineupCaptureContrastEvidenceAnalyzer(database)
            .analyze("l1", "ta", "tb", 2026);

        PrintStream original = System.out;
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try {
            System.setOut(new PrintStream(bytes));
            ButlerLeagueTeamPairLineupCaptureContrastEvidenceCli.print(report);
        } finally {
            System.setOut(original);
        }

        String output = bytes.toString();
        assertTrue(output.contains("Pairwise lineup-capture contrast evidence"));
        assertTrue(output.contains("shared comparable weeks: [1]"));
        assertTrue(output.contains("Team A-only comparable weeks: [2]"));
        assertTrue(output.contains("Team B-only comparable weeks: none"));
        assertTrue(output.contains("independently scoped full-season rates are not subtracted"));
        assertTrue(output.contains("Team A shared evidence [ta]"));
        assertTrue(output.contains("observed weeks: 2"));
        assertTrue(output.contains("individually comparable weeks: 2"));
        assertTrue(output.contains("lineup capture rate: 0.625000"));
        assertTrue(output.contains("lineup capture percentage: 62.50%"));
        assertTrue(output.contains("Team B shared evidence [tb]"));
        assertTrue(output.contains("individually comparable weeks: 1"));
        assertTrue(output.contains("lineup capture rate: 0.700000"));
        assertTrue(output.contains("lineup capture percentage: 70.00%"));
        assertTrue(output.contains("Team A minus Team B rate contrast: -0.075000"));
        assertTrue(output.contains("Team A minus Team B percentage-point contrast: -7.50 percentage points"));
        assertTrue(output.contains("not a manager-efficiency difference, winner, manager grade, rank, tier"));
        assertTrue(output.contains("must not be used to derive a league ranking"));
    }

    private Database fixture() throws Exception {
        Database database = new Database(tempDir.resolve("pair-cli.db"));
        database.initialize();
        new LeagueRepository(database).save(new League("l1", "L1", "League", 2026));
        TeamRepository teams = new TeamRepository(database);
        teams.save(new Team("ta", "1", "l1", "Alpha Team"));
        teams.save(new Team("tb", "2", "l1", "Beta Team"));
        PlayerRepository players = new PlayerRepository(database);
        players.save(new Player("pa1", "a1", "A QB", "QB", "CHI"));
        players.save(new Player("pa2", "a2", "A WR2", "WR", "DET"));
        players.save(new Player("pa3", "a3", "A WR3", "WR", "MIN"));
        players.save(new Player("pb1", "b1", "B QB", "QB", "GB"));
        players.save(new Player("pb2", "b2", "B WR2", "WR", "SEA"));
        players.save(new Player("pb3", "b3", "B WR3", "WR", "LAR"));

        new LeagueConfigurationObservationRepository(database).replace(new LeagueConfigurationObservation(
            "l1", "sleeper", AS_OF, 2026,
            List.of("QB", "WR", "BN"), Map.of("pass_td", 4.0, "rec_td", 6.0)));
        PlayerFantasyPositionObservationRepository eligibility = new PlayerFantasyPositionObservationRepository(database);
        eligibility.replace(new PlayerFantasyPositionObservation("pa1", "sleeper", AS_OF, List.of("QB")));
        eligibility.replace(new PlayerFantasyPositionObservation("pa2", "sleeper", AS_OF, List.of("WR")));
        eligibility.replace(new PlayerFantasyPositionObservation("pa3", "sleeper", AS_OF, List.of("WR")));
        eligibility.replace(new PlayerFantasyPositionObservation("pb1", "sleeper", AS_OF, List.of("QB")));
        eligibility.replace(new PlayerFantasyPositionObservation("pb2", "sleeper", AS_OF, List.of("WR")));
        eligibility.replace(new PlayerFantasyPositionObservation("pb3", "sleeper", AS_OF, List.of("WR")));

        saveWeek(database, 1, List.of("a1", "a2"), List.of("b1", "b2"));
        saveCoverage(database, 1);
        saveProduction(database, 1, "pa1", 1, 0);
        saveProduction(database, 1, "pa2", 0, 1);
        saveProduction(database, 1, "pa3", 0, 2);
        saveProduction(database, 1, "pb1", 2, 0);
        saveProduction(database, 1, "pb2", 0, 1);
        saveProduction(database, 1, "pb3", 0, 2);

        saveWeek(database, 2, List.of("a1", "a2"), List.of("b1", "0"));
        saveCoverage(database, 2);
        saveProduction(database, 2, "pa1", 1, 0);
        saveProduction(database, 2, "pa2", 0, 1);
        saveProduction(database, 2, "pa3", 0, 2);
        saveProduction(database, 2, "pb1", 1, 0);
        saveProduction(database, 2, "pb2", 0, 1);
        saveProduction(database, 2, "pb3", 0, 2);
        return database;
    }

    private static void saveWeek(Database database, int week, List<String> aStarters, List<String> bStarters)
        throws Exception {
        TeamWeekRosterEvidenceRepository rosters = new TeamWeekRosterEvidenceRepository(database);
        rosters.save(TeamWeekRosterEvidence.create(
            "l1", "ta", 2026, week, List.of("a1", "a2", "a3"), aStarters, "sleeper", AS_OF));
        rosters.save(TeamWeekRosterEvidence.create(
            "l1", "tb", 2026, week, List.of("b1", "b2", "b3"), bStarters, "sleeper", AS_OF));
    }

    private static void saveCoverage(Database database, int week) throws Exception {
        List<String> covered = List.of("pa1", "pa2", "pa3", "pb1", "pb2", "pb3");
        new PlayerWeekProductionCoverageRepository(database).replace(new PlayerWeekProductionCoverage(
            2026, week, "nflverse", URI.create("https://example.test/stats_player_week_2026.csv"),
            AS_OF, 100, covered.size(), 0, covered));
    }

    private static void saveProduction(
        Database database, int week, String playerId, int passTd, int recTd) throws Exception {
        new PlayerWeekProductionRepository(database).save(PlayerWeekProduction.create(
            playerId, 2026, week,
            0, passTd, 0,
            0, 0,
            0, 0, recTd,
            0, "nflverse", AS_OF));
    }

    private static final LocalDate AS_OF = LocalDate.of(2026, 9, 5);
}
