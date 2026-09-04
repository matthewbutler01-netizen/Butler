package io.butler.bet.integration.sleeper;

import io.butler.bet.data.Database;
import io.butler.bet.data.LeagueRepository;
import io.butler.bet.data.PlayerRepository;
import io.butler.bet.data.RosterRepository;
import io.butler.bet.data.TeamRepository;
import io.butler.bet.domain.League;
import io.butler.bet.domain.Player;
import io.butler.bet.domain.Roster;
import io.butler.bet.domain.Team;
import io.butler.bet.intelligence.TradeAssetAnalyzer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.net.URI;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SleeperCounterTradeReconciliationServiceTest {
    @TempDir
    Path tempDir;

    @Test
    void resolvesFetchesAndMatchesOfficialTransactionEvidence() throws Exception {
        Database database = fixture("289646328504385536");
        AtomicReference<URI> requested = new AtomicReference<>();
        var client = new SleeperReadOnlyClient(uri -> {
            requested.set(uri);
            return new SleeperReadOnlyClient.Response(200, """
                [{
                  "type":"trade",
                  "transaction_id":"tx-1",
                  "status":"pending",
                  "creator":"user-1",
                  "created":2500,
                  "status_updated":2600,
                  "leg":3,
                  "roster_ids":[1,2],
                  "consenter_ids":[1],
                  "adds":{"202":1,"101":2},
                  "drops":{"101":1,"202":2},
                  "draft_picks":[]
                }]
                """);
        });

        var report = new SleeperCounterTradeReconciliationService(database, client).reconcile(
            "league-1",
            "team-a",
            "team-b",
            new TradeAssetAnalyzer.TradePackage(List.of("p1"), List.of()),
            new TradeAssetAnalyzer.TradePackage(List.of("p2"), List.of()),
            3,
            "user-1",
            2_000L);

        assertEquals(SleeperCounterTradeReconciliationService.State.RECONCILED, report.state());
        assertEquals(SleeperTradeReconciliationPolicy.State.MATCH_PENDING,
            report.reconciliation().state());
        assertEquals(List.of("tx-1"), report.reconciliation().matchingTransactionIds());
        assertEquals(1, report.observedTransactions().size());
        assertEquals(URI.create(
            "https://api.sleeper.app/v1/league/289646328504385536/transactions/3"),
            requested.get());
    }

    @Test
    void unresolvedIdentityFailsBeforeAnyNetworkCall() throws Exception {
        Database database = fixture("invalid-external-id");
        AtomicInteger calls = new AtomicInteger();
        var client = new SleeperReadOnlyClient(uri -> {
            calls.incrementAndGet();
            return new SleeperReadOnlyClient.Response(200, "[]");
        });

        var report = new SleeperCounterTradeReconciliationService(database, client).reconcile(
            "league-1",
            "team-a",
            "team-b",
            new TradeAssetAnalyzer.TradePackage(List.of("p1"), List.of()),
            new TradeAssetAnalyzer.TradePackage(List.of("p2"), List.of()),
            3,
            null,
            2_000L);

        assertEquals(SleeperCounterTradeReconciliationService.State.INCONCLUSIVE, report.state());
        assertTrue(report.reconciliation() == null);
        assertTrue(report.observedTransactions().isEmpty());
        assertEquals(0, calls.get());
    }

    @Test
    void officialReadFailurePropagatesWithoutInventingReconciliation() throws Exception {
        Database database = fixture("289646328504385536");
        var client = new SleeperReadOnlyClient(uri ->
            new SleeperReadOnlyClient.Response(503, "unavailable"));
        var service = new SleeperCounterTradeReconciliationService(database, client);

        assertThrows(IOException.class, () -> service.reconcile(
            "league-1",
            "team-a",
            "team-b",
            new TradeAssetAnalyzer.TradePackage(List.of("p1"), List.of()),
            new TradeAssetAnalyzer.TradePackage(List.of("p2"), List.of()),
            3,
            null,
            2_000L));
    }

    private Database fixture(String leagueExternalId) throws Exception {
        Database database = new Database(tempDir.resolve("service-" + Math.abs(leagueExternalId.hashCode()) + ".db"));
        database.initialize();
        var leagues = new LeagueRepository(database);
        var teams = new TeamRepository(database);
        var players = new PlayerRepository(database);
        var rosters = new RosterRepository(database);

        leagues.save(new League("league-1", leagueExternalId, "League", 2026));
        teams.save(new Team("team-a", "1", "league-1", "A"));
        teams.save(new Team("team-b", "2", "league-1", "B"));
        players.save(new Player("p1", "101", "P1", "WR", "CHI"));
        players.save(new Player("p2", "202", "P2", "RB", "DET"));
        rosters.save(new Roster("r1", null, "team-a", "p1", "STARTER"));
        rosters.save(new Roster("r2", null, "team-b", "p2", "STARTER"));
        return database;
    }
}
