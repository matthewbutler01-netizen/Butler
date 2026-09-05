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
import static org.junit.jupiter.api.Assertions.assertTrue;

class LeagueTeamSeasonLineupPointsGapEvidenceAnalyzerTest {
    @TempDir Path tempDir;

    @Test
    void preservesObservedWeekStatesAndAggregatesOnlyComparableCompleteWeeks() throws Exception {
        Fixture fixture = initializedFixture();
        fixture.saveConfiguration();
        fixture.saveEligibility();

        fixture.saveRoster(1, List.of("s1", "s2", "s3"), List.of("s1", "s2"));
        fixture.saveCoverage(1, List.of("p1", "p2", "p3"));
        fixture.saveProduction("p1", 1, 1, 0);
        fixture.saveProduction("p2", 1, 0, 1);
        fixture.saveProduction("p3", 1, 0, 2);

        fixture.saveRoster(2, List.of("s1", "s2", "s3"), List.of("s1", "s3"));
        fixture.saveCoverage(2, List.of("p1", "p2", "p3"));
        fixture.saveProduction("p1", 2, 2, 0);
        fixture.saveProduction("p2", 2, 0, 1);
        fixture.saveProduction("p3", 2, 0, 2);

        fixture.saveRoster(3, List.of("s1"), List.of("s1", "0"));
        fixture.saveCoverage(3, List.of("p1"));
        fixture.saveProduction("p1", 3, 1, 0);

        fixture.saveRoster(4, List.of("s1", "s2"), List.of("s1", "0"));
        fixture.saveCoverage(4, List.of("p1", "p2"));
        fixture.saveProduction("p1", 4, 1, 0);
        fixture.saveProduction("p2", 4, 0, 1);

        fixture.saveRoster(5, List.of("s1", "s2"), List.of("s1", "s2"));

        var report = fixture.analyzer().analyze("l1", "t1", 2026);

        assertEquals(LeagueTeamSeasonLineupPointsGapEvidenceAnalyzer.WEEK_UNIVERSE, report.weekUniverse());
        assertEquals(LeagueTeamSeasonLineupPointsGapEvidenceAnalyzer.AGGREGATE_POLICY, report.aggregatePolicy());
        assertEquals(5, report.weeks().size());
        assertEquals(List.of(1, 2, 3, 4, 5), report.weeks().stream()
            .map(LeagueTeamSeasonLineupPointsGapEvidenceAnalyzer.WeekEvidence::week).toList());

        assertEquals(LeagueTeamSeasonLineupPointsGapEvidenceAnalyzer.WeekState.COMPARABLE_COMPLETE,
            report.weeks().get(0).state());
        assertEquals(new BigDecimal("10.0"), report.weeks().get(0).pointsGap().startedPoints());
        assertEquals(new BigDecimal("16.0"), report.weeks().get(0).pointsGap().potentialPoints());
        assertEquals(new BigDecimal("6.0"), report.weeks().get(0).pointsGap().pointsGap());

        assertEquals(LeagueTeamSeasonLineupPointsGapEvidenceAnalyzer.WeekState.COMPARABLE_COMPLETE,
            report.weeks().get(1).state());
        assertEquals(new BigDecimal("20.0"), report.weeks().get(1).pointsGap().startedPoints());
        assertEquals(new BigDecimal("20.0"), report.weeks().get(1).pointsGap().potentialPoints());
        assertEquals(0, report.weeks().get(1).pointsGap().pointsGap().compareTo(BigDecimal.ZERO));

        assertEquals(LeagueTeamSeasonLineupPointsGapEvidenceAnalyzer.WeekState.POTENTIAL_INCOMPLETE,
            report.weeks().get(2).state());
        assertTrue(report.weeks().get(2).sourcePotentialWeek().potentialLineup() != null);
        assertTrue(!report.weeks().get(2).sourcePotentialWeek().potentialLineup().lineup().complete());

        assertEquals(LeagueTeamSeasonLineupPointsGapEvidenceAnalyzer.WeekState.STARTED_INCOMPLETE,
            report.weeks().get(3).state());
        assertTrue(report.weeks().get(3).startedLineup() != null);
        assertTrue(!report.weeks().get(3).startedLineup().complete());
        assertEquals(null, report.weeks().get(3).pointsGap());

        assertEquals(LeagueTeamSeasonLineupPointsGapEvidenceAnalyzer.WeekState.BLOCKED,
            report.weeks().get(4).state());
        assertTrue(report.weeks().get(4).blockers().stream().anyMatch(
            blocker -> blocker.contains("No persisted nflverse week production coverage")));

        var aggregate = report.aggregate();
        assertEquals(5, aggregate.observedWeeks());
        assertEquals(1, aggregate.blockedWeeks());
        assertEquals(1, aggregate.potentialIncompleteWeeks());
        assertEquals(1, aggregate.startedIncompleteWeeks());
        assertEquals(2, aggregate.comparableCompleteWeeks());
        assertEquals(new BigDecimal("30.0"), aggregate.comparableTotalStartedPoints().orElseThrow());
        assertEquals(new BigDecimal("36.0"), aggregate.comparableTotalPotentialPoints().orElseThrow());
        assertEquals(new BigDecimal("6.0"), aggregate.comparableTotalPointsGap().orElseThrow());
    }

    @Test
    void emptyObservedWeekUniverseExposesNoFabricatedZeroTotals() throws Exception {
        Fixture fixture = initializedFixture();

        var report = fixture.analyzer().analyze("l1", "t1", 2026);

        assertTrue(report.weeks().isEmpty());
        assertEquals(0, report.aggregate().observedWeeks());
        assertEquals(0, report.aggregate().comparableCompleteWeeks());
        assertTrue(report.aggregate().comparableTotalStartedPoints().isEmpty());
        assertTrue(report.aggregate().comparableTotalPotentialPoints().isEmpty());
        assertTrue(report.aggregate().comparableTotalPointsGap().isEmpty());
    }

    private Fixture initializedFixture() throws Exception {
        Database database = new Database(tempDir.resolve("season-lineup-gap.db"));
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
                0, 0, 0,
                0, 0, receivingTouchdowns,
                0, "nflverse", AS_OF));
        }
    }
}
