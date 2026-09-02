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

class LeagueAssetInventoryAnalyzerTest {
    @TempDir Path tempDir;

    @Test
    void listsPlayersAndCurrentlyOwnedPicksWithStableTradeIds() throws Exception {
        Fixture f = fixture(LeagueValueFormat.TWO_QB);
        f.playerValues.save(PlayerValue.create(f.alphaPlayer.getId(), 300,
            DynastyProcessValueImporter.SOURCE_2QB, LocalDate.of(2026, 9, 1)));
        f.playerValues.save(PlayerValue.create(f.betaPlayer.getId(), 200,
            DynastyProcessValueImporter.SOURCE_2QB, LocalDate.of(2026, 9, 1)));
        f.pickValues.save(DraftPickValue.create(f.alphaPickOwnedByBeta.getId(), 150,
            DynastyProcessValueImporter.SOURCE_2QB, LocalDate.of(2026, 8, 28)));
        f.pickValues.save(DraftPickValue.create(f.betaPickOwnedByAlpha.getId(), 75,
            DynastyProcessValueImporter.SOURCE_2QB, LocalDate.of(2026, 8, 28)));

        var report = new LeagueAssetInventoryAnalyzer(f.database).analyze(f.league.getId());

        assertEquals(DynastyProcessValueImporter.SOURCE_2QB, report.source());
        assertEquals(4, report.valuedAssets());
        assertEquals(100.0, report.coveragePercent());
        assertEquals("Alpha", report.teams().get(0).teamName());
        assertEquals(f.alphaPlayer.getId(), report.teams().get(0).players().get(0).playerId());
        assertEquals(f.betaPickOwnedByAlpha.getId(), report.teams().get(0).draftPicks().get(0).draftPickId());
        assertEquals("Beta", report.teams().get(0).draftPicks().get(0).originalTeamName());
        assertEquals("2027 2nd", report.teams().get(0).draftPicks().get(0).label());
    }

    @Test
    void missingValuesRemainExplicitWithoutSuppressingInventory() throws Exception {
        Fixture f = fixture(LeagueValueFormat.ONE_QB);
        f.playerValues.save(PlayerValue.create(f.alphaPlayer.getId(), 100,
            DynastyProcessValueImporter.SOURCE_1QB, LocalDate.of(2026, 9, 1)));
        f.pickValues.save(DraftPickValue.create(f.alphaPickOwnedByBeta.getId(), 125,
            DynastyProcessValueImporter.SOURCE_1QB, LocalDate.of(2026, 8, 28)));

        var report = new LeagueAssetInventoryAnalyzer(f.database).analyze(f.league.getId());

        assertEquals(2, report.valuedAssets());
        assertEquals(2, report.missingAssets());
        assertEquals(50.0, report.coveragePercent());
        assertFalse(report.teams().get(0).draftPicks().get(0).valued());
        assertTrue(report.teams().get(1).draftPicks().get(0).valued());
    }

    @Test
    void explicitSourceOverrideUsesThatSourceForEveryAssetType() throws Exception {
        Fixture f = fixture(LeagueValueFormat.TWO_QB);
        f.playerValues.save(PlayerValue.create(f.alphaPlayer.getId(), 80,
            DynastyProcessValueImporter.SOURCE_1QB, LocalDate.of(2026, 9, 1)));
        f.playerValues.save(PlayerValue.create(f.betaPlayer.getId(), 120,
            DynastyProcessValueImporter.SOURCE_1QB, LocalDate.of(2026, 9, 1)));
        f.pickValues.save(DraftPickValue.create(f.alphaPickOwnedByBeta.getId(), 90,
            DynastyProcessValueImporter.SOURCE_1QB, LocalDate.of(2026, 8, 28)));
        f.pickValues.save(DraftPickValue.create(f.betaPickOwnedByAlpha.getId(), 60,
            DynastyProcessValueImporter.SOURCE_1QB, LocalDate.of(2026, 8, 28)));

        var report = new LeagueAssetInventoryAnalyzer(f.database)
            .analyze(f.league.getId(), DynastyProcessValueImporter.SOURCE_1QB);

        assertEquals(DynastyProcessValueImporter.SOURCE_1QB, report.source());
        assertEquals(80.0, report.teams().get(0).players().get(0).value());
        assertEquals(60.0, report.teams().get(0).draftPicks().get(0).value());
    }

    private Fixture fixture(LeagueValueFormat format) throws Exception {
        Database database = new Database(tempDir.resolve("asset-inventory.db"));
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
        Player alphaPlayer = new Player(UUID.randomUUID().toString(), "alpha-player", "Alpha Player", "QB", "CHI");
        Player betaPlayer = new Player(UUID.randomUUID().toString(), "beta-player", "Beta Player", "WR", "MIN");
        leagues.save(league);
        formats.save(league.getId(), format);
        teams.save(alpha);
        teams.save(beta);
        players.save(alphaPlayer);
        players.save(betaPlayer);
        rosters.save(new Roster(UUID.randomUUID().toString(), null, alpha.getId(), alphaPlayer.getId(), "STARTER"));
        rosters.save(new Roster(UUID.randomUUID().toString(), null, beta.getId(), betaPlayer.getId(), "BENCH"));

        DraftPick alphaPickOwnedByBeta = DraftPick.create(league.getId(), 2027, 1, alpha.getId(), beta.getId());
        DraftPick betaPickOwnedByAlpha = DraftPick.create(league.getId(), 2027, 2, beta.getId(), alpha.getId());
        picks.save(alphaPickOwnedByBeta);
        picks.save(betaPickOwnedByAlpha);

        return new Fixture(database, league, alphaPlayer, betaPlayer,
            alphaPickOwnedByBeta, betaPickOwnedByAlpha, playerValues, pickValues);
    }

    private record Fixture(Database database, League league,
                           Player alphaPlayer, Player betaPlayer,
                           DraftPick alphaPickOwnedByBeta, DraftPick betaPickOwnedByAlpha,
                           PlayerValueRepository playerValues,
                           DraftPickValueRepository pickValues) {}
}
