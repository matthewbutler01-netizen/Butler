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
import static org.junit.jupiter.api.Assertions.assertTrue;

class LeagueDecisionReadinessAnalyzerTest {
    @TempDir Path tempDir;

    @Test
    void incompleteLeagueIsBlocked() throws Exception {
        Fixture f = fixture();
        f.playerValues.save(PlayerValue.create(f.qb.getId(), 100,
            DynastyProcessValueImporter.SOURCE_1QB, LocalDate.of(2026, 9, 1)));

        var report = new LeagueDecisionReadinessAnalyzer(f.database).analyze(f.league.getId());

        assertEquals(LeagueDecisionReadinessAnalyzer.DecisionReadiness.BLOCKED, report.readiness());
        assertFalse(report.currentValueDecisionsReady());
        assertFalse(report.trendAwareDecisionsReady());
        assertTrue(report.actionPlan().hasRequiredActions());
    }

    @Test
    void completeCurrentSnapshotSupportsCurrentDecisionsOnly() throws Exception {
        Fixture f = fixture();
        saveCompleteValues(f, LocalDate.of(2026, 9, 1));

        var report = new LeagueDecisionReadinessAnalyzer(f.database).analyze(f.league.getId());

        assertEquals(LeagueDecisionReadinessAnalyzer.DecisionReadiness.CURRENT_READY, report.readiness());
        assertTrue(report.currentValueDecisionsReady());
        assertFalse(report.trendAwareDecisionsReady());
        assertTrue(report.franchiseRankingsReady());
    }

    @Test
    void twoComparableSnapshotsSupportTrendAwareDecisions() throws Exception {
        Fixture f = fixture();
        saveCompleteValues(f, LocalDate.of(2026, 9, 1));
        f.playerValues.save(PlayerValue.create(f.qb.getId(), 90,
            DynastyProcessValueImporter.SOURCE_1QB, LocalDate.of(2026, 8, 31)));
        f.playerValues.save(PlayerValue.create(f.wr.getId(), 70,
            DynastyProcessValueImporter.SOURCE_1QB, LocalDate.of(2026, 8, 31)));

        var report = new LeagueDecisionReadinessAnalyzer(f.database).analyze(f.league.getId());

        assertEquals(LeagueDecisionReadinessAnalyzer.DecisionReadiness.TREND_READY, report.readiness());
        assertTrue(report.currentValueDecisionsReady());
        assertTrue(report.trendAwareDecisionsReady());
        assertTrue(report.nextActions().isEmpty());
    }

    private void saveCompleteValues(Fixture f, LocalDate date) throws Exception {
        String source = DynastyProcessValueImporter.SOURCE_1QB;
        f.playerValues.save(PlayerValue.create(f.qb.getId(), 100, source, date));
        f.playerValues.save(PlayerValue.create(f.wr.getId(), 80, source, date));
        f.pickValues.save(DraftPickValue.create(f.alphaFirst.getId(), 70, source, date));
        f.pickValues.save(DraftPickValue.create(f.betaSecond.getId(), 40, source, date));
    }

    private Fixture fixture() throws Exception {
        Database database = new Database(tempDir.resolve("league-decision-readiness-" + UUID.randomUUID() + ".db"));
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

        return new Fixture(database, league, qb, wr, alphaFirst, betaSecond, playerValues, pickValues);
    }

    private record Fixture(Database database, League league, Player qb, Player wr,
                           DraftPick alphaFirst, DraftPick betaSecond,
                           PlayerValueRepository playerValues, DraftPickValueRepository pickValues) {}
}
