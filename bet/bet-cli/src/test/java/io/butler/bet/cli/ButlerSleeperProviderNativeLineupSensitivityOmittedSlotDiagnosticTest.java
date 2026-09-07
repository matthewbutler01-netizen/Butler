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
import io.butler.bet.intelligence.SleeperProviderNativeLineupSensitivityCorpusAuditAnalyzer;
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

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ButlerSleeperProviderNativeLineupSensitivityOmittedSlotDiagnosticTest {
    @TempDir Path tempDir;

    @Test
    void identifiesUniqueCompatibleOmittedOrdinalWithoutChangingLineupSemantics() throws Exception {
        Database database = new Database(tempDir.resolve("omitted-slot.db"));
        database.initialize();

        new LeagueRepository(database).save(new League("l1", "L1", "League One", 2025));
        TeamRepository teams = new TeamRepository(database);
        teams.save(new Team("t1", "1", "l1", "Team One"));
        teams.save(new Team("t2", "2", "l1", "Team Two"));

        PlayerRepository players = new PlayerRepository(database);
        players.save(new Player("q1", "qb-1", "QB One", "QB", "CHI"));
        players.save(new Player("r1", "rb-1", "RB One", "RB", "CHI"));
        players.save(new Player("q2", "qb-2", "QB Two", "QB", "DET"));
        players.save(new Player("r2", "rb-2", "RB Two", "RB", "DET"));

        new LeagueConfigurationObservationRepository(database).replace(new LeagueConfigurationObservation(
            "l1", "sleeper", AS_OF, 2025, List.of("QB", "RB", "BN"), Map.of("pass_td", 4.0)));
        PlayerFantasyPositionObservationRepository eligibility =
            new PlayerFantasyPositionObservationRepository(database);
        eligibility.replace(new PlayerFantasyPositionObservation("q1", "sleeper", AS_OF, List.of("QB")));
        eligibility.replace(new PlayerFantasyPositionObservation("r1", "sleeper", AS_OF, List.of("RB")));
        eligibility.replace(new PlayerFantasyPositionObservation("q2", "sleeper", AS_OF, List.of("QB")));
        eligibility.replace(new PlayerFantasyPositionObservation("r2", "sleeper", AS_OF, List.of("RB")));

        TeamWeekRosterEvidenceRepository rosters = new TeamWeekRosterEvidenceRepository(database);
        rosters.save(TeamWeekRosterEvidence.create(
            "l1", "t1", 2025, 1, List.of("qb-1", "rb-1"), List.of("qb-1"), "sleeper", AS_OF));
        rosters.save(TeamWeekRosterEvidence.create(
            "l1", "t2", 2025, 1, List.of("qb-2", "rb-2"), List.of("qb-2"), "sleeper", AS_OF));

        new ProviderPlayerWeekPointsEvidenceRepository(database).replaceSeasonSnapshot(
            "l1", 2025, "sleeper", PROVIDER_AS_OF, List.of(
                providerRow("t1", "1", "qb-1", "10.0"),
                providerRow("t1", "1", "rb-1", "8.0"),
                providerRow("t2", "2", "qb-2", "12.0"),
                providerRow("t2", "2", "rb-2", "7.0")));

        var report = new SleeperProviderNativeLineupSensitivityCorpusAuditAnalyzer(database).audit();
        var diagnostics = ButlerSleeperProviderNativeLineupSensitivityCorpusAuditCli
            .collectStarterSlotDiagnostics(database, report);
        String output = capture(() ->
            ButlerSleeperProviderNativeLineupSensitivityCorpusAuditCli.print(report, diagnostics));

        assertTrue(output.contains("BF-590 ordered single-omitted-slot compatibility"));
        assertTrue(output.contains("exactly-one-short snapshots evaluated: 2/2"));
        assertTrue(output.contains("omit ordinal 0 QB | compatible snapshots=0/2 | fully compatible=false"));
        assertTrue(output.contains("omit ordinal 1 RB | compatible snapshots=2/2 | fully compatible=true"));
        assertTrue(output.contains("fully compatible candidate ordinals: [1]"));
        assertTrue(output.contains("fully compatible slot labels: [RB]"));
        assertTrue(output.contains("compatibility evidence state: UNIQUE_ORDINAL_COMPATIBILITY"));
        assertTrue(output.contains("does not remove a slot, rewrite historical configuration, reconstruct starters"));
        assertTrue(output.contains("ordered Sleeper starter count 1 does not match supported starting-slot count 2"));
        assertFalse(output.toLowerCase().contains("selected threshold:"));
        assertFalse(output.toLowerCase().contains("best candidate:"));
        assertFalse(output.toLowerCase().contains("winning candidate:"));
    }

    private static ProviderPlayerWeekPointsEvidence providerRow(
        String teamId, String rosterId, String playerId, String points) {
        return ProviderPlayerWeekPointsEvidence.create(
            "l1", teamId, rosterId, "provider-l1", 2025, 1, playerId, new BigDecimal(points),
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
