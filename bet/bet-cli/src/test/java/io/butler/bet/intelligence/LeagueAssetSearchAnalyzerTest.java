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
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LeagueAssetSearchAnalyzerTest {
    @TempDir Path tempDir;

    @Test
    void searchesDisplayedPlayerAndPickMetadataWithoutChoosingAmbiguousMatches() throws Exception {
        Fixture f = fixture();
        var analyzer = new LeagueAssetSearchAnalyzer(f.database);

        var playerReport = analyzer.search(f.league.getId(), " receiver ");
        assertEquals(DynastyProcessValueImporter.SOURCE_1QB, playerReport.source());
        assertEquals("receiver", playerReport.query());
        assertEquals(1, playerReport.totalMatches());
        assertEquals(f.wr.getId(), playerReport.players().getFirst().playerId());
        assertEquals("Beta", playerReport.players().getFirst().teamName());

        var pickReport = analyzer.search(f.league.getId(), "2027 1st");
        assertEquals(1, pickReport.totalMatches());
        assertEquals(f.alphaFirst.getId(), pickReport.draftPicks().getFirst().draftPickId());
        assertEquals("Beta", pickReport.draftPicks().getFirst().teamName());
        assertEquals("Alpha", pickReport.draftPicks().getFirst().originalTeamName());

        var idReport = analyzer.search(f.league.getId(), f.alphaFirst.getId());
        assertEquals(1, idReport.totalMatches());
        assertEquals(f.alphaFirst.getId(), idReport.draftPicks().getFirst().draftPickId());
    }

    @Test
    void explicitSourceOverrideControlsReturnedValue() throws Exception {
        Fixture f = fixture();
        f.playerValues.save(PlayerValue.create(f.qb.getId(), 999,
            DynastyProcessValueImporter.SOURCE_2QB, LocalDate.of(2026, 9, 2)));

        var report = new LeagueAssetSearchAnalyzer(f.database).search(
            f.league.getId(), "Quarterback", DynastyProcessValueImporter.SOURCE_2QB);

        assertEquals(DynastyProcessValueImporter.SOURCE_2QB, report.source());
        assertEquals(1, report.players().size());
        assertTrue(report.players().getFirst().valued());
        assertEquals(999.0, report.players().getFirst().value());
    }

    @Test
    void rejectsBlankQuery() throws Exception {
        Fixture f = fixture();
        assertThrows(IllegalArgumentException.class,
            () -> new LeagueAssetSearchAnalyzer(f.database).search(f.league.getId(), "  "));
    }

    private Fixture fixture() throws Exception {
        Database database = new Database(tempDir.resolve("asset-search.db"));
        database.initialize();
        LeagueRepository leagues = new LeagueRepository(database);
        LeagueValueFormatRepository formats = new LeagueValueFormatRepository(database);
        TeamRepository teams = new TeamRepository(database);
        PlayerRepository players = new PlayerRepository(database);
        RosterRepository rosters = new RosterRepository(database);
        PlayerValueRepository playerValues = new PlayerValueRepository(database);
        DraftPickRepository picks = new DraftPickRepository(database);
        DraftPickValueRepository pickValues = new DraftPickValueRepository(database);

        League league = new League(UUID.randomUUID().toString(), "league-ext", "League");
        Team alpha = new Team(UUID.randomUUID().toString(), "1", league.getId(), "Alpha");
        Team beta = new Team(UUID.randomUUID().toString(), "2", league.getId(), "Beta");
        Player qb = new Player(UUID.randomUUID().toString(), "qb-ext", "Quarterback Example", "QB", "CHI");
        Player wr = new Player(UUID.randomUUID().toString(), "wr-ext", "Receiver Example", "WR", "MIN");
        leagues.save(league);
        formats.save(league.getId(), LeagueValueFormat.ONE_QB);
        teams.save(alpha);
        teams.save(beta);
        players.save(qb);
        players.save(wr);
        rosters.save(new Roster(UUID.randomUUID().toString(), null, alpha.getId(), qb.getId(), "STARTER"));
        rosters.save(new Roster(UUID.randomUUID().toString(), null, beta.getId(), wr.getId(), "BENCH"));

        DraftPick alphaFirst = DraftPick.create(league.getId(), 2027, 1, alpha.getId(), beta.getId());
        DraftPick betaSecond = DraftPick.create(league.getId(), 2027, 2, beta.getId(), alpha.getId());
        picks.save(alphaFirst);
        picks.save(betaSecond);

        playerValues.save(PlayerValue.create(qb.getId(), 100,
            DynastyProcessValueImporter.SOURCE_1QB, LocalDate.of(2026, 9, 1)));
        playerValues.save(PlayerValue.create(wr.getId(), 80,
            DynastyProcessValueImporter.SOURCE_1QB, LocalDate.of(2026, 9, 1)));
        pickValues.save(DraftPickValue.create(alphaFirst.getId(), 70,
            DynastyProcessValueImporter.SOURCE_1QB, LocalDate.of(2026, 8, 28)));
        pickValues.save(DraftPickValue.create(betaSecond.getId(), 40,
            DynastyProcessValueImporter.SOURCE_1QB, LocalDate.of(2026, 8, 28)));

        return new Fixture(database, league, qb, wr, alphaFirst, playerValues);
    }

    private record Fixture(Database database, League league, Player qb, Player wr,
                           DraftPick alphaFirst, PlayerValueRepository playerValues) {}
}
