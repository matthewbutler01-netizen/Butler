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
import io.butler.bet.intelligence.HistoricalScoringLaneSelector;
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
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ButlerSleeperProviderNativeLineupSensitivityCorpusAuditCliTest {
    @TempDir Path tempDir;

    @Test
    void rejectsSelectionArguments() {
        ButlerSleeperProviderNativeLineupSensitivityCorpusAuditCli.parse(new String[0]);
        ButlerSleeperProviderNativeLineupSensitivityCorpusAuditCli.parse(null);
        assertThrows(IllegalArgumentException.class,
            () -> ButlerSleeperProviderNativeLineupSensitivityCorpusAuditCli.parse(new String[] {"2025"}));
        assertThrows(IllegalArgumentException.class,
            () -> ButlerSleeperProviderNativeLineupSensitivityCorpusAuditCli.parse(new String[] {"league-id", "2025"}));
    }

    @Test
    void rendersBlockedFixedFrameEntryWithoutHidingItOrSelectingThreshold() throws Exception {
        Database database = new Database(tempDir.resolve("audit-cli.db"));
        database.initialize();
        new LeagueRepository(database).save(new League("l1", "L1", "League One", 2025));
        new TeamRepository(database).save(new Team("t1", "1", "l1", "Team One"));

        ProviderPlayerWeekPointsEvidence row = ProviderPlayerWeekPointsEvidence.create(
            "l1", "t1", "1", "provider-l1", 2025, 1, "player-1", new BigDecimal("10.25"),
            "sleeper", SleeperProviderNativeSeasonScoringAudit.EXPECTED_SOURCE_SURFACE, PROVIDER_AS_OF);
        new ProviderPlayerWeekPointsEvidenceRepository(database).replaceSeasonSnapshot(
            "l1", 2025, "sleeper", PROVIDER_AS_OF, List.of(row));

        var report = new SleeperProviderNativeLineupSensitivityCorpusAuditAnalyzer(database).audit();
        String output = capture(() -> ButlerSleeperProviderNativeLineupSensitivityCorpusAuditCli.print(report));

        assertTrue(output.contains("Fixed-frame league-seasons: 1"));
        assertTrue(output.contains("Provider-native READY: 0"));
        assertTrue(output.contains("Provider-native BLOCKED: 1"));
        assertTrue(output.contains("PROVIDER_NATIVE_BLOCKED"));
        assertTrue(output.contains("BLOCKED"));
        assertTrue(output.contains(HistoricalScoringLaneSelector.Lane.SLEEPER_PROVIDER_NATIVE.name()));
        assertTrue(output.contains(HistoricalScoringLaneSelector.PROVIDER_NATIVE_SCORING_POLICY_ID));
        assertTrue(output.contains("provider audit: BLOCKED"));
        assertTrue(output.contains("selector blocker:"));
        assertTrue(output.contains("every distinct league-season with persisted Sleeper provider-points evidence"));
        assertTrue(output.contains("accepts no selection arguments"));
        assertTrue(output.contains("not BF-521 readiness, statistical confidence, candidate quality"));
        assertFalse(output.toLowerCase().contains("selected threshold:"));
        assertFalse(output.toLowerCase().contains("best candidate:"));
        assertFalse(output.toLowerCase().contains("winning candidate:"));
    }

    @Test
    void explainsZeroCommonWeekIntersectionWithoutUsingPartialFallback() throws Exception {
        Database database = new Database(tempDir.resolve("zero-common-weeks.db"));
        database.initialize();

        new LeagueRepository(database).save(new League("l2", "L2", "League Two", 2025));
        TeamRepository teams = new TeamRepository(database);
        teams.save(new Team("t1", "1", "l2", "Team One"));
        teams.save(new Team("t2", "2", "l2", "Team Two"));

        PlayerRepository players = new PlayerRepository(database);
        players.save(new Player("p1", "player-1", "Player One", "QB", "CHI"));
        players.save(new Player("p2", "player-2", "Player Two", "QB", "DET"));

        new LeagueConfigurationObservationRepository(database).replace(new LeagueConfigurationObservation(
            "l2", "sleeper", AS_OF, 2025, List.of("QB"), Map.of("pass_td", 4.0)));
        PlayerFantasyPositionObservationRepository eligibility =
            new PlayerFantasyPositionObservationRepository(database);
        eligibility.replace(new PlayerFantasyPositionObservation("p1", "sleeper", AS_OF, List.of("QB")));
        eligibility.replace(new PlayerFantasyPositionObservation("p2", "sleeper", AS_OF, List.of("QB")));

        TeamWeekRosterEvidenceRepository rosters = new TeamWeekRosterEvidenceRepository(database);
        rosters.save(TeamWeekRosterEvidence.create(
            "l2", "t1", 2025, 1, List.of("player-1"), List.of("player-1"), "sleeper", AS_OF));
        rosters.save(TeamWeekRosterEvidence.create(
            "l2", "t2", 2025, 2, List.of("player-2"), List.of("player-2"), "sleeper", AS_OF));

        ProviderPlayerWeekPointsEvidence teamOne = ProviderPlayerWeekPointsEvidence.create(
            "l2", "t1", "1", "provider-l2", 2025, 1, "player-1", new BigDecimal("10.0"),
            "sleeper", SleeperProviderNativeSeasonScoringAudit.EXPECTED_SOURCE_SURFACE, PROVIDER_AS_OF);
        ProviderPlayerWeekPointsEvidence teamTwo = ProviderPlayerWeekPointsEvidence.create(
            "l2", "t2", "2", "provider-l2", 2025, 2, "player-2", new BigDecimal("12.0"),
            "sleeper", SleeperProviderNativeSeasonScoringAudit.EXPECTED_SOURCE_SURFACE, PROVIDER_AS_OF);
        new ProviderPlayerWeekPointsEvidenceRepository(database).replaceSeasonSnapshot(
            "l2", 2025, "sleeper", PROVIDER_AS_OF, List.of(teamOne, teamTwo));

        var report = new SleeperProviderNativeLineupSensitivityCorpusAuditAnalyzer(database).audit();
        String output = capture(() -> ButlerSleeperProviderNativeLineupSensitivityCorpusAuditCli.print(report));

        assertTrue(output.contains("UNAVAILABLE_NO_COMMON_COMPARABLE_WEEKS"));
        assertTrue(output.contains("BF-588 zero-common-week diagnostics"));
        assertTrue(output.contains("Team One [t1] | observed=1 | comparable=1 | comparable weeks=[1]"));
        assertTrue(output.contains("Team Two [t2] | observed=1 | comparable=1 | comparable weeks=[2]"));
        assertTrue(output.contains("week 1 | comparable teams=1/2 | non-comparable=[Team Two=NO_OBSERVED_ROSTER_WEEK]"));
        assertTrue(output.contains("week 2 | comparable teams=1/2 | non-comparable=[Team One=NO_OBSERVED_ROSTER_WEEK]"));
        assertTrue(output.contains("all-team intersection remains authoritative"));
        assertTrue(output.contains("do not authorize a partial common-universe fallback"));
        assertFalse(output.toLowerCase().contains("selected threshold:"));
        assertFalse(output.toLowerCase().contains("best candidate:"));
        assertFalse(output.toLowerCase().contains("winning candidate:"));
    }

    @Test
    void diagnosesPersistedStarterArrayCountMismatchWithoutReconstruction() throws Exception {
        Database database = new Database(tempDir.resolve("starter-count-mismatch.db"));
        database.initialize();

        new LeagueRepository(database).save(new League("l3", "L3", "League Three", 2025));
        TeamRepository teams = new TeamRepository(database);
        teams.save(new Team("t1", "1", "l3", "Team One"));
        teams.save(new Team("t2", "2", "l3", "Team Two"));

        PlayerRepository players = new PlayerRepository(database);
        players.save(new Player("q1", "qb-1", "QB One", "QB", "CHI"));
        players.save(new Player("r1", "rb-1", "RB One", "RB", "CHI"));
        players.save(new Player("q2", "qb-2", "QB Two", "QB", "DET"));
        players.save(new Player("r2", "rb-2", "RB Two", "RB", "DET"));

        new LeagueConfigurationObservationRepository(database).replace(new LeagueConfigurationObservation(
            "l3", "sleeper", AS_OF, 2025, List.of("QB", "RB", "BN"), Map.of("pass_td", 4.0)));
        PlayerFantasyPositionObservationRepository eligibility =
            new PlayerFantasyPositionObservationRepository(database);
        eligibility.replace(new PlayerFantasyPositionObservation("q1", "sleeper", AS_OF, List.of("QB")));
        eligibility.replace(new PlayerFantasyPositionObservation("r1", "sleeper", AS_OF, List.of("RB")));
        eligibility.replace(new PlayerFantasyPositionObservation("q2", "sleeper", AS_OF, List.of("QB")));
        eligibility.replace(new PlayerFantasyPositionObservation("r2", "sleeper", AS_OF, List.of("RB")));

        TeamWeekRosterEvidenceRepository rosters = new TeamWeekRosterEvidenceRepository(database);
        rosters.save(TeamWeekRosterEvidence.create(
            "l3", "t1", 2025, 1, List.of("qb-1", "rb-1"), List.of("qb-1"), "sleeper", AS_OF));
        rosters.save(TeamWeekRosterEvidence.create(
            "l3", "t2", 2025, 1, List.of("qb-2", "rb-2"), List.of("qb-2"), "sleeper", AS_OF));

        List<ProviderPlayerWeekPointsEvidence> providerRows = List.of(
            ProviderPlayerWeekPointsEvidence.create(
                "l3", "t1", "1", "provider-l3", 2025, 1, "qb-1", new BigDecimal("10.0"),
                "sleeper", SleeperProviderNativeSeasonScoringAudit.EXPECTED_SOURCE_SURFACE, PROVIDER_AS_OF),
            ProviderPlayerWeekPointsEvidence.create(
                "l3", "t1", "1", "provider-l3", 2025, 1, "rb-1", new BigDecimal("8.0"),
                "sleeper", SleeperProviderNativeSeasonScoringAudit.EXPECTED_SOURCE_SURFACE, PROVIDER_AS_OF),
            ProviderPlayerWeekPointsEvidence.create(
                "l3", "t2", "2", "provider-l3", 2025, 1, "qb-2", new BigDecimal("12.0"),
                "sleeper", SleeperProviderNativeSeasonScoringAudit.EXPECTED_SOURCE_SURFACE, PROVIDER_AS_OF),
            ProviderPlayerWeekPointsEvidence.create(
                "l3", "t2", "2", "provider-l3", 2025, 1, "rb-2", new BigDecimal("7.0"),
                "sleeper", SleeperProviderNativeSeasonScoringAudit.EXPECTED_SOURCE_SURFACE, PROVIDER_AS_OF));
        new ProviderPlayerWeekPointsEvidenceRepository(database).replaceSeasonSnapshot(
            "l3", 2025, "sleeper", PROVIDER_AS_OF, providerRows);

        var report = new SleeperProviderNativeLineupSensitivityCorpusAuditAnalyzer(database).audit();
        var diagnostics = ButlerSleeperProviderNativeLineupSensitivityCorpusAuditCli
            .collectStarterSlotDiagnostics(database, report);
        String output = capture(() ->
            ButlerSleeperProviderNativeLineupSensitivityCorpusAuditCli.print(report, diagnostics));

        assertTrue(output.contains("UNAVAILABLE_NO_COMMON_COMPARABLE_WEEKS"));
        assertTrue(output.contains("ordered Sleeper starter count 1 does not match supported starting-slot count 2"));
        assertTrue(output.contains("BF-589 starter-slot count diagnostics"));
        assertTrue(output.contains("persisted roster_positions: [QB, RB, BN]"));
        assertTrue(output.contains("supported starting slots: [QB, RB]"));
        assertTrue(output.contains("supported starting-slot count: 2"));
        assertTrue(output.contains("unsupported roster positions: []"));
        assertTrue(output.contains("observed ordered starter-array length distribution: {1=2}"));
        assertTrue(output.contains("roster snapshots: 2 | snapshots containing literal 0 sentinel: 0 | literal 0 sentinel entries: 0"));
        assertTrue(output.contains("do not authorize dropping a configured slot, padding a starter array, reordering starters, or reconstructing missing starter identities"));
        assertFalse(output.toLowerCase().contains("selected threshold:"));
        assertFalse(output.toLowerCase().contains("best candidate:"));
        assertFalse(output.toLowerCase().contains("winning candidate:"));
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
