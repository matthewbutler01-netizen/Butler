package io.butler.bet.sleeper;

import io.butler.bet.data.Database;
import io.butler.bet.data.DraftPickRepository;
import io.butler.bet.data.LeagueRepository;
import io.butler.bet.data.TeamRepository;
import io.butler.bet.domain.League;
import io.butler.bet.domain.Team;
import io.butler.bet.intelligence.DynastyProcessDraftPickCatalog;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SleeperDraftPickImporterTest {
    @TempDir Path tempDir;

    @Test
    void importsOnlySupportedCoordinatesAppliesTradesAndPreservesIds() throws Exception {
        Fixture fixture = fixture();
        SleeperDraftPickImporter importer = new SleeperDraftPickImporter(new EmptyGateway(), fixture.database());
        DraftPickRepository picks = new DraftPickRepository(fixture.database());

        var league = sourceLeague();
        var catalog = catalog(
            value(2025, 1),
            value(2026, 1),
            value(2026, 2),
            value(2026, 3),
            value(2026, 4),
            value(2027, 1),
            value(2027, 2));
        var traded = List.of(
            new SleeperJsonParser.SleeperTradedPick(2026, 1, 1, 1, 2),
            new SleeperJsonParser.SleeperTradedPick(2026, 4, 1, 1, 2));

        var first = importer.importLeague(league, traded, catalog);

        assertEquals(5, first.supportedCoordinates());
        assertEquals(2, first.teams());
        assertEquals(10, first.picksImported());
        assertEquals(1, first.tradedOwnershipApplied());
        assertEquals(1, first.unsupportedTradedPicks());
        assertEquals(0, first.stalePicksRemoved());

        var moved = picks.findByLeagueSeasonRoundAndOriginalTeam(
            fixture.league().getId(), 2026, 1, fixture.team1().getId()).orElseThrow();
        assertEquals(fixture.team2().getId(), moved.getOwnerTeamId());
        String movedId = moved.getId();

        var untouched = picks.findByLeagueSeasonRoundAndOriginalTeam(
            fixture.league().getId(), 2026, 1, fixture.team2().getId()).orElseThrow();
        assertEquals(fixture.team2().getId(), untouched.getOwnerTeamId());

        var secondCatalog = catalog(value(2026, 1), value(2026, 2), value(2027, 1));
        var second = importer.importLeague(league, List.of(), secondCatalog);

        assertEquals(3, second.supportedCoordinates());
        assertEquals(6, second.picksImported());
        assertEquals(4, second.stalePicksRemoved());
        var restored = picks.findByLeagueSeasonRoundAndOriginalTeam(
            fixture.league().getId(), 2026, 1, fixture.team1().getId()).orElseThrow();
        assertEquals(movedId, restored.getId());
        assertEquals(fixture.team1().getId(), restored.getOwnerTeamId());
        assertTrue(picks.findByLeagueSeasonRoundAndOriginalTeam(
            fixture.league().getId(), 2026, 3, fixture.team1().getId()).isEmpty());
    }

    @Test
    void rejectsUnknownRosterReferenceBeforeChangingPersistedOwnership() throws Exception {
        Fixture fixture = fixture();
        SleeperDraftPickImporter importer = new SleeperDraftPickImporter(new EmptyGateway(), fixture.database());
        DraftPickRepository picks = new DraftPickRepository(fixture.database());
        var catalog = catalog(value(2026, 1));

        importer.importLeague(sourceLeague(), List.of(), catalog);
        var before = picks.findByLeagueSeasonRoundAndOriginalTeam(
            fixture.league().getId(), 2026, 1, fixture.team1().getId()).orElseThrow();

        assertThrows(IllegalArgumentException.class, () -> importer.importLeague(
            sourceLeague(),
            List.of(new SleeperJsonParser.SleeperTradedPick(2026, 1, 1, 1, 99)),
            catalog));

        var after = picks.findByLeagueSeasonRoundAndOriginalTeam(
            fixture.league().getId(), 2026, 1, fixture.team1().getId()).orElseThrow();
        assertEquals(before.getId(), after.getId());
        assertEquals(before.getOwnerTeamId(), after.getOwnerTeamId());
    }

    @Test
    void rejectsDuplicateSupportedTradeRecord() throws Exception {
        Fixture fixture = fixture();
        SleeperDraftPickImporter importer = new SleeperDraftPickImporter(new EmptyGateway(), fixture.database());
        var catalog = catalog(value(2026, 1));

        assertThrows(IllegalArgumentException.class, () -> importer.importLeague(
            sourceLeague(),
            List.of(
                new SleeperJsonParser.SleeperTradedPick(2026, 1, 1, 1, 2),
                new SleeperJsonParser.SleeperTradedPick(2026, 1, 1, 2, 1)),
            catalog));
    }

    @Test
    void requiresImportedLeagueAndUsableSleeperDraftMetadata() throws Exception {
        Database database = database();
        SleeperDraftPickImporter importer = new SleeperDraftPickImporter(new EmptyGateway(), database);
        var catalog = catalog(value(2026, 1));

        assertThrows(IllegalArgumentException.class, () -> importer.importLeague(
            new SleeperJsonParser.SleeperLeague("missing", "Missing", List.of(), 2026, 2, 3),
            List.of(), catalog));

        Fixture fixture = fixture(database);
        assertThrows(IllegalArgumentException.class, () -> importer.importLeague(
            new SleeperJsonParser.SleeperLeague("L1", "League", List.of(), 0, 2, 3),
            List.of(), catalog));
        assertThrows(IllegalArgumentException.class, () -> importer.importLeague(
            new SleeperJsonParser.SleeperLeague("L1", "League", List.of(), 2026, 2, 0),
            List.of(), catalog));
        assertEquals(0, new DraftPickRepository(fixture.database()).findByLeagueId(fixture.league().getId()).size());
    }

    private Fixture fixture() throws Exception {
        return fixture(database());
    }

    private Fixture fixture(Database database) throws Exception {
        League league = new League("league-1", "L1", "League");
        Team team1 = new Team("team-1", "1", league.getId(), "One");
        Team team2 = new Team("team-2", "2", league.getId(), "Two");
        new LeagueRepository(database).save(league);
        TeamRepository teams = new TeamRepository(database);
        teams.save(team1);
        teams.save(team2);
        return new Fixture(database, league, team1, team2);
    }

    private Database database() throws Exception {
        Database database = new Database(tempDir.resolve("butler-test-" + System.nanoTime() + ".db"));
        database.initialize();
        return database;
    }

    private static SleeperJsonParser.SleeperLeague sourceLeague() {
        return new SleeperJsonParser.SleeperLeague("L1", "League", List.of(), 2026, 2, 3);
    }

    private static DynastyProcessDraftPickCatalog.Catalog catalog(
        DynastyProcessDraftPickCatalog.PickValue... values) {
        return new DynastyProcessDraftPickCatalog.Catalog(
            LocalDate.of(2026, 8, 28), List.of(values));
    }

    private static DynastyProcessDraftPickCatalog.PickValue value(int season, int round) {
        return new DynastyProcessDraftPickCatalog.PickValue(
            season, round, season + " " + ordinal(round), 1000 - round, 900 - round,
            LocalDate.of(2026, 8, 28));
    }

    private static String ordinal(int round) {
        return switch (round) {
            case 1 -> "1st";
            case 2 -> "2nd";
            case 3 -> "3rd";
            default -> round + "th";
        };
    }

    private record Fixture(Database database, League league, Team team1, Team team2) {}

    private static final class EmptyGateway implements SleeperGateway {
        @Override public SleeperJsonParser.SleeperLeague fetchLeague(String leagueId) {
            throw new UnsupportedOperationException();
        }
        @Override public List<SleeperJsonParser.SleeperUser> fetchUsers(String leagueId) {
            return List.of();
        }
        @Override public List<SleeperJsonParser.SleeperRoster> fetchRosters(String leagueId) {
            return List.of();
        }
        @Override public Map<String, SleeperJsonParser.SleeperPlayer> fetchPlayers() {
            return Map.of();
        }
    }
}
