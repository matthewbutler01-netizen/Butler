package io.butler.bet.data;

import io.butler.bet.domain.DraftPick;
import io.butler.bet.domain.League;
import io.butler.bet.domain.Team;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DraftPickRepositoryTest {
    @TempDir Path tempDir;

    @Test
    void savesUpdatesAndFindsDraftPickOwnership() throws Exception {
        Database database = database();
        LeagueRepository leagues = new LeagueRepository(database);
        TeamRepository teams = new TeamRepository(database);
        DraftPickRepository picks = new DraftPickRepository(database);

        League league = new League(UUID.randomUUID().toString(), "league", "League");
        Team alpha = new Team(UUID.randomUUID().toString(), "1", league.getId(), "Alpha");
        Team beta = new Team(UUID.randomUUID().toString(), "2", league.getId(), "Beta");
        leagues.save(league);
        teams.save(alpha);
        teams.save(beta);

        DraftPick original = DraftPick.create(league.getId(), 2027, 1, alpha.getId(), alpha.getId());
        picks.save(original);
        var saved = picks.findById(original.getId()).orElseThrow();
        assertEquals(alpha.getId(), saved.getOwnerTeamId());
        assertNull(saved.getPickNumber());

        DraftPick traded = new DraftPick(original.getId(), league.getId(), 2027, 1,
            alpha.getId(), beta.getId(), 4);
        picks.save(traded);

        var updated = picks.findByLeagueSeasonRoundAndOriginalTeam(
            league.getId(), 2027, 1, alpha.getId()).orElseThrow();
        assertEquals(original.getId(), updated.getId());
        assertEquals(beta.getId(), updated.getOwnerTeamId());
        assertEquals(4, updated.getPickNumber());
        assertEquals(1, picks.findByLeagueId(league.getId()).size());
        assertEquals(1, picks.findByOwnerTeamId(beta.getId()).size());
    }

    @Test
    void deleteByLeagueRemovesPersistedAssets() throws Exception {
        Database database = database();
        LeagueRepository leagues = new LeagueRepository(database);
        TeamRepository teams = new TeamRepository(database);
        DraftPickRepository picks = new DraftPickRepository(database);

        League league = new League(UUID.randomUUID().toString(), "league", "League");
        Team team = new Team(UUID.randomUUID().toString(), "1", league.getId(), "Alpha");
        leagues.save(league);
        teams.save(team);
        picks.save(DraftPick.create(league.getId(), 2028, 2, team.getId(), team.getId()));

        picks.deleteByLeagueId(league.getId());
        assertTrue(picks.findByLeagueId(league.getId()).isEmpty());
    }

    private Database database() throws Exception {
        Database database = new Database(tempDir.resolve("draft-picks.db"));
        database.initialize();
        return database;
    }
}
