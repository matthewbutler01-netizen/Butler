package io.butler.bet.intelligence;

import io.butler.bet.data.Database;
import io.butler.bet.data.LeagueRepository;
import io.butler.bet.data.LeagueValueFormatRepository;
import io.butler.bet.data.PlayerRepository;
import io.butler.bet.data.PlayerValueRepository;
import io.butler.bet.data.RosterRepository;
import io.butler.bet.data.TeamRepository;
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

class TradeValueAnalyzerTest {
    @TempDir Path tempDir;

    @Test
    void automaticallyUsesLeagueFormatSourceAndLatestValues() throws Exception {
        Fixture fixture = fixture(LeagueValueFormat.TWO_QB);
        fixture.values.save(PlayerValue.create(fixture.qb.getId(), 100, DynastyProcessValueImporter.SOURCE_1QB, LocalDate.of(2026, 9, 1)));
        fixture.values.save(PlayerValue.create(fixture.qb.getId(), 250, DynastyProcessValueImporter.SOURCE_2QB, LocalDate.of(2026, 8, 25)));
        fixture.values.save(PlayerValue.create(fixture.qb.getId(), 300, DynastyProcessValueImporter.SOURCE_2QB, LocalDate.of(2026, 9, 1)));
        fixture.values.save(PlayerValue.create(fixture.wr.getId(), 200, DynastyProcessValueImporter.SOURCE_2QB, LocalDate.of(2026, 9, 1)));

        var report = new TradeValueAnalyzer(fixture.database).analyze(
            fixture.league.getId(), List.of(fixture.qb.getId()), List.of(fixture.wr.getId()));

        assertEquals(DynastyProcessValueImporter.SOURCE_2QB, report.source());
        assertTrue(report.complete());
        assertEquals(300.0, report.sideA().totalValue());
        assertEquals(200.0, report.sideB().totalValue());
        assertEquals(100.0, report.valueDifference());
        assertEquals(LocalDate.of(2026, 9, 1), report.sideA().players().getFirst().asOfDate());
        assertEquals("Alpha", report.sideA().players().getFirst().teamName());
        assertEquals("Beta", report.sideB().players().getFirst().teamName());
    }

    @Test
    void explicitSourceOverrideIsPreserved() throws Exception {
        Fixture fixture = fixture(LeagueValueFormat.TWO_QB);
        fixture.values.save(PlayerValue.create(fixture.qb.getId(), 75, DynastyProcessValueImporter.SOURCE_1QB, LocalDate.of(2026, 9, 1)));
        fixture.values.save(PlayerValue.create(fixture.wr.getId(), 125, DynastyProcessValueImporter.SOURCE_1QB, LocalDate.of(2026, 9, 1)));
        fixture.values.save(PlayerValue.create(fixture.qb.getId(), 500, DynastyProcessValueImporter.SOURCE_2QB, LocalDate.of(2026, 9, 1)));
        fixture.values.save(PlayerValue.create(fixture.wr.getId(), 500, DynastyProcessValueImporter.SOURCE_2QB, LocalDate.of(2026, 9, 1)));

        var report = new TradeValueAnalyzer(fixture.database).analyze(
            fixture.league.getId(), List.of(fixture.qb.getId()), List.of(fixture.wr.getId()),
            DynastyProcessValueImporter.SOURCE_1QB);

        assertEquals(DynastyProcessValueImporter.SOURCE_1QB, report.source());
        assertEquals(75.0, report.sideA().totalValue());
        assertEquals(125.0, report.sideB().totalValue());
        assertEquals(-50.0, report.valueDifference());
    }

    @Test
    void missingValuesMakeComparisonIncompleteInsteadOfInventingValues() throws Exception {
        Fixture fixture = fixture(LeagueValueFormat.ONE_QB);
        fixture.values.save(PlayerValue.create(fixture.qb.getId(), 90, DynastyProcessValueImporter.SOURCE_1QB, LocalDate.of(2026, 9, 1)));

        var report = new TradeValueAnalyzer(fixture.database).analyze(
            fixture.league.getId(), List.of(fixture.qb.getId()), List.of(fixture.wr.getId()));

        assertFalse(report.complete());
        assertEquals(1, report.valuedPlayers());
        assertEquals(1, report.missingPlayers());
        assertEquals(50.0, report.coveragePercent());
        assertNull(report.valueDifference());
        assertTrue(report.sideA().players().getFirst().valued());
        assertFalse(report.sideB().players().getFirst().valued());
    }

    @Test
    void rejectsDuplicateOverlapAndUnrosteredPlayers() throws Exception {
        Fixture fixture = fixture(LeagueValueFormat.ONE_QB);
        Player outsider = new Player(UUID.randomUUID().toString(), "outside", "Outside", "TE", "KC");
        fixture.players.save(outsider);
        TradeValueAnalyzer analyzer = new TradeValueAnalyzer(fixture.database);

        assertThrows(IllegalArgumentException.class, () -> analyzer.analyze(
            fixture.league.getId(), List.of(fixture.qb.getId(), fixture.qb.getId()), List.of(fixture.wr.getId())));
        assertThrows(IllegalArgumentException.class, () -> analyzer.analyze(
            fixture.league.getId(), List.of(fixture.qb.getId()), List.of(fixture.qb.getId())));
        assertThrows(IllegalArgumentException.class, () -> analyzer.analyze(
            fixture.league.getId(), List.of(outsider.getId()), List.of(fixture.wr.getId())));
    }

    @Test
    void rejectsEmptyPackagesAndUnknownExplicitSource() throws Exception {
        Fixture fixture = fixture(LeagueValueFormat.ONE_QB);
        fixture.values.save(PlayerValue.create(fixture.qb.getId(), 90, DynastyProcessValueImporter.SOURCE_1QB, LocalDate.of(2026, 9, 1)));
        TradeValueAnalyzer analyzer = new TradeValueAnalyzer(fixture.database);

        assertThrows(IllegalArgumentException.class, () -> analyzer.analyze(
            fixture.league.getId(), List.of(), List.of(fixture.wr.getId())));
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class, () -> analyzer.analyze(
            fixture.league.getId(), List.of(fixture.qb.getId()), List.of(fixture.wr.getId()), "missing"));
        assertEquals("unknown player value source: missing. Available sources: " + DynastyProcessValueImporter.SOURCE_1QB,
            error.getMessage());
    }

    private Fixture fixture(LeagueValueFormat format) throws Exception {
        Database database = new Database(tempDir.resolve("trade.db"));
        database.initialize();
        LeagueRepository leagues = new LeagueRepository(database);
        LeagueValueFormatRepository formats = new LeagueValueFormatRepository(database);
        TeamRepository teams = new TeamRepository(database);
        PlayerRepository players = new PlayerRepository(database);
        RosterRepository rosters = new RosterRepository(database);
        PlayerValueRepository values = new PlayerValueRepository(database);

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
        return new Fixture(database, league, qb, wr, players, values);
    }

    private record Fixture(Database database, League league, Player qb, Player wr,
                           PlayerRepository players, PlayerValueRepository values) {}
}
