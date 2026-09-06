package io.butler.bet.sleeper;

import io.butler.bet.data.Database;
import io.butler.bet.data.LeagueRepository;
import io.butler.bet.data.ProviderPlayerWeekPointsEvidenceRepository;
import io.butler.bet.data.TeamRepository;
import io.butler.bet.data.TeamWeekRosterEvidenceRepository;
import io.butler.bet.domain.League;
import io.butler.bet.domain.ProviderPlayerWeekPointsEvidence;
import io.butler.bet.domain.Team;
import io.butler.bet.domain.TeamWeekRosterEvidence;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.math.BigDecimal;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SleeperProviderNativeSeasonScoringAuditTest {
    @TempDir Path tempDir;

    @Test
    void provesLatestRosterIdentityParityAndPreservesExactProviderDecimals() throws Exception {
        Database database = database("ready.db", "1");
        var rosters = new TeamWeekRosterEvidenceRepository(database);
        rosters.save(roster(List.of("old-player"), LocalDate.of(2026, 9, 4), 1));
        rosters.save(roster(List.of("1002", "1001"), LocalDate.of(2026, 9, 5), 1));

        var points = new ProviderPlayerWeekPointsEvidenceRepository(database);
        points.replaceSeasonSnapshot("league-1", 2025, "sleeper", LocalDate.of(2026, 9, 5), List.of(
            point("1001", "1", "hist-2025", "1.00", 1, "matchup.players_points", LocalDate.of(2026, 9, 5))));
        points.replaceSeasonSnapshot("league-1", 2025, "sleeper", LocalDate.of(2026, 9, 6), List.of(
            point("1001", "1", "hist-2025", "12.3400", 1, "matchup.players_points", LocalDate.of(2026, 9, 6)),
            point("1002", "1", "hist-2025", "-0.50", 1, "matchup.players_points", LocalDate.of(2026, 9, 6))));

        var report = new SleeperProviderNativeSeasonScoringAudit(database).audit("league-1", 2025);

        assertTrue(report.ready());
        assertEquals(SleeperProviderNativeSeasonScoringAudit.AuditState.READY, report.state());
        assertEquals("hist-2025", report.providerLeagueId());
        assertEquals(LocalDate.of(2026, 9, 6), report.providerPointsAsOf());
        assertEquals(1, report.observedTeamWeeks());
        assertEquals(1, report.readyTeamWeeks());
        assertEquals(2, report.candidateIdentities());
        assertEquals(2, report.scoredIdentities());

        var week = report.teamWeeks().getFirst();
        assertEquals(List.of("1002", "1001"),
            week.scores().stream().map(SleeperProviderNativeSeasonScoringAudit.PlayerScoreEvidence::providerPlayerId).toList());
        assertEquals("-0.50", week.scores().get(0).points().toPlainString());
        assertEquals("12.3400", week.scores().get(1).points().toPlainString());
        assertEquals(2, week.scores().get(1).points().scale() + 2); // scale is 4; proves no decimal coercion
        assertEquals("1", week.expectedProviderRosterId());
        assertEquals("1", week.observedProviderRosterId());
        assertTrue(week.blockers().isEmpty());
    }

    @Test
    void missingProviderIdentityBlocksInsteadOfInferringZero() throws Exception {
        Database database = database("missing.db", "1");
        new TeamWeekRosterEvidenceRepository(database).save(
            roster(List.of("1001", "1002"), LocalDate.of(2026, 9, 5), 1));
        persist(database, List.of(
            point("1001", "1", "hist-2025", "4.5", 1, "matchup.players_points", LocalDate.of(2026, 9, 6))));

        var report = new SleeperProviderNativeSeasonScoringAudit(database).audit("league-1", 2025);

        assertFalse(report.ready());
        var week = report.teamWeeks().getFirst();
        assertEquals(List.of("1002"), week.missingProviderPlayerIds());
        assertTrue(week.blockers().stream().anyMatch(value -> value.contains("Missing provider-points identities")));
        assertEquals(1, week.scores().size());
    }

    @Test
    void extraProviderIdentityBlocksInsteadOfWideningRosterUniverse() throws Exception {
        Database database = database("extra.db", "1");
        new TeamWeekRosterEvidenceRepository(database).save(
            roster(List.of("1001"), LocalDate.of(2026, 9, 5), 1));
        persist(database, List.of(
            point("1001", "1", "hist-2025", "4.5", 1, "matchup.players_points", LocalDate.of(2026, 9, 6)),
            point("9999", "1", "hist-2025", "7.25", 1, "matchup.players_points", LocalDate.of(2026, 9, 6))));

        var week = new SleeperProviderNativeSeasonScoringAudit(database)
            .audit("league-1", 2025).teamWeeks().getFirst();

        assertEquals(SleeperProviderNativeSeasonScoringAudit.TeamWeekState.BLOCKED, week.state());
        assertEquals(List.of("9999"), week.extraProviderPlayerIds());
        assertEquals(1, week.scores().size());
    }

    @Test
    void duplicateRosterIdentityFailsClosed() throws Exception {
        Database database = database("duplicate.db", "1");
        new TeamWeekRosterEvidenceRepository(database).save(
            roster(List.of("1001", "1001"), LocalDate.of(2026, 9, 5), 1));
        persist(database, List.of(
            point("1001", "1", "hist-2025", "4.5", 1, "matchup.players_points", LocalDate.of(2026, 9, 6))));

        var week = new SleeperProviderNativeSeasonScoringAudit(database)
            .audit("league-1", 2025).teamWeeks().getFirst();

        assertEquals(SleeperProviderNativeSeasonScoringAudit.TeamWeekState.BLOCKED, week.state());
        assertTrue(week.blockers().stream().anyMatch(value -> value.contains("Duplicate provider player ids")));
        assertEquals(2, week.candidateIdentityCount());
        assertEquals(1, week.scores().size());
    }

    @Test
    void providerRosterMismatchFailsClosed() throws Exception {
        Database database = database("roster-mismatch.db", "1");
        new TeamWeekRosterEvidenceRepository(database).save(
            roster(List.of("1001"), LocalDate.of(2026, 9, 5), 1));
        persist(database, List.of(
            point("1001", "99", "hist-2025", "4.5", 1, "matchup.players_points", LocalDate.of(2026, 9, 6))));

        var week = new SleeperProviderNativeSeasonScoringAudit(database)
            .audit("league-1", 2025).teamWeeks().getFirst();

        assertEquals(SleeperProviderNativeSeasonScoringAudit.TeamWeekState.BLOCKED, week.state());
        assertTrue(week.blockers().stream().anyMatch(value -> value.contains("Provider roster id mismatch")));
    }

    @Test
    void providerOnlyTeamWeekAndMixedSnapshotProvenanceBlockWholeSeason() throws Exception {
        Database database = database("provenance.db", "1");
        new TeamWeekRosterEvidenceRepository(database).save(
            roster(List.of("1001"), LocalDate.of(2026, 9, 5), 1));
        persist(database, List.of(
            point("1001", "1", "hist-a", "4.5", 1, "matchup.players_points", LocalDate.of(2026, 9, 6)),
            point("2001", "1", "hist-b", "7", 2, "unexpected.surface", LocalDate.of(2026, 9, 6))));

        var report = new SleeperProviderNativeSeasonScoringAudit(database).audit("league-1", 2025);

        assertFalse(report.ready());
        assertEquals(2, report.observedTeamWeeks());
        assertTrue(report.blockers().stream().anyMatch(value -> value.contains("mixed provider league ids")));
        assertTrue(report.blockers().stream().anyMatch(value -> value.contains("unexpected source surface")));
        var providerOnly = report.teamWeeks().stream().filter(value -> value.week() == 2).findFirst().orElseThrow();
        assertEquals(SleeperProviderNativeSeasonScoringAudit.TeamWeekState.BLOCKED, providerOnly.state());
        assertEquals(List.of("2001"), providerOnly.extraProviderPlayerIds());
        assertTrue(providerOnly.blockers().stream().anyMatch(value -> value.contains("no persisted Sleeper roster evidence")));
    }

    private Database database(String file, String teamExternalId) throws Exception {
        Database database = new Database(tempDir.resolve(file));
        database.initialize();
        new LeagueRepository(database).save(new League("league-1", "current-league", "Test League", 2026));
        new TeamRepository(database).save(new Team("team-1", teamExternalId, "league-1", "Test Team"));
        return database;
    }

    private static TeamWeekRosterEvidence roster(List<String> playerIds, LocalDate asOf, int week) {
        return TeamWeekRosterEvidence.create(
            "league-1", "team-1", 2025, week, playerIds, List.of(), "sleeper", asOf);
    }

    private static ProviderPlayerWeekPointsEvidence point(
        String playerId,
        String providerRosterId,
        String providerLeagueId,
        String points,
        int week,
        String surface,
        LocalDate asOf) {
        return ProviderPlayerWeekPointsEvidence.create(
            "league-1", "team-1", providerRosterId, providerLeagueId,
            2025, week, playerId, new BigDecimal(points), "sleeper", surface, asOf);
    }

    private static void persist(Database database, List<ProviderPlayerWeekPointsEvidence> rows) throws Exception {
        new ProviderPlayerWeekPointsEvidenceRepository(database).replaceSeasonSnapshot(
            "league-1", 2025, "sleeper", LocalDate.of(2026, 9, 6), rows);
    }
}
