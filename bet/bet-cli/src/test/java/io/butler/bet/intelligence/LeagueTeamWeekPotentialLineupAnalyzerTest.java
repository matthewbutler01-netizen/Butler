package io.butler.bet.intelligence;

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
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.math.BigDecimal;
import java.net.URI;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LeagueTeamWeekPotentialLineupAnalyzerTest {
    @TempDir Path tempDir;

    @Test
    void scoresObservedProductionUsesAuthorizedZeroAndSolvesCompletePotentialLineup() throws Exception {
        Fixture fixture = readyFixture();
        fixture.saveProduction("p1", COVERAGE_DATE, 1);

        var report = fixture.analyzer().analyze("l1", "t1", 2026, 3);

        assertEquals(LeagueTeamWeekPotentialLineupCoverageAnalyzer.METRIC_SCOPE, report.metricScope());
        assertEquals(COVERAGE_DATE, report.productionCoverageAsOf());
        assertEquals(2, report.playerScores().size());
        assertEquals(new BigDecimal("4.0"), report.playerScores().get(0).fantasyPoints());
        assertEquals(LeagueTeamWeekPotentialLineupCoverageAnalyzer.ProductionState.OBSERVED,
            report.playerScores().get(0).productionState());
        assertEquals(BigDecimal.ZERO, report.playerScores().get(1).fantasyPoints());
        assertEquals(LeagueTeamWeekPotentialLineupCoverageAnalyzer.ProductionState.IDENTITY_COVERED_ZERO,
            report.playerScores().get(1).productionState());
        assertTrue(report.lineup().complete());
        assertEquals(2, report.lineup().filledSlots());
        assertEquals(new BigDecimal("4.0"), report.lineup().totalPoints());
        assertEquals("p1", report.lineup().assignments().get(0).playerId());
        assertEquals("p2", report.lineup().assignments().get(1).playerId());
    }

    @Test
    void scoresProductionFromCoverageDateInsteadOfNewerSnapshot() throws Exception {
        Fixture fixture = readyFixture();
        fixture.saveProduction("p1", COVERAGE_DATE, 1);
        fixture.saveProduction("p1", COVERAGE_DATE.plusDays(1), 3);

        var report = fixture.analyzer().analyze("l1", "t1", 2026, 3);

        assertEquals(new BigDecimal("4.0"), report.playerScores().get(0).fantasyPoints());
        assertEquals(COVERAGE_DATE, report.playerScores().get(0).productionCoverageAsOf());
        assertEquals(new BigDecimal("4.0"), report.lineup().totalPoints());
    }

    @Test
    void refusesToCalculateWhenCoverageIsBlocked() throws Exception {
        Fixture fixture = baseFixture();
        fixture.saveConfiguration();
        fixture.saveRoster();
        fixture.saveEligibility("p2", List.of("WR"));
        fixture.saveCoverage(List.of("p1", "p2"));
        fixture.saveProduction("p1", COVERAGE_DATE, 1);

        IllegalStateException error = assertThrows(IllegalStateException.class,
            () -> fixture.analyzer().analyze("l1", "t1", 2026, 3));

        assertTrue(error.getMessage().contains("No Sleeper fantasy-position observation for player p1"));
    }

    private Fixture readyFixture() throws Exception {
        Fixture fixture = baseFixture();
        fixture.saveConfiguration();
        fixture.saveRoster();
        fixture.saveEligibility("p1", List.of("QB"));
        fixture.saveEligibility("p2", List.of("WR"));
        fixture.saveCoverage(List.of("p1", "p2"));
        return fixture;
    }

    private Fixture baseFixture() throws Exception {
        Database database = new Database(tempDir.resolve("potential-lineup.db"));
        database.initialize();
        new LeagueRepository(database).save(new League("l1", "L1", "League", 2026));
        new TeamRepository(database).save(new Team("t1", "1", "l1", "Team One"));
        PlayerRepository players = new PlayerRepository(database);
        players.save(new Player("p1", "s1", "Quarterback", "QB", "CHI"));
        players.save(new Player("p2", "s2", "Receiver", "WR", "DET"));
        return new Fixture(database);
    }

    private static final LocalDate OBSERVATION_DATE = LocalDate.of(2026, 9, 5);
    private static final LocalDate ROSTER_DATE = LocalDate.of(2026, 9, 5);
    private static final LocalDate COVERAGE_DATE = LocalDate.of(2026, 9, 5);

    private record Fixture(Database database) {
        LeagueTeamWeekPotentialLineupAnalyzer analyzer() {
            return new LeagueTeamWeekPotentialLineupAnalyzer(database);
        }

        void saveConfiguration() throws Exception {
            new LeagueConfigurationObservationRepository(database).replace(new LeagueConfigurationObservation(
                "l1", "sleeper", OBSERVATION_DATE, 2026,
                List.of("QB", "FLEX", "BN"), Map.of("pass_td", 4.0)));
        }

        void saveRoster() throws Exception {
            new TeamWeekRosterEvidenceRepository(database).save(TeamWeekRosterEvidence.create(
                "l1", "t1", 2026, 3, List.of("s1", "s2"), List.of("s1"),
                "sleeper", ROSTER_DATE));
        }

        void saveEligibility(String playerId, List<String> positions) throws Exception {
            new PlayerFantasyPositionObservationRepository(database).replace(
                new PlayerFantasyPositionObservation(playerId, "sleeper", OBSERVATION_DATE, positions));
        }

        void saveCoverage(List<String> identityCoveredPlayerIds) throws Exception {
            new PlayerWeekProductionCoverageRepository(database).replace(new PlayerWeekProductionCoverage(
                2026, 3, "nflverse", URI.create("https://example.test/week.csv"), COVERAGE_DATE,
                50, 1, 0, identityCoveredPlayerIds));
        }

        void saveProduction(String playerId, LocalDate asOfDate, int passingTouchdowns) throws Exception {
            new PlayerWeekProductionRepository(database).save(PlayerWeekProduction.create(
                playerId, 2026, 3,
                0, passingTouchdowns, 0,
                0, 0, 0,
                0, 0, 0,
                "nflverse", asOfDate));
        }
    }
}
