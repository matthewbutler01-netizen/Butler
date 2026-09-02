package io.butler.bet.intelligence;

import io.butler.bet.data.Database;
import io.butler.bet.data.DraftPickRepository;
import io.butler.bet.data.DraftPickValueRepository;
import io.butler.bet.data.LeagueRepository;
import io.butler.bet.data.LeagueValueFormatRepository;
import io.butler.bet.data.PlayerRepository;
import io.butler.bet.data.PlayerValueRepository;
import io.butler.bet.data.RosterRepository;
import io.butler.bet.data.TeamRepository;
import io.butler.bet.domain.DraftPick;
import io.butler.bet.domain.DraftPickValue;
import io.butler.bet.domain.League;
import io.butler.bet.domain.LeagueValueFormat;
import io.butler.bet.domain.Player;
import io.butler.bet.domain.PlayerValue;
import io.butler.bet.domain.Roster;
import io.butler.bet.domain.Team;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.LocalDate;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LeagueOverviewAnalyzerTest {
    @TempDir Path tempDir;

    @Test
    void incompleteLeagueReturnsHealthAndActionsWithoutUnsafeRankings() throws Exception {
        Fixture f = fixture();
        f.playerValues.save(PlayerValue.create(f.qb.getId(), 100,
            DynastyProcessValueImporter.SOURCE_1QB, LocalDate.of(2026, 9, 1)));

        var overview = new LeagueOverviewAnalyzer(f.database).analyze(f.league.getId());

        assertEquals(LeagueHealthAnalyzer.HealthStatus.PARTIAL, overview.health().status());
        assertTrue(overview.requiresAttention());
        assertFalse(overview.franchiseRankingsAvailable());
        assertFalse(overview.movementAvailable());
        assertNull(overview.franchiseRankings());
        assertNull(overview.movement());
    }

    @Test
    void completeCurrentValuesExposeRankingsButNotMovementFromOneSnapshot() throws Exception {
        Fixture f = fixture();
        saveLatestValues(f);

        var overview = new LeagueOverviewAnalyzer(f.database).analyze(f.league.getId());

        assertTrue(overview.health().coreAnalysisReady());
        assertFalse(overview.requiresAttention());
        assertTrue(overview.franchiseRankingsAvailable());
        assertFalse(overview.movementAvailable());
        assertEquals("Alpha", overview.topFranchises(1).get(0).teamName());
        assertEquals(1, overview.actionPlan().actions().size());
        assertFalse(overview.actionPlan().actions().get(0).requiredForCoreAnalysis());
    }

    @Test
    void twoPlayerSnapshotsExposeLargestLeagueMoversAlongsideRankings() throws Exception {
        Fixture f = fixture();
        String source = DynastyProcessValueImporter.SOURCE_1QB;
        f.playerValues.save(PlayerValue.create(f.qb.getId(), 90, source, LocalDate.of(2026, 8, 31)));
        f.playerValues.save(PlayerValue.create(f.wr.getId(), 50, source, LocalDate.of(2026, 8, 31)));
        saveLatestValues(f);

        var overview = new LeagueOverviewAnalyzer(f.database).analyze(f.league.getId());

        assertTrue(overview.franchiseRankingsAvailable());
        assertTrue(overview.movementAvailable());
        assertTrue(overview.health().movementReady());
        assertEquals("Receiver", overview.topMovers(1).get(0).playerName());
        assertEquals(30.0, overview.topMovers(1).get(0).delta());
        assertTrue(overview.actionPlan().actions().isEmpty());
    }

    @Test
    void topListsRejectNonPositiveLimits() throws Exception {
        Fixture f = fixture();
        saveLatestValues(f);
        var overview = new LeagueOverviewAnalyzer(f.database).analyze(f.league.getId());

        boolean failed = false;
        try {
            overview.topFranchises(0);
        } catch (IllegalArgumentException expected) {
            failed = true;
        }
        assertTrue(failed);
    }

    private void saveLatestValues(Fixture f) throws Exception {
        String source = DynastyProcessValueImporter.SOURCE_1QB;
        LocalDate date = LocalDate.of(2026, 9, 1);
        f.playerValues.save(PlayerValue.create(f.qb.getId(), 100, source, date));
        f.playerValues.save(PlayerValue.create(f.wr.getId(), 80, source, date));
        f.pickValues.save(DraftPickValue.create(f.alphaFirst.getId(), 70, source, date));
        f.pickValues.save(DraftPickValue.create(f.betaSecond.getId(), 40, source, date));
    }

    private Fixture fixture() throws Exception {
        Database database = new Database(tempDir.resolve("league-overview-" + UUID.randomUUID() + ".db"));
        database.initialize();
        LeagueRepository leagues = new LeagueRepository(database);
        LeagueValueFormatRepository formats = new LeagueValueFormatRepository(database);
        TeamRepository teams = new TeamRepository(database);
        PlayerRepository players = new PlayerRepository(database);
        RosterRepository rosters = new RosterRepository(database);
        PlayerValueRepository playerValues = new PlayerValueRepository(database);
        DraftPickRepository picks = new DraftPickRepository(database);
        DraftPickValueRepository pickValues = new DraftPickValueRepository(database);

        League league = new League(UUID.randomUUID().toString(), "sleeper-league", "League");
        Team alpha = new Team(UUID.randomUUID().toString(), "1", league.getId(), "Alpha");
        Team beta = new Team(UUID.randomUUID().toString(), "2", league.getId(), "Beta");
        Player qb = new Player(UUID.randomUUID().toString(), "qb", "Quarterback", "QB", "CHI");
        Player wr = new Player(UUID.randomUUID().toString(), "wr", "Receiver", "WR", "MIN");
        leagues.save(league);
        formats.save(league.getId(), LeagueValueFormat.ONE_QB);
        teams.save(alpha);
        teams.save(beta);
        players.save(qb);
        players.save(wr);
        rosters.save(new Roster(UUID.randomUUID().toString(), null, alpha.getId(), qb.getId(), "STARTER"));
        rosters.save(new Roster(UUID.randomUUID().toString(), null, beta.getId(), wr.getId(), "STARTER"));

        DraftPick alphaFirst = DraftPick.create(league.getId(), 2027, 1, alpha.getId(), alpha.getId());
        DraftPick betaSecond = DraftPick.create(league.getId(), 2027, 2, beta.getId(), beta.getId());
        picks.save(alphaFirst);
        picks.save(betaSecond);

        return new Fixture(database, league, alpha, beta, qb, wr,
            alphaFirst, betaSecond, playerValues, pickValues);
    }

    private record Fixture(Database database, League league, Team alpha, Team beta,
                           Player qb, Player wr, DraftPick alphaFirst, DraftPick betaSecond,
                           PlayerValueRepository playerValues, DraftPickValueRepository pickValues) {}
}
