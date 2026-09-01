package io.butler.bet.intelligence;

import io.butler.bet.data.Database;
import io.butler.bet.data.LeagueRepository;
import io.butler.bet.data.PlayerRepository;
import io.butler.bet.data.RosterRepository;
import io.butler.bet.data.TeamRepository;
import io.butler.bet.domain.League;
import io.butler.bet.domain.Player;
import io.butler.bet.domain.Roster;
import io.butler.bet.domain.Team;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class LeagueAnalyzerTest {
    @TempDir Path tempDir;

    @Test
    void summarizesLeagueTeamsPositionsAndSlots() throws Exception {
        Database database = new Database(tempDir.resolve("analysis.db"));
        database.initialize();
        League league = new League("l1", null, "League");
        Team alpha = new Team("t1", null, league.getId(), "Alpha");
        Team beta = new Team("t2", null, league.getId(), "Beta");
        Player qb = new Player("p1", null, "Quarterback", "QB", "CHI");
        Player wr = new Player("p2", null, "Receiver", "WR", "KC");
        Player rb = new Player("p3", null, "Runner", "RB", "DET");
        new LeagueRepository(database).save(league);
        TeamRepository teams = new TeamRepository(database); teams.save(beta); teams.save(alpha);
        PlayerRepository players = new PlayerRepository(database); players.save(qb); players.save(wr); players.save(rb);
        RosterRepository rosters = new RosterRepository(database);
        rosters.save(new Roster("r1", null, alpha.getId(), qb.getId(), "STARTER"));
        rosters.save(new Roster("r2", null, alpha.getId(), wr.getId(), "BENCH"));
        rosters.save(new Roster("r3", null, beta.getId(), rb.getId(), "RESERVE"));

        LeagueAnalyzer.LeagueReport report = new LeagueAnalyzer(database).analyze(league.getId());
        assertEquals(2, report.teamCount());
        assertEquals(3, report.rosteredPlayers());
        assertEquals(1, report.positionCounts().get("QB"));
        assertEquals(1, report.positionCounts().get("WR"));
        assertEquals(1, report.positionCounts().get("RB"));
        assertEquals("Alpha", report.teams().get(0).teamName());
        assertEquals(2, report.teams().get(0).rosterSize());
        assertEquals(1, report.teams().get(0).slotCounts().get("STARTER"));
        assertEquals(1, report.teams().get(0).slotCounts().get("BENCH"));
    }

    @Test
    void rejectsBlankLeagueId() throws Exception {
        Database database = new Database(tempDir.resolve("blank.db"));
        database.initialize();
        assertThrows(IllegalArgumentException.class, () -> new LeagueAnalyzer(database).analyze(" "));
    }
}
