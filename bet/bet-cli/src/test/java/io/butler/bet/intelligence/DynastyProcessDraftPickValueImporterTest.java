package io.butler.bet.intelligence;

import io.butler.bet.data.Database;
import io.butler.bet.data.DraftPickRepository;
import io.butler.bet.data.DraftPickValueRepository;
import io.butler.bet.data.LeagueRepository;
import io.butler.bet.data.TeamRepository;
import io.butler.bet.domain.DraftPick;
import io.butler.bet.domain.League;
import io.butler.bet.domain.Team;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DynastyProcessDraftPickValueImporterTest {
    @TempDir Path tempDir;

    @Test
    void importsGenericOneQbAndTwoQbValuesForPersistedLeaguePicks() throws Exception {
        Fixture fixture = fixture();
        DraftPick first = DraftPick.create(fixture.league.getId(), 2027, 1, fixture.team1.getId(), fixture.team2.getId());
        DraftPick second = DraftPick.create(fixture.league.getId(), 2027, 2, fixture.team2.getId(), fixture.team2.getId());
        fixture.picks.save(first);
        fixture.picks.save(second);

        var catalog = new DynastyProcessDraftPickCatalog.Catalog(
            LocalDate.of(2026, 8, 28),
            List.of(
                new DynastyProcessDraftPickCatalog.PickValue(2027, 1, "2027 1st", 1782, 2028, LocalDate.of(2026, 8, 28)),
                new DynastyProcessDraftPickCatalog.PickValue(2027, 2, "2027 2nd", 900, 1050, LocalDate.of(2026, 8, 28))));

        var result = new DynastyProcessDraftPickValueImporter(fixture.database).importCatalog(fixture.league.getId(), catalog);

        assertEquals(2, result.draftPicks());
        assertEquals(2, result.matchedPicks());
        assertEquals(0, result.missingPicks());
        assertEquals(4, result.valuesImported());
        assertEquals(100.0, result.coveragePercent());
        assertEquals(1782.0, fixture.values.findLatestByDraftPickIdAndSource(first.getId(), DynastyProcessValueImporter.SOURCE_1QB).orElseThrow().getValue());
        assertEquals(2028.0, fixture.values.findLatestByDraftPickIdAndSource(first.getId(), DynastyProcessValueImporter.SOURCE_2QB).orElseThrow().getValue());
        assertEquals(900.0, fixture.values.findLatestByDraftPickIdAndSource(second.getId(), DynastyProcessValueImporter.SOURCE_1QB).orElseThrow().getValue());
    }

    @Test
    void reportsUnsupportedPickWithoutGuessingAndLeavesItUnvalued() throws Exception {
        Fixture fixture = fixture();
        DraftPick supported = DraftPick.create(fixture.league.getId(), 2027, 1, fixture.team1.getId(), fixture.team1.getId());
        DraftPick unsupported = DraftPick.create(fixture.league.getId(), 2029, 4, fixture.team2.getId(), fixture.team1.getId());
        fixture.picks.save(supported);
        fixture.picks.save(unsupported);

        var catalog = new DynastyProcessDraftPickCatalog.Catalog(
            LocalDate.of(2026, 8, 28),
            List.of(new DynastyProcessDraftPickCatalog.PickValue(
                2027, 1, "2027 1st", 1782, 2028, LocalDate.of(2026, 8, 28))));

        var result = new DynastyProcessDraftPickValueImporter(fixture.database).importCatalog(fixture.league.getId(), catalog);

        assertEquals(2, result.draftPicks());
        assertEquals(1, result.matchedPicks());
        assertEquals(1, result.missingPicks());
        assertEquals(50.0, result.coveragePercent());
        assertEquals(unsupported.getId(), result.missing().get(0).draftPickId());
        assertTrue(fixture.values.findLatestByDraftPickIdAndSource(
            unsupported.getId(), DynastyProcessValueImporter.SOURCE_1QB).isEmpty());
    }

    @Test
    void sameProviderDateIsIdempotentPerPickAndSource() throws Exception {
        Fixture fixture = fixture();
        DraftPick pick = DraftPick.create(fixture.league.getId(), 2027, 1, fixture.team1.getId(), fixture.team1.getId());
        fixture.picks.save(pick);

        var firstCatalog = new DynastyProcessDraftPickCatalog.Catalog(
            LocalDate.of(2026, 8, 28),
            List.of(new DynastyProcessDraftPickCatalog.PickValue(
                2027, 1, "2027 1st", 1700, 2000, LocalDate.of(2026, 8, 28))));
        var updatedCatalog = new DynastyProcessDraftPickCatalog.Catalog(
            LocalDate.of(2026, 8, 28),
            List.of(new DynastyProcessDraftPickCatalog.PickValue(
                2027, 1, "2027 1st", 1750, 2050, LocalDate.of(2026, 8, 28))));

        var importer = new DynastyProcessDraftPickValueImporter(fixture.database);
        importer.importCatalog(fixture.league.getId(), firstCatalog);
        importer.importCatalog(fixture.league.getId(), updatedCatalog);

        var history = fixture.values.findByDraftPickIdAndSource(pick.getId(), DynastyProcessValueImporter.SOURCE_1QB);
        assertEquals(1, history.size());
        assertEquals(1750.0, history.get(0).getValue());
    }

    @Test
    void leagueIsolationOnlyImportsRequestedLeague() throws Exception {
        Fixture fixture = fixture();
        DraftPick target = DraftPick.create(fixture.league.getId(), 2027, 1, fixture.team1.getId(), fixture.team1.getId());
        fixture.picks.save(target);

        League otherLeague = new League(UUID.randomUUID().toString(), "other-ext", "Other");
        new LeagueRepository(fixture.database).save(otherLeague);
        Team otherTeam = new Team(UUID.randomUUID().toString(), "other-team", otherLeague.getId(), "Other Team");
        new TeamRepository(fixture.database).save(otherTeam);
        DraftPick outside = DraftPick.create(otherLeague.getId(), 2027, 1, otherTeam.getId(), otherTeam.getId());
        fixture.picks.save(outside);

        var catalog = new DynastyProcessDraftPickCatalog.Catalog(
            LocalDate.of(2026, 8, 28),
            List.of(new DynastyProcessDraftPickCatalog.PickValue(
                2027, 1, "2027 1st", 1782, 2028, LocalDate.of(2026, 8, 28))));

        new DynastyProcessDraftPickValueImporter(fixture.database).importCatalog(fixture.league.getId(), catalog);

        assertTrue(fixture.values.findLatestByDraftPickIdAndSource(
            outside.getId(), DynastyProcessValueImporter.SOURCE_1QB).isEmpty());
    }

    private Fixture fixture() throws Exception {
        Database database = new Database(tempDir.resolve("draft-pick-values.db"));
        database.initialize();
        League league = new League(UUID.randomUUID().toString(), "league-ext", "League");
        Team team1 = new Team(UUID.randomUUID().toString(), "1", league.getId(), "One");
        Team team2 = new Team(UUID.randomUUID().toString(), "2", league.getId(), "Two");
        new LeagueRepository(database).save(league);
        TeamRepository teams = new TeamRepository(database);
        teams.save(team1);
        teams.save(team2);
        return new Fixture(database, league, team1, team2,
            new DraftPickRepository(database), new DraftPickValueRepository(database));
    }

    private record Fixture(Database database, League league, Team team1, Team team2,
                           DraftPickRepository picks, DraftPickValueRepository values) {}
}
