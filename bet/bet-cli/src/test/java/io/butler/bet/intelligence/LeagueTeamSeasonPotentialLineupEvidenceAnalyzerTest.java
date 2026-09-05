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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LeagueTeamSeasonPotentialLineupEvidenceAnalyzerTest {
    @TempDir Path tempDir;

    @Test
    void classifiesEveryObservedRosterWeekAndAggregatesOnlyCompleteWeeks() throws Exception {
        Fixture fixture = initializedFixture();
        fixture.saveConfiguration();
        fixture.saveEligibility();

        fixture.saveRoster(1, OLD_ROSTER_DATE, List.of("s1"));
        fixture.saveRoster(1, AS_OF, List.of("s1", "s2"));
        fixture.saveCoverage(1, List.of("p1", "p2"));
        fixture.saveProduction("p1", 1, 1);

        fixture.saveRoster(2, AS_OF, List.of("s1", "s2"));
        fixture.saveCoverage(2, List.of("p1", "p2"));
        fixture.saveProduction("p1", 2, 2);

        fixture.saveRoster(3, AS_OF, List.of("s1"));
        fixture.saveCoverage(3, List.of("p1"));
        fixture.saveProduction("p1", 3, 1);

        fixture.saveRoster(4, AS_OF, List.of("s1"));

        var report = fixture.analyzer().analyze("l1", "t1", 2026);

        assertEquals(LeagueTeamSeasonPotentialLineupEvidenceAnalyzer.WEEK_UNIVERSE, report.weekUniverse());
        assertEquals(4, report.weeks().size());
        assertEquals(List.of(1, 2, 3, 4), report.weeks().stream().map(
            LeagueTeamSeasonPotentialLineupEvidenceAnalyzer.WeekEvidence::week).toList());
        assertEquals(AS_OF, report.weeks().get(0).enumeratedRosterEvidenceAsOf());
        assertEquals(LeagueTeamSeasonPotentialLineupEvidenceAnalyzer.WeekState.QUALIFYING_COMPLETE,
            report.weeks().get(0).state());
        assertEquals(new BigDecimal("4.0"), report.weeks().get(0).potentialLineup().lineup().totalPoints());
        assertEquals(LeagueTeamSeasonPotentialLineupEvidenceAnalyzer.WeekState.QUALIFYING_COMPLETE,
            report.weeks().get(1).state());
        assertEquals(new BigDecimal("8.0"), report.weeks().get(1).potentialLineup().lineup().totalPoints());
        assertEquals(LeagueTeamSeasonPotentialLineupEvidenceAnalyzer.WeekState.INCOMPLETE_LINEUP,
            report.weeks().get(2).state());
        assertFalse(report.weeks().get(2).potentialLineup().lineup().complete());
        assertEquals(new BigDecimal("4.0"), report.weeks().get(2).potentialLineup().lineup().totalPoints());
        assertEquals(LeagueTeamSeasonPotentialLineupEvidenceAnalyzer.WeekState.BLOCKED,
            report.weeks().get(3).state());
        assertTrue(report.weeks().get(3).blockers().stream().anyMatch(
            blocker -> blocker.contains("No persisted nflverse week production coverage")));

        var aggregate = report.aggregate();
        assertEquals(4, aggregate.observedWeeks());
        assertEquals(1, aggregate.blockedWeeks());
        assertEquals(1, aggregate.incompleteLineupWeeks());
        assertEquals(2, aggregate.qualifyingCompleteWeeks());
        assertEquals(new BigDecimal("12.0"), aggregate.qualifyingTotalPotentialPoints().orElseThrow());
        assertEquals(0, new BigDecimal("6.0").compareTo(
            aggregate.qualifyingAveragePotentialPoints().orElseThrow()));
    }

    @Test
    void emptyObservedWeekUniverseExposesNoFabricatedZeroAggregate() throws Exception {
        Fixture fixture = initializedFixture();

        var report = fixture.analyzer().analyze("l1", "t1", 2026);

        assertTrue(report.weeks().isEmpty());
        assertEquals(0, report.aggregate().observedWeeks());
        assertEquals(0, report.aggregate().qualifyingCompleteWeeks());
        assertTrue(report.aggregate().qualifyingTotalPotentialPoints().isEmpty());
        assertTrue(report.aggregate().qualifyingAveragePotentialPoints().isEmpty());
    }

    private Fixture initializedFixture() throws Exception {
        Database database = new Database(tempDir.resolve("season-potential.db"));
        database.initialize();
        new LeagueRepository(database).save(new League("l1", "L1", "League", 2026));
        new TeamRepository(database).save(new Team("t1", "1", "l1", "Team One"));
        PlayerRepository players = new PlayerRepository(database);
        players.save(new Player("p1", "s1", "Quarterback", "QB", "CHI"));
        players.save(new Player("p2", "s2", "Receiver", "WR", "DET"));
        return new Fixture(database);
    }

    private static final LocalDate OLD_ROSTER_DATE = LocalDate.of(2026, 9, 4);
    private static final LocalDate AS_OF = LocalDate.of(2026, 9, 5);

    private record Fixture(Database database) {
        LeagueTeamSeasonPotentialLineupEvidenceAnalyzer analyzer() {
            return new LeagueTeamSeasonPotentialLineupEvidenceAnalyzer(database);
        }

        void saveConfiguration() throws Exception {
            new LeagueConfigurationObservationRepository(database).replace(new LeagueConfigurationObservation(
                "l1", "sleeper", AS_OF, 2026,
                List.of("QB", "FLEX", "BN"), Map.of("pass_td", 4.0)));
        }

        void saveEligibility() throws Exception {
            PlayerFantasyPositionObservationRepository repository =
                new PlayerFantasyPositionObservationRepository(database);
            repository.replace(new PlayerFantasyPositionObservation("p1", "sleeper", AS_OF, List.of("QB")));
            repository.replace(new PlayerFantasyPositionObservation("p2", "sleeper", AS_OF, List.of("WR")));
        }

        void saveRoster(int week, LocalDate asOf, List<String> providerPlayerIds) throws Exception {
            new TeamWeekRosterEvidenceRepository(database).save(TeamWeekRosterEvidence.create(
                "l1", "t1", 2026, week, providerPlayerIds, List.of(), "sleeper", asOf));
        }

        void saveCoverage(int week, List<String> identityCoveredPlayerIds) throws Exception {
            new PlayerWeekProductionCoverageRepository(database).replace(new PlayerWeekProductionCoverage(
                2026, week, "nflverse",
                URI.create("https://example.test/stats_player_week_2026.csv"),
                AS_OF, 50, 1, 0, identityCoveredPlayerIds));
        }

        void saveProduction(String playerId, int week, int passingTouchdowns) throws Exception {
            new PlayerWeekProductionRepository(database).save(PlayerWeekProduction.create(
                playerId, 2026, week,
                0, passingTouchdowns, 0,
                0, 0, 0,
                0, 0, 0,
                "nflverse", AS_OF));
        }
    }
}
