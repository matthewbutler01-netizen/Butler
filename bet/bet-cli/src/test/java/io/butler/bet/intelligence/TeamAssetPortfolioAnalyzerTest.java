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

class TeamAssetPortfolioAnalyzerTest {
    @TempDir Path tempDir;

    @Test
    void combinesPlayersAndCurrentlyOwnedPicksWithoutChangingOwnershipSemantics() throws Exception {
        Fixture f = fixture(LeagueValueFormat.TWO_QB);
        f.playerValues.save(PlayerValue.create(f.qb.getId(), 300,
            DynastyProcessValueImporter.SOURCE_2QB, LocalDate.of(2026, 9, 1)));
        f.playerValues.save(PlayerValue.create(f.wr.getId(), 200,
            DynastyProcessValueImporter.SOURCE_2QB, LocalDate.of(2026, 9, 1)));
        f.pickValues.save(DraftPickValue.create(f.alphaFirst.getId(), 150,
            DynastyProcessValueImporter.SOURCE_2QB, LocalDate.of(2026, 8, 28)));
        f.pickValues.save(DraftPickValue.create(f.betaSecond.getId(), 75,
            DynastyProcessValueImporter.SOURCE_2QB, LocalDate.of(2026, 8, 28)));

        var report = new TeamAssetPortfolioAnalyzer(f.database).analyze(f.league.getId());

        assertEquals(DynastyProcessValueImporter.SOURCE_2QB, report.source());
        assertTrue(report.complete());
        assertEquals(500.0, report.playerValue());
        assertEquals(225.0, report.draftPickValue());
        assertEquals(725.0, report.totalAssetValue());
        assertEquals(100.0, report.coveragePercent());

        var alpha = report.teams().get(0);
        var beta = report.teams().get(1);
        assertEquals("Alpha", alpha.teamName());
        assertEquals(300.0, alpha.playerValue());
        assertEquals(75.0, alpha.draftPickValue());
        assertEquals(375.0, alpha.totalAssetValue());
        assertEquals("Beta", beta.teamName());
        assertEquals(200.0, beta.playerValue());
        assertEquals(150.0, beta.draftPickValue());
        assertEquals(350.0, beta.totalAssetValue());
    }

    @Test
    void missingPlayerAndPickValuesRemainVisibleAsPartialCoverage() throws Exception {
        Fixture f = fixture(LeagueValueFormat.ONE_QB);
        f.playerValues.save(PlayerValue.create(f.qb.getId(), 100,
            DynastyProcessValueImporter.SOURCE_1QB, LocalDate.of(2026, 9, 1)));
        f.pickValues.save(DraftPickValue.create(f.alphaFirst.getId(), 125,
            DynastyProcessValueImporter.SOURCE_1QB, LocalDate.of(2026, 8, 28)));

        var report = new TeamAssetPortfolioAnalyzer(f.database).analyze(f.league.getId());

        assertFalse(report.complete());
        assertEquals(2, report.valuedAssets());
        assertEquals(2, report.missingAssets());
        assertEquals(50.0, report.coveragePercent());
        assertEquals(225.0, report.totalAssetValue());
        assertEquals(1, report.missingPlayers());
        assertEquals(1, report.missingDraftPicks());
    }

    @Test
    void explicitSourceOverrideUsesSameSourceForPlayersAndPicks() throws Exception {
        Fixture f = fixture(LeagueValueFormat.TWO_QB);
        f.playerValues.save(PlayerValue.create(f.qb.getId(), 80,
            DynastyProcessValueImporter.SOURCE_1QB, LocalDate.of(2026, 9, 1)));
        f.playerValues.save(PlayerValue.create(f.wr.getId(), 120,
            DynastyProcessValueImporter.SOURCE_1QB, LocalDate.of(2026, 9, 1)));
        f.pickValues.save(DraftPickValue.create(f.alphaFirst.getId(), 100,
            DynastyProcessValueImporter.SOURCE_1QB, LocalDate.of(2026, 8, 28)));
        f.pickValues.save(DraftPickValue.create(f.betaSecond.getId(), 60,
            DynastyProcessValueImporter.SOURCE_1QB, LocalDate.of(2026, 8, 28)));

        var report = new TeamAssetPortfolioAnalyzer(f.database).analyze(
            f.league.getId(), DynastyProcessValueImporter.SOURCE_1QB);

        assertEquals(DynastyProcessValueImporter.SOURCE_1QB, report.source());
        assertEquals(200.0, report.playerValue());
        assertEquals(160.0, report.draftPickValue());
    }

    private Fixture fixture(LeagueValueFormat format) throws Exception {
        Database database = new Database(tempDir.resolve("portfolio.db"));
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
        Player qb = new Player(UUID.randomUUID().toString(), "qb", "Quarterback", "QB", "CHI");
        Player wr = new Player(UUID.randomUUID().toString(), "wr", "Receiver", "WR", "MIN");
        leagues.save(league);
        formats.save(league.getId(), format);
        teams.save(alpha);
        teams.save(beta);
        players.save(qb);
        players.save(wr);
        rosters.save(new Roster(UUID.randomUUID().toString(), null, alpha.getId(), qb.getId(), "STARTER"));
        rosters.save(new Roster(UUID.randomUUID().toString(), null, beta.getId(), wr.getId(), "STARTER"));

        // Both picks originated with Alpha/Beta respectively, but ownership is swapped.
        DraftPick alphaFirst = DraftPick.create(league.getId(), 2027, 1, alpha.getId(), beta.getId());
        DraftPick betaSecond = DraftPick.create(league.getId(), 2027, 2, beta.getId(), alpha.getId());
        picks.save(alphaFirst);
        picks.save(betaSecond);
        return new Fixture(database, league, qb, wr, alphaFirst, betaSecond, playerValues, pickValues);
    }

    private record Fixture(Database database, League league, Player qb, Player wr,
                           DraftPick alphaFirst, DraftPick betaSecond,
                           PlayerValueRepository playerValues,
                           DraftPickValueRepository pickValues) {}
}
