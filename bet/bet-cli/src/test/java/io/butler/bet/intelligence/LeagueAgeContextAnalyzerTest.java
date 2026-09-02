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
import static org.junit.jupiter.api.Assertions.assertNull;

class LeagueAgeContextAnalyzerTest {
    @TempDir Path tempDir;

    @Test
    void exactBirthDateTakesPrecedenceAndReportedAgeIsNotExtrapolated() throws Exception {
        Database database = seeded();
        new PlayerProfileRepository(database).save(new PlayerProfile(
            "p1", LocalDate.of(2000, 10, 1), 4));
        PlayerProfileSnapshotRepository snapshots = new PlayerProfileSnapshotRepository(database);
        snapshots.save(PlayerProfileSnapshot.create("p1", 27, 4, "sleeper", LocalDate.of(2026, 8, 1)));
        snapshots.save(PlayerProfileSnapshot.create("p2", 23, 2, "sleeper", LocalDate.of(2026, 8, 1)));

        var report = new LeagueAgeContextAnalyzer(database).analyze(
            "l1", LocalDate.of(2026, 9, 2), "sleeper", null);
        var team = report.teams().getFirst();
        var p1 = team.players().stream().filter(p -> p.playerId().equals("p1")).findFirst().orElseThrow();
        var p2 = team.players().stream().filter(p -> p.playerId().equals("p2")).findFirst().orElseThrow();

        assertEquals(25, p1.age());
        assertEquals(LeagueAgeContextAnalyzer.AgeProvenance.EXACT_BIRTH_DATE, p1.provenance());
        assertEquals(23, p2.age());
        assertEquals(LeagueAgeContextAnalyzer.AgeProvenance.PROVIDER_REPORTED, p2.provenance());
        assertEquals(2, team.coveredPlayers());
        assertEquals(1, team.exactBirthDatePlayers());
        assertEquals(1, team.providerReportedPlayers());
        assertEquals(24.0, team.averageAge());
        assertEquals(23, team.minimumAge());
        assertEquals(25, team.maximumAge());
    }

    @Test
    void staleReportedAgeIsExcludedButExactBirthDateRemainsUsable() throws Exception {
        Database database = seeded();
        new PlayerProfileRepository(database).save(new PlayerProfile(
            "p1", LocalDate.of(2000, 1, 1), 4));
        PlayerProfileSnapshotRepository snapshots = new PlayerProfileSnapshotRepository(database);
        snapshots.save(PlayerProfileSnapshot.create("p2", 23, 2, "sleeper", LocalDate.of(2026, 7, 1)));

        var report = new LeagueAgeContextAnalyzer(database).analyze(
            "l1", LocalDate.of(2026, 9, 2), "sleeper", LocalDate.of(2026, 8, 1));
        var team = report.teams().getFirst();
        var p2 = team.players().stream().filter(p -> p.playerId().equals("p2")).findFirst().orElseThrow();

        assertEquals(1, team.coveredPlayers());
        assertEquals(1, team.exactBirthDatePlayers());
        assertEquals(0, team.providerReportedPlayers());
        assertNull(p2.age());
        assertEquals(LeagueAgeContextAnalyzer.AgeProvenance.UNAVAILABLE, p2.provenance());
        assertEquals(true, p2.providerSnapshotStale());
    }

    private Database seeded() throws Exception {
        Database database = new Database(tempDir.resolve("age.db"));
        database.initialize();
        new LeagueRepository(database).save(new League("l1", null, "League", 2026));
        new TeamRepository(database).save(new Team("t1", null, "l1", "Alpha"));
        PlayerRepository players = new PlayerRepository(database);
        RosterRepository rosters = new RosterRepository(database);
        players.save(new Player("p1", "101", "Player One", "WR", "CHI"));
        players.save(new Player("p2", "102", "Player Two", "WR", "MIN"));
        rosters.save(new Roster("r1", null, "t1", "p1", "STARTER"));
        rosters.save(new Roster("r2", null, "t1", "p2", "BENCH"));
        return database;
    }
}
