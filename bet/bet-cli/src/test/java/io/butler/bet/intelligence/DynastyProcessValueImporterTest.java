package io.butler.bet.intelligence;

import io.butler.bet.data.Database;
import io.butler.bet.data.PlayerRepository;
import io.butler.bet.data.PlayerValueRepository;
import io.butler.bet.domain.Player;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.LocalDate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class DynastyProcessValueImporterTest {
    @TempDir
    Path tempDir;

    @Test
    void mapsFantasyProsIdsToSleeperPlayersAndImportsBothFormats() throws Exception {
        Database database = database();
        PlayerRepository players = new PlayerRepository(database);
        PlayerValueRepository values = new PlayerValueRepository(database);
        Player matched = new Player("p1", "100", "Matched Player", "WR", "KC");
        Player unmatched = new Player("p2", "999", "Unmatched Player", "RB", "DET");
        Player manual = Player.create("Manual Player", "QB", "BUF");
        players.save(matched);
        players.save(unmatched);
        players.save(manual);

        String ids = "fantasypros_id,sleeper_id,name\n19788,100,Matched Player\n20000,200,Other Player\n";
        String providerValues = "player,pos,team,value_1qb,value_2qb,scrape_date,fp_id\n"
            + "\"Matched, Player\",WR,KC,10232,9055,2026-08-28,19788\n"
            + "Other Player,RB,DAL,5000,4500,2026-08-28,20000\n";

        var result = new DynastyProcessValueImporter(database).importCsv(providerValues, ids);

        assertEquals(LocalDate.of(2026, 8, 28), result.asOfDate());
        assertEquals(2, result.eligiblePlayers());
        assertEquals(1, result.matchedPlayers());
        assertEquals(1, result.unmatchedPlayers());
        assertEquals(2, result.valuesImported());
        assertEquals("999", result.unmatched().get(0).sleeperId());
        assertEquals("Unmatched Player", result.unmatched().get(0).playerName());

        var oneQb = values.findByPlayerIdAndSource("p1", DynastyProcessValueImporter.SOURCE_1QB);
        var twoQb = values.findByPlayerIdAndSource("p1", DynastyProcessValueImporter.SOURCE_2QB);
        assertEquals(1, oneQb.size());
        assertEquals(10232.0, oneQb.get(0).getValue());
        assertEquals(LocalDate.of(2026, 8, 28), oneQb.get(0).getAsOfDate());
        assertEquals(1, twoQb.size());
        assertEquals(9055.0, twoQb.get(0).getValue());
        assertEquals(0, values.findByPlayerId("p2").size());
    }

    @Test
    void acceptsNumericIdsExportedWithDecimalSuffix() throws Exception {
        Database database = database();
        PlayerRepository players = new PlayerRepository(database);
        players.save(new Player("p1", "100", "Player", "WR", "KC"));

        String ids = "fantasypros_id,sleeper_id\n19788.0,100.0\n";
        String providerValues = "value_1qb,value_2qb,scrape_date,fp_id\n10,20,2026-08-28,19788\n";

        var result = new DynastyProcessValueImporter(database).importCsv(providerValues, ids);
        assertEquals(1, result.matchedPlayers());
        assertEquals(2, result.valuesImported());
    }

    @Test
    void rejectsMixedProviderSnapshotDatesBeforePersistingAnything() throws Exception {
        Database database = database();
        PlayerRepository players = new PlayerRepository(database);
        PlayerValueRepository values = new PlayerValueRepository(database);
        players.save(new Player("p1", "100", "One", "WR", "KC"));
        players.save(new Player("p2", "200", "Two", "RB", "DET"));

        String ids = "fantasypros_id,sleeper_id\n1,100\n2,200\n";
        String providerValues = "value_1qb,value_2qb,scrape_date,fp_id\n"
            + "10,20,2026-08-28,1\n"
            + "11,21,2026-08-29,2\n";

        assertThrows(IllegalArgumentException.class,
            () -> new DynastyProcessValueImporter(database).importCsv(providerValues, ids));
        assertEquals(0, values.findByPlayerId("p1").size());
        assertEquals(0, values.findByPlayerId("p2").size());
    }

    @Test
    void rejectsAmbiguousFantasyProsMapping() throws Exception {
        Database database = database();
        String ids = "fantasypros_id,sleeper_id\n19788,100\n19788,101\n";
        String providerValues = "value_1qb,value_2qb,scrape_date,fp_id\n10,20,2026-08-28,19788\n";

        assertThrows(IllegalArgumentException.class,
            () -> new DynastyProcessValueImporter(database).importCsv(providerValues, ids));
    }

    private Database database() throws Exception {
        Database database = new Database(tempDir.resolve("dynastyprocess.db"));
        database.initialize();
        return database;
    }
}
