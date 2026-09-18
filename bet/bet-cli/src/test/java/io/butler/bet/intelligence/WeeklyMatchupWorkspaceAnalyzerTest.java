package io.butler.bet.intelligence;

import io.butler.bet.data.Database;
import io.butler.bet.data.LeagueRepository;
import io.butler.bet.data.TeamRepository;
import io.butler.bet.data.TeamWeekMatchupEvidenceRepository;
import io.butler.bet.domain.League;
import io.butler.bet.domain.Team;
import io.butler.bet.domain.TeamWeekMatchupEvidence;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.LocalDate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WeeklyMatchupWorkspaceAnalyzerTest {
    @TempDir Path tempDir;

    @Test
    void resolvesExactOpponentFromSamePersistedPairingFrame() throws Exception {
        Database database = database();
        TeamWeekMatchupEvidenceRepository evidence = new TeamWeekMatchupEvidenceRepository(database);
        LocalDate asOf = LocalDate.of(2026, 9, 18);
        evidence.save(TeamWeekMatchupEvidence.create("l1", "t1", 2026, 3, 7, "sleeper", asOf));
        evidence.save(TeamWeekMatchupEvidence.create("l1", "t2", 2026, 3, 7, "sleeper", asOf));

        var report = new WeeklyMatchupWorkspaceAnalyzer(database)
            .analyze("L1", "t1", 2026, 3, "sleeper");

        assertEquals("t1", report.userTeamId());
        assertEquals("Alpha", report.userTeamName());
        assertEquals("t2", report.opponentTeamId());
        assertEquals("Beta", report.opponentTeamName());
        assertEquals(7, report.providerMatchupId());
        assertEquals(asOf, report.asOfDate());
    }

    @Test
    void failsClosedWhenPairingIsMissingAmbiguousOrCrossFrame() throws Exception {
        Database missingDatabase = database();
        var missing = assertThrows(IllegalStateException.class,
            () -> new WeeklyMatchupWorkspaceAnalyzer(missingDatabase)
                .analyze("L1", "t1", 2026, 3, "sleeper"));
        assertTrue(missing.getMessage().contains("pairing is unavailable"));

        Database ambiguousDatabase = database();
        TeamRepository ambiguousTeams = new TeamRepository(ambiguousDatabase);
        ambiguousTeams.save(new Team("t3", "3", "l1", "Gamma"));
        TeamWeekMatchupEvidenceRepository ambiguousEvidence =
            new TeamWeekMatchupEvidenceRepository(ambiguousDatabase);
        LocalDate asOf = LocalDate.of(2026, 9, 18);
        ambiguousEvidence.save(TeamWeekMatchupEvidence.create("l1", "t1", 2026, 3, 9, "sleeper", asOf));
        ambiguousEvidence.save(TeamWeekMatchupEvidence.create("l1", "t2", 2026, 3, 9, "sleeper", asOf));
        ambiguousEvidence.save(TeamWeekMatchupEvidence.create("l1", "t3", 2026, 3, 9, "sleeper", asOf));

        var ambiguous = assertThrows(IllegalStateException.class,
            () -> new WeeklyMatchupWorkspaceAnalyzer(ambiguousDatabase)
                .analyze("L1", "t1", 2026, 3, "sleeper"));
        assertTrue(ambiguous.getMessage().contains("incomplete or ambiguous"));

        Database crossFrameDatabase = database();
        TeamWeekMatchupEvidenceRepository crossFrameEvidence =
            new TeamWeekMatchupEvidenceRepository(crossFrameDatabase);
        crossFrameEvidence.save(TeamWeekMatchupEvidence.create(
            "l1", "t1", 2026, 3, 11, "sleeper", LocalDate.of(2026, 9, 18)));
        crossFrameEvidence.save(TeamWeekMatchupEvidence.create(
            "l1", "t2", 2026, 3, 11, "sleeper", LocalDate.of(2026, 9, 17)));

        var crossFrame = assertThrows(IllegalStateException.class,
            () -> new WeeklyMatchupWorkspaceAnalyzer(crossFrameDatabase)
                .analyze("L1", "t1", 2026, 3, "sleeper"));
        assertTrue(crossFrame.getMessage().contains("incomplete or ambiguous"));
    }

    private Database database() throws Exception {
        Database database = new Database(tempDir.resolve("weekly-matchup-" + System.nanoTime() + ".db"));
        database.initialize();
        new LeagueRepository(database).save(new League("l1", "L1", "Dynasty", 2026));
        TeamRepository teams = new TeamRepository(database);
        teams.save(new Team("t1", "1", "l1", "Alpha"));
        teams.save(new Team("t2", "2", "l1", "Beta"));
        return database;
    }
}
