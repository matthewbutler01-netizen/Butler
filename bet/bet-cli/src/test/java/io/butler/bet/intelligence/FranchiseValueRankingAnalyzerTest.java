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
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

class FranchiseValueRankingAnalyzerTest {
    @TempDir Path tempDir;

    @Test
    void ranksCompleteFranchisePortfoliosByTotalAssetValue() throws Exception {
        Fixture f = fixture(LeagueValueFormat.TWO_QB);
        savePlayer(f, f.alphaPlayer, 300, DynastyProcessValueImporter.SOURCE_2QB);
        savePlayer(f, f.betaPlayer, 260, DynastyProcessValueImporter.SOURCE_2QB);
        savePick(f, f.alphaPickOwnedByBeta, 160, DynastyProcessValueImporter.SOURCE_2QB);
        savePick(f, f.betaPickOwnedByAlpha, 80, DynastyProcessValueImporter.SOURCE_2QB);

        var report = new FranchiseValueRankingAnalyzer(f.database).rank(f.league.getId());

        assertEquals(DynastyProcessValueImporter.SOURCE_2QB, report.source());
        assertNull(report.minimumAsOfDate());
        assertEquals(800.0, report.totalAssetValue());
        assertEquals(1, report.teams().get(0).rank());
        assertEquals("Beta", report.teams().get(0).teamName());
        assertEquals(420.0, report.teams().get(0).totalAssetValue());
        assertEquals(2, report.teams().get(1).rank());
        assertEquals("Alpha", report.teams().get(1).teamName());
        assertEquals(380.0, report.teams().get(1).totalAssetValue());
    }

    @Test
    void explicitSourceOverrideRanksUsingThatSourceForPlayersAndPicks() throws Exception {
        Fixture f = fixture(LeagueValueFormat.TWO_QB);
        savePlayer(f, f.alphaPlayer, 100, DynastyProcessValueImporter.SOURCE_1QB);
        savePlayer(f, f.betaPlayer, 150, DynastyProcessValueImporter.SOURCE_1QB);
        savePick(f, f.alphaPickOwnedByBeta, 50, DynastyProcessValueImporter.SOURCE_1QB);
        savePick(f, f.betaPickOwnedByAlpha, 200, DynastyProcessValueImporter.SOURCE_1QB);

        var report = new FranchiseValueRankingAnalyzer(f.database)
            .rank(f.league.getId(), DynastyProcessValueImporter.SOURCE_1QB);

        assertEquals(DynastyProcessValueImporter.SOURCE_1QB, report.source());
        assertEquals("Alpha", report.teams().get(0).teamName());
        assertEquals(300.0, report.teams().get(0).totalAssetValue());
    }

    @Test
    void explicitMinimumDateAllowsValuesOnTheCutoffDate() throws Exception {
        Fixture f = fixture(LeagueValueFormat.TWO_QB);
        savePlayer(f, f.alphaPlayer, 300, DynastyProcessValueImporter.SOURCE_2QB);
        savePlayer(f, f.betaPlayer, 260, DynastyProcessValueImporter.SOURCE_2QB);
        savePick(f, f.alphaPickOwnedByBeta, 160, DynastyProcessValueImporter.SOURCE_2QB);
        savePick(f, f.betaPickOwnedByAlpha, 80, DynastyProcessValueImporter.SOURCE_2QB);

        LocalDate cutoff = LocalDate.of(2026, 8, 28);
        var report = new FranchiseValueRankingAnalyzer(f.database).rank(f.league.getId(), cutoff);

        assertEquals(cutoff, report.minimumAsOfDate());
        assertEquals(DynastyProcessValueImporter.SOURCE_2QB, report.source());
        assertEquals("Beta", report.teams().getFirst().teamName());
    }

    @Test
    void refusesRankingWhenAnyValuedAssetPredatesExplicitMinimumDate() throws Exception {
        Fixture f = fixture(LeagueValueFormat.TWO_QB);
        savePlayer(f, f.alphaPlayer, 300, DynastyProcessValueImporter.SOURCE_2QB);
        savePlayer(f, f.betaPlayer, 260, DynastyProcessValueImporter.SOURCE_2QB);
        savePick(f, f.alphaPickOwnedByBeta, 160, DynastyProcessValueImporter.SOURCE_2QB);
        savePick(f, f.betaPickOwnedByAlpha, 80, DynastyProcessValueImporter.SOURCE_2QB);

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
            () -> new FranchiseValueRankingAnalyzer(f.database)
                .rank(f.league.getId(), LocalDate.of(2026, 8, 29)));

        assertEquals(true, error.getMessage().contains("requires READY asset coverage on or after 2026-08-29"));
        assertEquals(true, error.getMessage().contains("status=STALE"));
        assertEquals(true, error.getMessage().contains("stale-assets=2"));
        assertEquals(true, error.getMessage().contains("oldest-value-date=2026-08-28"));
    }

    @Test
    void explicitSourceAndMinimumDateUseTheRequestedSource() throws Exception {
        Fixture f = fixture(LeagueValueFormat.TWO_QB);
        savePlayer(f, f.alphaPlayer, 100, DynastyProcessValueImporter.SOURCE_1QB);
        savePlayer(f, f.betaPlayer, 150, DynastyProcessValueImporter.SOURCE_1QB);
        savePick(f, f.alphaPickOwnedByBeta, 50, DynastyProcessValueImporter.SOURCE_1QB);
        savePick(f, f.betaPickOwnedByAlpha, 200, DynastyProcessValueImporter.SOURCE_1QB);

        LocalDate cutoff = LocalDate.of(2026, 8, 28);
        var report = new FranchiseValueRankingAnalyzer(f.database)
            .rank(f.league.getId(), DynastyProcessValueImporter.SOURCE_1QB, cutoff);

        assertEquals(DynastyProcessValueImporter.SOURCE_1QB, report.source());
        assertEquals(cutoff, report.minimumAsOfDate());
        assertEquals("Alpha", report.teams().getFirst().teamName());
    }

    @Test
    void refusesRankingWhenAnyAssetValueIsMissing() throws Exception {
        Fixture f = fixture(LeagueValueFormat.ONE_QB);
        savePlayer(f, f.alphaPlayer, 100, DynastyProcessValueImporter.SOURCE_1QB);
        savePlayer(f, f.betaPlayer, 90, DynastyProcessValueImporter.SOURCE_1QB);
        savePick(f, f.alphaPickOwnedByBeta, 50, DynastyProcessValueImporter.SOURCE_1QB);

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
            () -> new FranchiseValueRankingAnalyzer(f.database).rank(f.league.getId()));

        assertEquals(true, error.getMessage().contains("requires complete asset coverage"));
        assertEquals(true, error.getMessage().contains("Alpha"));
        assertEquals(true, error.getMessage().contains("missing-picks=1"));
    }

    private void savePlayer(Fixture f, Player player, double value, String source) throws Exception {
        f.playerValues.save(PlayerValue.create(player.getId(), value, source, LocalDate.of(2026, 9, 1)));
    }

    private void savePick(Fixture f, DraftPick pick, double value, String source) throws Exception {
        f.pickValues.save(DraftPickValue.create(pick.getId(), value, source, LocalDate.of(2026, 8, 28)));
    }

    private Fixture fixture(LeagueValueFormat format) throws Exception {
        Database database = new Database(tempDir.resolve("franchise-rank.db"));
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
        rosters.save(new Roster(UUID.randomUUID().toString(), null, beta.getId(), betaPlayer.getId(), "STARTER"));

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
