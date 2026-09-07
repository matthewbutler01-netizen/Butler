package io.butler.bet.intelligence;

import io.butler.bet.data.Database;
import io.butler.bet.data.HistoricalEffectiveLineupConfigurationRepository;
import io.butler.bet.data.LeagueConfigurationObservationRepository;
import io.butler.bet.data.LeagueRepository;
import io.butler.bet.data.PlayerFantasyPositionObservationRepository;
import io.butler.bet.data.PlayerRepository;
import io.butler.bet.data.ProviderPlayerWeekPointsEvidenceRepository;
import io.butler.bet.data.TeamRepository;
import io.butler.bet.data.TeamWeekRosterEvidenceRepository;
import io.butler.bet.domain.HistoricalEffectiveLineupConfiguration;
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
import static org.junit.jupiter.api.Assertions.assertTrue;

class LeagueTeamWeekEffectiveHistoricalLineupConfigurationTest {
    @TempDir Path tempDir;

    @Test
    void potentialAndStartedLineupsUseSameDerivedShapeWhileRawConfigurationRemainsUntouched() throws Exception {
        Database database = new Database(tempDir.resolve("effective-lineup.db"));
        database.initialize();
        new LeagueRepository(database).save(new League("l1", "provider-current", "League", 2026));
        new TeamRepository(database).save(new Team("t1", "1", "l1", "Team One"));

        PlayerRepository players = new PlayerRepository(database);
        players.save(new Player("p1", "s1", "Quarterback", "QB", "CHI"));
        players.save(new Player("p2", "s2", "Receiver", "WR", "DET"));
        players.save(new Player("p3", "s3", "Running Back", "RB", "GB"));

        var rawConfigurations = new LeagueConfigurationObservationRepository(database);
        rawConfigurations.replace(new LeagueConfigurationObservation(
            "l1", "sleeper", RAW_AS_OF, 2026,
            List.of("QB", "FLEX", "WR", "BN"), Map.of("pass_td", 4.0)));

        var positions = new PlayerFantasyPositionObservationRepository(database);
        positions.replace(new PlayerFantasyPositionObservation("p1", "sleeper", RAW_AS_OF, List.of("QB")));
        positions.replace(new PlayerFantasyPositionObservation("p2", "sleeper", RAW_AS_OF, List.of("WR")));
        positions.replace(new PlayerFantasyPositionObservation("p3", "sleeper", RAW_AS_OF, List.of("RB")));

        new TeamWeekRosterEvidenceRepository(database).save(TeamWeekRosterEvidence.create(
            "l1", "t1", 2026, 3,
            List.of("s1", "s2", "s3"),
            List.of("s1", "s2"),
            "sleeper", RAW_AS_OF));

        new ProviderPlayerWeekPointsEvidenceRepository(database).replaceSeasonSnapshot(
            "l1", 2026, "sleeper", PROVIDER_AS_OF, List.of(
                provider("s1", "10"), provider("s2", "8"), provider("s3", "50")));

        new HistoricalEffectiveLineupConfigurationRepository(database).replace(
            new HistoricalEffectiveLineupConfiguration(
                "l1", 2026, "sleeper", RAW_AS_OF, DERIVED_AS_OF,
                "provider-2026", "provider-2025", 2025,
                1, "FLEX",
                List.of("QB", "FLEX", "WR"),
                List.of("QB", "WR"),
                HistoricalEffectiveLineupConfigurationResolver.DERIVATION_POLICY_ID));

        var potential = new LeagueTeamWeekPotentialLineupAnalyzer(database)
            .analyze("l1", "t1", 2026, 3);
        var started = new LeagueTeamWeekStartedLineupEvidenceAnalyzer(database)
            .analyze("l1", "t1", 2026, 3);

        assertEquals(2, potential.lineup().startingSlots());
        assertEquals(2, potential.lineup().filledSlots());
        assertEquals(new BigDecimal("18"), potential.lineup().totalPoints());
        assertEquals(List.of("QB", "WR"), potential.lineup().assignments().stream()
            .map(OptimalLegalLineupSolver.Assignment::slot).toList());
        assertTrue(potential.lineup().assignments().stream().noneMatch(a -> "p3".equals(a.playerId())));

        assertEquals(2, started.requiredSlots());
        assertEquals(2, started.filledSlots());
        assertTrue(started.complete());
        assertEquals(new BigDecimal("18"), started.totalStartedPoints());
        assertEquals(List.of("QB", "WR"), started.slots().stream()
            .map(LeagueTeamWeekStartedLineupEvidenceAnalyzer.StartedSlotEvidence::slot).toList());
        assertEquals(List.of("s1", "s2"), started.slots().stream()
            .map(LeagueTeamWeekStartedLineupEvidenceAnalyzer.StartedSlotEvidence::providerStarterId).toList());

        assertEquals(List.of("QB", "FLEX", "WR", "BN"),
            rawConfigurations.findLatestForSeason("l1", 2026, "sleeper").orElseThrow().lineupSlots());
    }

    private static ProviderPlayerWeekPointsEvidence provider(String playerId, String points) {
        return ProviderPlayerWeekPointsEvidence.create(
            "l1", "t1", "1", "provider-2026", 2026, 3, playerId,
            new BigDecimal(points), "sleeper",
            SleeperProviderNativeSeasonScoringAudit.EXPECTED_SOURCE_SURFACE, PROVIDER_AS_OF);
    }

    private static final LocalDate RAW_AS_OF = LocalDate.of(2026, 9, 6);
    private static final LocalDate PROVIDER_AS_OF = LocalDate.of(2026, 9, 6);
    private static final LocalDate DERIVED_AS_OF = LocalDate.of(2026, 9, 7);
}
