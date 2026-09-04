package io.butler.bet.integration.sleeper;

import io.butler.bet.data.Database;
import io.butler.bet.data.DraftPickRepository;
import io.butler.bet.data.LeagueRepository;
import io.butler.bet.data.PlayerRepository;
import io.butler.bet.data.RosterRepository;
import io.butler.bet.data.TeamRepository;
import io.butler.bet.domain.DraftPick;
import io.butler.bet.domain.League;
import io.butler.bet.domain.Player;
import io.butler.bet.domain.Roster;
import io.butler.bet.domain.Team;
import io.butler.bet.intelligence.TradeAssetAnalyzer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SleeperTradeExpectationResolverTest {
    @TempDir
    Path tempDir;

    @Test
    void resolvesOwnedPlayersAndPicksToExactSleeperCoordinates() throws Exception {
        var fixture = fixture("289646328504385536");
        var resolver = new SleeperTradeExpectationResolver(fixture.database());

        var result = resolver.resolve(
            "league-1",
            "team-a",
            "team-b",
            new TradeAssetAnalyzer.TradePackage(List.of("p1"), List.of("pick-a-2027-2")),
            new TradeAssetAnalyzer.TradePackage(List.of("p2"), List.of()),
            3,
            "user-123",
            9_000L);

        assertTrue(result.available());
        assertEquals(SleeperTradeExpectationResolver.State.RESOLVED, result.state());
        var expected = result.expectedTrade();
        assertEquals("289646328504385536", expected.leagueId());
        assertEquals(3, expected.round());
        assertEquals(Set.of(1, 2), expected.rosterIds());
        assertEquals(Map.of("101", 2, "202", 1), expected.playerAdds());
        assertEquals(Map.of("101", 1, "202", 2), expected.playerDrops());
        assertEquals(Set.of(new SleeperReadOnlyClient.DraftPick("2027", 2, 1, 1, 2)),
            expected.draftPicks());
        assertEquals("user-123", expected.creatorUserId());
        assertEquals(9_000L, expected.notBeforeEpochMillis());
    }

    @Test
    void missingNumericSleeperLeagueExternalIdFailsClosed() throws Exception {
        var fixture = fixture("not-sleeper-id");
        var result = new SleeperTradeExpectationResolver(fixture.database()).resolve(
            "league-1", "team-a", "team-b",
            new TradeAssetAnalyzer.TradePackage(List.of("p1"), List.of()),
            new TradeAssetAnalyzer.TradePackage(List.of("p2"), List.of()),
            1, null, 0);

        assertFalse(result.available());
        assertTrue(result.reason().contains("league"));
        assertTrue(result.reason().contains("external_id"));
    }

    @Test
    void playerMustBeOwnedByStatedSendingTeam() throws Exception {
        var fixture = fixture("289646328504385536");
        var result = new SleeperTradeExpectationResolver(fixture.database()).resolve(
            "league-1", "team-a", "team-b",
            new TradeAssetAnalyzer.TradePackage(List.of("p2"), List.of()),
            new TradeAssetAnalyzer.TradePackage(List.of("p1"), List.of()),
            1, null, 0);

        assertEquals(SleeperTradeExpectationResolver.State.UNAVAILABLE, result.state());
        assertTrue(result.reason().contains("not owned"));
    }

    @Test
    void pickMustBeOwnedByStatedSendingTeam() throws Exception {
        var fixture = fixture("289646328504385536");
        var result = new SleeperTradeExpectationResolver(fixture.database()).resolve(
            "league-1", "team-a", "team-b",
            new TradeAssetAnalyzer.TradePackage(List.of(), List.of()),
            new TradeAssetAnalyzer.TradePackage(List.of("p2"), List.of("pick-a-2027-2")),
            1, null, 0);

        assertEquals(SleeperTradeExpectationResolver.State.UNAVAILABLE, result.state());
        assertTrue(result.reason().contains("draft pick"));
        assertTrue(result.reason().contains("not owned"));
    }

    @Test
    void missingPlayerExternalIdFailsClosed() throws Exception {
        var fixture = fixture("289646328504385536");
        new PlayerRepository(fixture.database()).save(new Player("p1", null, "P1", "WR", "CHI"));

        var result = new SleeperTradeExpectationResolver(fixture.database()).resolve(
            "league-1", "team-a", "team-b",
            new TradeAssetAnalyzer.TradePackage(List.of("p1"), List.of()),
            new TradeAssetAnalyzer.TradePackage(List.of("p2"), List.of()),
            1, null, 0);

        assertEquals(SleeperTradeExpectationResolver.State.UNAVAILABLE, result.state());
        assertTrue(result.reason().contains("player"));
        assertTrue(result.reason().contains("external_id"));
    }

    private Fixture fixture(String leagueExternalId) throws Exception {
        Database database = new Database(tempDir.resolve("resolver-" + Math.abs(leagueExternalId.hashCode()) + ".db"));
        database.initialize();
        var leagues = new LeagueRepository(database);
        var teams = new TeamRepository(database);
        var players = new PlayerRepository(database);
        var rosters = new RosterRepository(database);
        var picks = new DraftPickRepository(database);

        leagues.save(new League("league-1", leagueExternalId, "League", 2026));
        teams.save(new Team("team-a", "1", "league-1", "A"));
        teams.save(new Team("team-b", "2", "league-1", "B"));
        players.save(new Player("p1", "101", "P1", "WR", "CHI"));
        players.save(new Player("p2", "202", "P2", "RB", "DET"));
        rosters.save(new Roster("r1", null, "team-a", "p1", "STARTER"));
        rosters.save(new Roster("r2", null, "team-b", "p2", "STARTER"));
        picks.save(new DraftPick("pick-a-2027-2", "league-1", 2027, 2, "team-a", "team-a", null));
        return new Fixture(database);
    }

    private record Fixture(Database database) {}
}
