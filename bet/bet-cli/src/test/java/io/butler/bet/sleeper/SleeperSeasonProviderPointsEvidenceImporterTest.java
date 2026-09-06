package io.butler.bet.sleeper;

import io.butler.bet.data.Database;
import io.butler.bet.data.LeagueRepository;
import io.butler.bet.data.ProviderPlayerWeekPointsEvidenceRepository;
import io.butler.bet.data.TeamRepository;
import io.butler.bet.domain.League;
import io.butler.bet.domain.Team;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.math.BigDecimal;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SleeperSeasonProviderPointsEvidenceImporterTest {
    @TempDir Path tempDir;
    private static final LocalDate AS_OF = LocalDate.of(2026, 9, 6);

    @Test
    void persistsCompleteSeasonEvidenceIncludingBenchDefensePlaceholderAndExactDecimal() throws Exception {
        Database database = initialized("complete.db");
        String week1 = """
            [
              {"roster_id":1,"players":["p1","p2","CHI","0"],"starters":["p1","CHI","0"],
               "players_points":{"p1":10.12345678901234567890123456789,"p2":-1.25,"CHI":7.00}},
              {"roster_id":2,"players":["p3"],"starters":["p3"],
               "players_points":{"p3":3.5}}
            ]
            """;

        var result = importer(database, week1).syncSeason("l1", 2025);

        assertEquals(SleeperSeasonProviderPointsEvidenceImporter.ImportState.PERSISTED_RECONCILED, result.state());
        assertEquals(1, result.populatedWeeks());
        assertEquals(4, result.rowsPersisted());
        assertEquals(4, result.rowsReadBack());
        assertEquals(List.of("CHI"), result.defenseIdentities());
        assertEquals("sleeper", result.source());
        assertEquals("matchup.players_points", result.sourceSurface());

        var persisted = new ProviderPlayerWeekPointsEvidenceRepository(database)
            .findSnapshot("l1", 2025, "sleeper", AS_OF);
        assertEquals(4, persisted.size());
        var p1 = persisted.stream().filter(row -> row.providerPlayerId().equals("p1")).findFirst().orElseThrow();
        assertEquals(new BigDecimal("10.12345678901234567890123456789"), p1.points());
        var bench = persisted.stream().filter(row -> row.providerPlayerId().equals("p2")).findFirst().orElseThrow();
        assertEquals(new BigDecimal("-1.25"), bench.points());
        var defense = persisted.stream().filter(row -> row.providerPlayerId().equals("CHI")).findFirst().orElseThrow();
        assertEquals("1", defense.providerRosterId());
        assertEquals("hist", defense.providerLeagueId());
    }

    @Test
    void missingBenchPointFailsBeforeAnyWrite() throws Exception {
        Database database = initialized("missing-bench.db");
        String week1 = """
            [
              {"roster_id":1,"players":["p1","p2"],"starters":["p1"],"players_points":{"p1":10}},
              {"roster_id":2,"players":["p3"],"starters":["p3"],"players_points":{"p3":3}}
            ]
            """;

        var error = assertThrows(IllegalStateException.class,
            () -> importer(database, week1).syncSeason("l1", 2025));

        assertTrue(error.getMessage().contains("p2"));
        assertNoRows(database);
    }

    @Test
    void starterNotInPlayersFailsBeforeAnyWrite() throws Exception {
        Database database = initialized("starter-mismatch.db");
        String week1 = """
            [
              {"roster_id":1,"players":["p1"],"starters":["p1","p2"],"players_points":{"p1":10,"p2":4}},
              {"roster_id":2,"players":["p3"],"starters":["p3"],"players_points":{"p3":3}}
            ]
            """;

        var error = assertThrows(IllegalStateException.class,
            () -> importer(database, week1).syncSeason("l1", 2025));

        assertTrue(error.getMessage().contains("not present in players"));
        assertNoRows(database);
    }

    @Test
    void duplicateProviderIdentityAcrossRostersFailsBeforeAnyWrite() throws Exception {
        Database database = initialized("duplicate.db");
        String week1 = """
            [
              {"roster_id":1,"players":["p1"],"starters":["p1"],"players_points":{"p1":10}},
              {"roster_id":2,"players":["p1"],"starters":[],"players_points":{"p1":10}}
            ]
            """;

        var error = assertThrows(IllegalStateException.class,
            () -> importer(database, week1).syncSeason("l1", 2025));

        assertTrue(error.getMessage().contains("multiple rosters"));
        assertNoRows(database);
    }

    @Test
    void rosterIdentityMismatchFailsBeforeAnyWrite() throws Exception {
        Database database = initialized("roster-mismatch.db");
        String week1 = """
            [
              {"roster_id":1,"players":["p1"],"starters":["p1"],"players_points":{"p1":10}},
              {"roster_id":3,"players":["p3"],"starters":["p3"],"players_points":{"p3":3}}
            ]
            """;

        var error = assertThrows(IllegalStateException.class,
            () -> importer(database, week1).syncSeason("l1", 2025));

        assertTrue(error.getMessage().contains("unknown roster_id 3"));
        assertNoRows(database);
    }

    private Database initialized(String name) throws Exception {
        Database database = new Database(tempDir.resolve(name));
        database.initialize();
        new LeagueRepository(database).save(new League("l1", "cur", "League", 2026));
        new TeamRepository(database).save(new Team("t1", "1", "l1", "One"));
        new TeamRepository(database).save(new Team("t2", "2", "l1", "Two"));
        return database;
    }

    private static SleeperSeasonProviderPointsEvidenceImporter importer(Database database, String week1) {
        return new SleeperSeasonProviderPointsEvidenceImporter(database, source(week1), AS_OF);
    }

    private static SleeperSeasonProviderPointsEvidenceImporter.Source source(String week1) {
        return new SleeperSeasonProviderPointsEvidenceImporter.Source() {
            @Override
            public SleeperLeagueLineageResolver.Lineage resolveLineage(String currentSleeperLeagueId) {
                return new SleeperLeagueLineageResolver.Lineage(
                    "cur", "hist", 2026,
                    List.of(
                        new SleeperLeagueLineageResolver.LeagueLink("cur", 2026, "hist"),
                        new SleeperLeagueLineageResolver.LeagueLink("hist", 2025, null)));
            }

            @Override
            public String matchups(String sleeperLeagueId, int week) {
                return week == 1 ? week1 : "[]";
            }
        };
    }

    private static void assertNoRows(Database database) throws Exception {
        assertTrue(new ProviderPlayerWeekPointsEvidenceRepository(database)
            .findLatestByLeagueSeason("l1", 2025, "sleeper").isEmpty());
    }
}
