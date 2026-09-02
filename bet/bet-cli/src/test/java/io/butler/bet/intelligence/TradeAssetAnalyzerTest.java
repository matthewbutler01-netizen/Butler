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
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TradeAssetAnalyzerTest {
    @TempDir Path tempDir;

    @Test
    void comparesPlayersAndPicksWithAutomaticLeagueSource() throws Exception {
        Fixture fixture = fixture(LeagueValueFormat.TWO_QB);
        fixture.playerValues.save(PlayerValue.create(
            fixture.qb.getId(), 300, DynastyProcessValueImporter.SOURCE_2QB, LocalDate.of(2026, 9, 1)));
        fixture.playerValues.save(PlayerValue.create(
            fixture.wr.getId(), 200, DynastyProcessValueImporter.SOURCE_2QB, LocalDate.of(2026, 9, 1)));
        fixture.pickValues.save(DraftPickValue.create(
            fixture.alphaFirst.getId(), 150, DynastyProcessValueImporter.SOURCE_2QB, LocalDate.of(2026, 8, 28)));

        var report = new TradeAssetAnalyzer(fixture.database).analyze(
            fixture.league.getId(),
            new TradeAssetAnalyzer.TradePackage(
                List.of(fixture.qb.getId()), List.of(fixture.alphaFirst.getId())),
            TradeAssetAnalyzer.TradePackage.players(List.of(fixture.wr.getId())));

        assertEquals(DynastyProcessValueImporter.SOURCE_2QB, report.source());
        assertTrue(report.complete());
        assertEquals(450.0, report.sideA().totalValue());
        assertEquals(200.0, report.sideB().totalValue());
        assertEquals(250.0, report.valueDifference());
        assertEquals(3, report.totalAssets());
        assertEquals(100.0, report.coveragePercent());
        assertEquals("2027 1st", report.sideA().draftPicks().getFirst().label());
        assertEquals("Alpha", report.sideA().draftPicks().getFirst().originalTeamName());
        assertEquals("Alpha", report.sideA().draftPicks().getFirst().ownerTeamName());
        assertEquals(LocalDate.of(2026, 8, 28), report.sideA().draftPicks().getFirst().asOfDate());
    }

    @Test
    void pickOnlyComparisonUsesDraftPickSourceDiscovery() throws Exception {
        Fixture fixture = fixture(LeagueValueFormat.ONE_QB);
        fixture.pickValues.save(DraftPickValue.create(
            fixture.alphaFirst.getId(), 125, DynastyProcessValueImporter.SOURCE_1QB, LocalDate.of(2026, 8, 28)));
        fixture.pickValues.save(DraftPickValue.create(
            fixture.betaSecond.getId(), 75, DynastyProcessValueImporter.SOURCE_1QB, LocalDate.of(2026, 8, 28)));

        var report = new TradeAssetAnalyzer(fixture.database).analyze(
            fixture.league.getId(),
            TradeAssetAnalyzer.TradePackage.picks(List.of(fixture.alphaFirst.getId())),
            TradeAssetAnalyzer.TradePackage.picks(List.of(fixture.betaSecond.getId())));

        assertTrue(report.complete());
        assertEquals(125.0, report.sideA().totalValue());
        assertEquals(75.0, report.sideB().totalValue());
        assertEquals(50.0, report.valueDifference());
        assertEquals(0, report.sideA().players().size());
        assertEquals(1, report.sideA().valuedDraftPicks());
    }

    @Test
    void missingPickValueKeepsDifferenceUnavailable() throws Exception {
        Fixture fixture = fixture(LeagueValueFormat.ONE_QB);
        fixture.playerValues.save(PlayerValue.create(
            fixture.qb.getId(), 100, DynastyProcessValueImporter.SOURCE_1QB, LocalDate.of(2026, 9, 1)));

        var report = new TradeAssetAnalyzer(fixture.database).analyze(
            fixture.league.getId(),
            new TradeAssetAnalyzer.TradePackage(
                List.of(fixture.qb.getId()), List.of(fixture.alphaFirst.getId())),
            TradeAssetAnalyzer.TradePackage.picks(List.of(fixture.betaSecond.getId())));

        assertFalse(report.complete());
        assertEquals(1, report.valuedAssets());
        assertEquals(2, report.missingAssets());
        assertEquals(100.0 / 3.0, report.coveragePercent(), 0.0001);
        assertNull(report.valueDifference());
        assertFalse(report.sideA().draftPicks().getFirst().valued());
    }

    @Test
    void explicitSourceOverrideAppliesToBothAssetTypes() throws Exception {
        Fixture fixture = fixture(LeagueValueFormat.TWO_QB);
        fixture.playerValues.save(PlayerValue.create(
            fixture.qb.getId(), 80, DynastyProcessValueImporter.SOURCE_1QB, LocalDate.of(2026, 9, 1)));
        fixture.pickValues.save(DraftPickValue.create(
            fixture.alphaFirst.getId(), 120, DynastyProcessValueImporter.SOURCE_1QB, LocalDate.of(2026, 8, 28)));
        fixture.pickValues.save(DraftPickValue.create(
            fixture.betaSecond.getId(), 60, DynastyProcessValueImporter.SOURCE_1QB, LocalDate.of(2026, 8, 28)));

        var report = new TradeAssetAnalyzer(fixture.database).analyze(
            fixture.league.getId(),
            new TradeAssetAnalyzer.TradePackage(
                List.of(fixture.qb.getId()), List.of(fixture.alphaFirst.getId())),
            TradeAssetAnalyzer.TradePackage.picks(List.of(fixture.betaSecond.getId())),
            DynastyProcessValueImporter.SOURCE_1QB);

        assertEquals(DynastyProcessValueImporter.SOURCE_1QB, report.source());
        assertEquals(200.0, report.sideA().totalValue());
        assertEquals(60.0, report.sideB().totalValue());
        assertEquals(140.0, report.valueDifference());
    }

    @Test
    void rejectsDuplicateOverlapWrongLeagueAndEmptyPackages() throws Exception {
        Fixture fixture = fixture(LeagueValueFormat.ONE_QB);
        TradeAssetAnalyzer analyzer = new TradeAssetAnalyzer(fixture.database);

        assertThrows(IllegalArgumentException.class, () -> analyzer.analyze(
            fixture.league.getId(),
            new TradeAssetAnalyzer.TradePackage(List.of(), List.of(fixture.alphaFirst.getId(), fixture.alphaFirst.getId())),
            TradeAssetAnalyzer.TradePackage.players(List.of(fixture.wr.getId()))));
        assertThrows(IllegalArgumentException.class, () -> analyzer.analyze(
            fixture.league.getId(),
            TradeAssetAnalyzer.TradePackage.picks(List.of(fixture.alphaFirst.getId())),
            TradeAssetAnalyzer.TradePackage.picks(List.of(fixture.alphaFirst.getId()))));
        assertThrows(IllegalArgumentException.class, () -> analyzer.analyze(
            fixture.league.getId(),
            new TradeAssetAnalyzer.TradePackage(List.of(), List.of()),
            TradeAssetAnalyzer.TradePackage.players(List.of(fixture.wr.getId()))));

        League otherLeague = new League(UUID.randomUUID().toString(), "other-league", "Other");
        Team otherTeam = new Team(UUID.randomUUID().toString(), "9", otherLeague.getId(), "Other Team");
        new LeagueRepository(fixture.database).save(otherLeague);
        new TeamRepository(fixture.database).save(otherTeam);
        DraftPick outsider = DraftPick.create(otherLeague.getId(), 2027, 1, otherTeam.getId(), otherTeam.getId());
        new DraftPickRepository(fixture.database).save(outsider);

        assertThrows(IllegalArgumentException.class, () -> analyzer.analyze(
            fixture.league.getId(),
            TradeAssetAnalyzer.TradePackage.picks(List.of(outsider.getId())),
            TradeAssetAnalyzer.TradePackage.players(List.of(fixture.wr.getId()))));
    }

    @Test
    void rejectsUnknownExplicitSourceAcrossPlayerAndPickStores() throws Exception {
        Fixture fixture = fixture(LeagueValueFormat.ONE_QB);
        fixture.pickValues.save(DraftPickValue.create(
            fixture.alphaFirst.getId(), 125, DynastyProcessValueImporter.SOURCE_1QB, LocalDate.of(2026, 8, 28)));

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class, () ->
            new TradeAssetAnalyzer(fixture.database).analyze(
                fixture.league.getId(),
                TradeAssetAnalyzer.TradePackage.picks(List.of(fixture.alphaFirst.getId())),
                TradeAssetAnalyzer.TradePackage.players(List.of(fixture.wr.getId())),
                "missing"));
        assertEquals("unknown trade value source: missing. Available sources: "
            + DynastyProcessValueImporter.SOURCE_1QB, error.getMessage());
    }

    private Fixture fixture(LeagueValueFormat format) throws Exception {
        Database database = new Database(tempDir.resolve("trade-assets.db"));
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
