package io.butler.bet.intelligence;

import io.butler.bet.data.Database;
import io.butler.bet.data.LeagueRepository;
import io.butler.bet.data.PlayerProfileRepository;
import io.butler.bet.data.PlayerProfileSnapshotRepository;
import io.butler.bet.data.PlayerRepository;
import io.butler.bet.data.RosterRepository;
import io.butler.bet.data.TeamRepository;
import io.butler.bet.domain.League;
import io.butler.bet.domain.Player;
import io.butler.bet.domain.PlayerProfile;
import io.butler.bet.domain.PlayerProfileSnapshot;
import io.butler.bet.domain.Roster;
import io.butler.bet.domain.Team;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.LocalDate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LeaguePlayerProfileCoverageAnalyzerTest {
    @TempDir Path tempDir;

    @Test
    void keepsExactBirthReportedAgeExperienceOnlyAndMissingSeparate() throws Exception {
        Database database = initialized();
        seedLeague(database);
        var profiles = new PlayerProfileRepository(database);
        var snapshots = new PlayerProfileSnapshotRepository(database);
        profiles.save(new PlayerProfile("p1", LocalDate.of(2000, 5, 1), 5));
        snapshots.save(PlayerProfileSnapshot.create("p2", 24, 2, "sleeper", LocalDate.of(2026, 9, 1)));
        snapshots.save(PlayerProfileSnapshot.create("p3", null, 1, "sleeper", LocalDate.of(2026, 9, 1)));

        var report = new LeaguePlayerProfileCoverageAnalyzer(database).analyze("l1");

        assertEquals(4, report.totalPlayers());
        assertEquals(2, report.ageEvidencePlayers());
        assertEquals(1, report.exactBirthDatePlayers());
        assertEquals(1, report.reportedAgePlayers());
        assertEquals(3, report.experienceEvidencePlayers());
        assertEquals(1, report.noProfileEvidencePlayers());
        assertEquals(50.0, report.ageCoveragePercent());
        assertEquals(25.0, report.exactBirthDateCoveragePercent());
        assertFalse(report.ageComplete());

        var players = report.teams().getFirst().players();
        var exact = players.stream().filter(p -> p.playerId().equals("p1")).findFirst().orElseThrow();
        var reported = players.stream().filter(p -> p.playerId().equals("p2")).findFirst().orElseThrow();
        var experienceOnly = players.stream().filter(p -> p.playerId().equals("p3")).findFirst().orElseThrow();
        assertEquals("canonical-birth-date", exact.ageEvidenceSource());
        assertEquals("sleeper", reported.ageEvidenceSource());
        assertTrue(experienceOnly.hasExperienceEvidence());
        assertFalse(experienceOnly.hasAgeEvidence());
    }

    @Test
    void staleProviderAgeDoesNotOverrideExactBirthDateAndDoesNotCountAsFreshReportedAge() throws Exception {
        Database database = initialized();
        seedLeague(database);
        var profiles = new PlayerProfileRepository(database);
        var snapshots = new PlayerProfileSnapshotRepository(database);
        profiles.save(new PlayerProfile("p1", LocalDate.of(2000, 5, 1), null));
        snapshots.save(PlayerProfileSnapshot.create("p1", 99, 5, "sleeper", LocalDate.of(2026, 8, 1)));
        snapshots.save(PlayerProfileSnapshot.create("p2", 24, 2, "sleeper", LocalDate.of(2026, 8, 1)));

        var report = new LeaguePlayerProfileCoverageAnalyzer(database).analyze(
            "l1", "sleeper", LocalDate.of(2026, 9, 1));

        assertEquals(1, report.ageEvidencePlayers());
        assertEquals(1, report.exactBirthDatePlayers());
        assertEquals(0, report.reportedAgePlayers());
        assertEquals(2, report.staleProviderSnapshotPlayers());
        var exact = report.teams().getFirst().players().stream()
            .filter(p -> p.playerId().equals("p1")).findFirst().orElseThrow();
        assertEquals(LocalDate.of(2000, 5, 1), exact.birthDate());
        assertEquals(null, exact.reportedAge());
        assertTrue(exact.providerSnapshotStale());
    }

    @Test
    void providerSourcesRemainSeparate() throws Exception {
        Database database = initialized();
        seedLeague(database);
        new PlayerProfileSnapshotRepository(database).save(
            PlayerProfileSnapshot.create("p2", 24, 2, "other", LocalDate.of(2026, 9, 1)));

        var sleeper = new LeaguePlayerProfileCoverageAnalyzer(database).analyze("l1");
        var other = new LeaguePlayerProfileCoverageAnalyzer(database).analyze("l1", "other");

        assertEquals(0, sleeper.reportedAgePlayers());
        assertEquals(1, other.reportedAgePlayers());
    }

    private Database initialized() throws Exception {
        Database database = new Database(tempDir.resolve("test.db"));
        database.initialize();
        return database;
    }

    private static void seedLeague(Database database) throws Exception {
        new LeagueRepository(database).save(new League("l1", null, "League"));
        new TeamRepository(database).save(new Team("t1", null, "l1", "Alpha"));
        String[] positions = {"QB", "RB", "WR", "TE"};
        for (int i = 0; i < 4; i++) {
            String playerId = "p" + (i + 1);
            new PlayerRepository(database).save(new Player(playerId, "10" + (i + 1), "Player " + (i + 1), positions[i], null));
            new RosterRepository(database).save(new Roster("r" + (i + 1), null, "t1", playerId, i == 0 ? "STARTER" : "BENCH"));
        }
    }
}
