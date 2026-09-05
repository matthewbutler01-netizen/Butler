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
import io.butler.bet.intelligence.LeagueTeamSeasonLineupPointsGapEvidenceAnalyzer;
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

class ButlerLeagueTeamSeasonLineupPointsGapEvidenceCliTest {
    @TempDir Path tempDir;

    @Test
    void parsesExactCommandShape() {
        var options = ButlerLeagueTeamSeasonLineupPointsGapEvidenceCli.parse(new String[]{
            "league", "team-season-lineup-points-gap-evidence", "l1", "t1", "2026"});

        assertEquals("l1", options.leagueId());
        assertEquals("t1", options.teamId());
        assertEquals(2026, options.season());
    }

    @Test
    void rejectsMalformedArguments() {
        assertThrows(IllegalArgumentException.class,
            () -> ButlerLeagueTeamSeasonLineupPointsGapEvidenceCli.parse(new String[]{
                "league", "team-season-lineup-points-gap-evidence", "l1", "t1", "bad"}));
        assertThrows(IllegalArgumentException.class,
            () -> ButlerLeagueTeamSeasonLineupPointsGapEvidenceCli.parse(new String[]{
                "league", "team-season-lineup-points-gap-evidence", "l1", "t1"}));
    }

    @Test
    void rendersAllObservedWeekStatesExactDenominatorTotalsAndNonAttributionBoundary() throws Exception {
        Fixture fixture = initializedFixture();
        fixture.saveConfiguration();
        fixture.saveEligibility();

        fixture.saveRoster(1, List.of("s1", "s2", "s3"), List.of("s1", "s2"));
        fixture.saveCoverage(1, List.of("p1", "p2", "p3"));
        fixture.saveProduction("p1", 1, 1, 0);
        fixture.saveProduction("p2", 1, 0, 1);
        fixture.saveProduction("p3", 1, 0, 2);

        fixture.saveRoster(2, List.of("s1"), List.of("s1", "0"));
        fixture.saveCoverage(2, List.of("p1"));
        fixture.saveProduction("p1", 2, 1, 0);

        fixture.saveRoster(3, List.of("s1", "s2"), List.of("s1", "0"));
        fixture.saveCoverage(3, List.of("p1", "p2"));
        fixture.saveProduction("p1", 3, 1, 0);
        fixture.saveProduction("p2", 3, 0, 1);

        fixture.saveRoster(4, List.of("s1", "s2"), List.of("s1", "s2"));

        var report = fixture.analyzer().analyze("l1", "t1", 2026);

        PrintStream original = System.out;
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try {
            System.setOut(new PrintStream(bytes));
            ButlerLeagueTeamSeasonLineupPointsGapEvidenceCli.print(report);
        } finally {
            System.setOut(original);
        }

        String output = bytes.toString();
        assertTrue(output.contains("Week 1 | COMPARABLE_COMPLETE"));
        assertTrue(output.contains("recalculated started points: 10"));
        assertTrue(output.contains("retrospective potential points: 16"));
        assertTrue(output.contains("potential-minus-started points gap: 6"));
        assertTrue(output.contains("Week 2 | POTENTIAL_INCOMPLETE"));
        assertTrue(output.contains("excluded because potential lineup is incomplete"));
        assertTrue(output.contains("Week 3 | STARTED_INCOMPLETE"));
        assertTrue(output.contains("excluded because observed started lineup is incomplete"));
        assertTrue(output.contains("Week 4 | BLOCKED"));
        assertTrue(output.contains("No persisted nflverse week production coverage"));
        assertTrue(output.contains("observed weeks: 4"));
        assertTrue(output.contains("comparable complete weeks: 1"));
        assertTrue(output.contains("potential-incomplete weeks: 1"));
        assertTrue(output.contains("started-incomplete weeks: 1"));
        assertTrue(output.contains("blocked weeks: 1"));
        assertTrue(output.contains("comparable total started points: 10"));
        assertTrue(output.contains("comparable total potential points: 16"));
        assertTrue(output.contains("comparable total potential-minus-started gap: 6"));
        assertTrue(output.contains("1 comparable complete observed week(s) out of 4 observed week(s)"));
        assertTrue(output.contains("Unobserved, blocked, and incomplete weeks are not normalized away"));
        assertTrue(output.contains("No average gap, efficiency percentage, manager score, rank, tier, recommendation"));
        assertTrue(output.contains("intent, fault, or skill attribution is computed"));
    }

    private Fixture initializedFixture() throws Exception {
        Database database = new Database(tempDir.resolve("team-season-lineup-gap-cli.db"));
        database.initialize();
        new LeagueRepository(database).save(new League("l1", "L1", "League", 2026));
        new TeamRepository(database).save(new Team("t1", "1", "l1", "Team One"));
        PlayerRepository players = new PlayerRepository(database);
        players.save(new Player("p1", "s1", "Quarterback", "QB", "CHI"));
        players.save(new Player("p2", "s2", "Receiver Two", "WR", "DET"));
        players.save(new Player("p3", "s3", "Receiver Three", "WR", "MIN"));
        return new Fixture(database);
    }

    private static final LocalDate AS_OF = LocalDate.of(2026, 9, 5);

    private record Fixture(Database database) {
        LeagueTeamSeasonLineupPointsGapEvidenceAnalyzer analyzer() {
            return new LeagueTeamSeasonLineupPointsGapEvidenceAnalyzer(database);
        }

        void saveConfiguration() throws Exception {
            new LeagueConfigurationObservationRepository(database).replace(new LeagueConfigurationObservation(
                "l1", "sleeper", AS_OF, 2026,
                List.of("QB", "WR", "BN"), Map.of("pass_td", 4.0, "rec_td", 6.0)));
        }

        void saveEligibility() throws Exception {
            PlayerFantasyPositionObservationRepository repository =
                new PlayerFantasyPositionObservationRepository(database);
            repository.replace(new PlayerFantasyPositionObservation("p1", "sleeper", AS_OF, List.of("QB")));
            repository.replace(new PlayerFantasyPositionObservation("p2", "sleeper", AS_OF, List.of("WR")));
            repository.replace(new PlayerFantasyPositionObservation("p3", "sleeper", AS_OF, List.of("WR")));
        }

        void saveRoster(int week, List<String> providerPlayerIds, List<String> starterIds) throws Exception {
            new TeamWeekRosterEvidenceRepository(database).save(TeamWeekRosterEvidence.create(
                "l1", "t1", 2026, week, providerPlayerIds, starterIds, "sleeper", AS_OF));
        }

        void saveCoverage(int week, List<String> identityCoveredPlayerIds) throws Exception {
            new PlayerWeekProductionCoverageRepository(database).replace(new PlayerWeekProductionCoverage(
                2026, week, "nflverse", URI.create("https://example.test/stats_player_week_2026.csv"),
                AS_OF, 50, identityCoveredPlayerIds.size(), 0, identityCoveredPlayerIds));
        }

        void saveProduction(String playerId, int week, int passingTouchdowns, int receivingTouchdowns)
            throws Exception {
            new PlayerWeekProductionRepository(database).save(PlayerWeekProduction.create(
                playerId, 2026, week,
                0, passingTouchdowns, 0,
                0, 0,
                0, 0, receivingTouchdowns,
                0, "nflverse", AS_OF));
        }
    }
}
