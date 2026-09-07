package io.butler.bet.intelligence;

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
import io.butler.bet.sleeper.SleeperProviderNativeSeasonScoringAudit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.math.BigDecimal;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LeagueTeamWeekPotentialLineupKDefEligibilityTest {
    @TempDir Path tempDir;

    @Test
    void providerNativeCoverageRecognizesKAndDefOnlyFromPersistedSleeperEligibility() throws Exception {
        Database database = new Database(tempDir.resolve("k-def-provider-native.db"));
        database.initialize();

        new LeagueRepository(database).save(new League("l1", "provider-current", "League", 2026));
        new TeamRepository(database).save(new Team("t1", "1", "l1", "Team One"));

        PlayerRepository players = new PlayerRepository(database);
        players.save(new Player("p1", "s1", "Canonical Primary Is Irrelevant", "QB", "CHI"));
        players.save(new Player("p2", "s2", "Canonical Primary Is Irrelevant Too", "WR", "PIT"));

        LocalDate rosterAsOf = LocalDate.of(2026, 9, 6);
        LocalDate providerAsOf = LocalDate.of(2026, 9, 7);
        new LeagueConfigurationObservationRepository(database).replace(new LeagueConfigurationObservation(
            "l1",
            "sleeper",
            rosterAsOf,
            2026,
            List.of("K", "DEF", "BN"),
            Map.of("fgm_40_49", 4.0, "sack", 1.0)));
        new TeamWeekRosterEvidenceRepository(database).save(TeamWeekRosterEvidence.create(
            "l1",
            "t1",
            2026,
            3,
            List.of("s1", "s2"),
            List.of("s1", "s2"),
            "sleeper",
            rosterAsOf));

        PlayerFantasyPositionObservationRepository eligibility =
            new PlayerFantasyPositionObservationRepository(database);
        eligibility.replace(new PlayerFantasyPositionObservation(
            "p1", "sleeper", rosterAsOf, List.of("K")));
        eligibility.replace(new PlayerFantasyPositionObservation(
            "p2", "sleeper", rosterAsOf, List.of("DEF")));

        new ProviderPlayerWeekPointsEvidenceRepository(database).replaceSeasonSnapshot(
            "l1",
            2026,
            "sleeper",
            providerAsOf,
            List.of(
                ProviderPlayerWeekPointsEvidence.create(
                    "l1", "t1", "1", "provider-2026", 2026, 3, "s1",
                    new BigDecimal("12.50"), "sleeper",
                    SleeperProviderNativeSeasonScoringAudit.EXPECTED_SOURCE_SURFACE, providerAsOf),
                ProviderPlayerWeekPointsEvidence.create(
                    "l1", "t1", "1", "provider-2026", 2026, 3, "s2",
                    new BigDecimal("9.25"), "sleeper",
                    SleeperProviderNativeSeasonScoringAudit.EXPECTED_SOURCE_SURFACE, providerAsOf)));

        var coverage = new LeagueTeamWeekPotentialLineupCoverageAnalyzer(database)
            .analyze("l1", "t1", 2026, 3);

        assertTrue(coverage.ready());
        assertEquals(HistoricalScoringLaneSelector.Lane.SLEEPER_PROVIDER_NATIVE, coverage.scoringLane());
        assertFalse(coverage.blockers().stream().anyMatch(blocker -> blocker.contains("Unsupported observed lineup slot")));
        assertEquals(List.of("K"), coverage.players().get(0).providerFantasyPositions());
        assertEquals(List.of("DEF"), coverage.players().get(1).providerFantasyPositions());

        var potential = new LeagueTeamWeekPotentialLineupAnalyzer(database).analyze(coverage);
        assertTrue(potential.lineup().complete());
        assertEquals(2, potential.lineup().startingSlots());
        assertEquals(2, potential.lineup().filledSlots());
        assertEquals(new BigDecimal("21.75"), potential.lineup().totalPoints());
        assertEquals("p1", potential.lineup().assignments().get(0).playerId());
        assertEquals("K", potential.lineup().assignments().get(0).slot());
        assertEquals("p2", potential.lineup().assignments().get(1).playerId());
        assertEquals("DEF", potential.lineup().assignments().get(1).slot());
    }
}
