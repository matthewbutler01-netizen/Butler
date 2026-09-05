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

class LeagueSeasonPotentialLineupEvidenceAnalyzerTest {
    @TempDir Path tempDir;

    @Test
    void preservesTeamNameOrderWithoutScoreRankingOrCrossTeamAggregation() throws Exception {
        Database database = new Database(tempDir.resolve("league-season-potential.db"));
        database.initialize();
        new LeagueRepository(database).save(new League("l1", "L1", "League", 2026));
        TeamRepository teams = new TeamRepository(database);
        teams.save(new Team("t-beta", "2", "l1", "Beta Team"));
        teams.save(new Team("t-alpha", "1", "l1", "Alpha Team"));

        PlayerRepository players = new PlayerRepository(database);
        players.save(new Player("p-alpha", "s-alpha", "Alpha QB", "QB", "CHI"));
        players.save(new Player("p-beta", "s-beta", "Beta QB", "QB", "DET"));

        new LeagueConfigurationObservationRepository(database).replace(new LeagueConfigurationObservation(
            "l1", "sleeper", AS_OF, 2026, List.of("QB", "BN"), Map.of("pass_td", 4.0)));
        PlayerFantasyPositionObservationRepository positions =
            new PlayerFantasyPositionObservationRepository(database);
        positions.replace(new PlayerFantasyPositionObservation("p-alpha", "sleeper", AS_OF, List.of("QB")));
        positions.replace(new PlayerFantasyPositionObservation("p-beta", "sleeper", AS_OF, List.of("QB")));

        TeamWeekRosterEvidenceRepository rosters = new TeamWeekRosterEvidenceRepository(database);
        rosters.save(TeamWeekRosterEvidence.create(
            "l1", "t-alpha", 2026, 1, List.of("s-alpha"), List.of(), "sleeper", AS_OF));
        rosters.save(TeamWeekRosterEvidence.create(
            "l1", "t-beta", 2026, 1, List.of("s-beta"), List.of(), "sleeper", AS_OF));

        new PlayerWeekProductionCoverageRepository(database).replace(new PlayerWeekProductionCoverage(
            2026, 1, "nflverse", URI.create("https://example.test/week.csv"), AS_OF,
            50, 2, 0, List.of("p-alpha", "p-beta")));
        PlayerWeekProductionRepository production = new PlayerWeekProductionRepository(database);
        production.save(PlayerWeekProduction.create(
            "p-alpha", 2026, 1, 0, 1, 0, 0, 0, 0, 0, 0, 0, "nflverse", AS_OF));
        production.save(PlayerWeekProduction.create(
            "p-beta", 2026, 1, 0, 5, 0, 0, 0, 0, 0, 0, 0, "nflverse", AS_OF));

        var report = new LeagueSeasonPotentialLineupEvidenceAnalyzer(database).analyze("l1", 2026);

        assertEquals("League", report.leagueName());
        assertEquals(LeagueTeamSeasonPotentialLineupEvidenceAnalyzer.WEEK_UNIVERSE, report.weekUniverse());
        assertEquals(List.of("Alpha Team", "Beta Team"),
            report.teams().stream().map(LeagueSeasonPotentialLineupEvidenceAnalyzer.TeamEvidence::teamName).toList());
        assertEquals(new BigDecimal("4.0"), report.teams().get(0).seasonEvidence().aggregate()
            .qualifyingTotalPotentialPoints().orElseThrow());
        assertEquals(new BigDecimal("20.0"), report.teams().get(1).seasonEvidence().aggregate()
            .qualifyingTotalPotentialPoints().orElseThrow());
        assertEquals(1, report.teams().get(0).seasonEvidence().aggregate().qualifyingCompleteWeeks());
        assertEquals(1, report.teams().get(1).seasonEvidence().aggregate().qualifyingCompleteWeeks());
        assertTrue(report.policyId().contains("no-ranking-no-cross-team-aggregate"));
    }

    private static final LocalDate AS_OF = LocalDate.of(2026, 9, 5);
}
