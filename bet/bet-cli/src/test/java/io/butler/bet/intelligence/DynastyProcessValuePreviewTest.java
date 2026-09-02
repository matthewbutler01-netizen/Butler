package io.butler.bet.intelligence;

import io.butler.bet.data.Database;
import io.butler.bet.data.PlayerRepository;
import io.butler.bet.data.PlayerValueRepository;
import io.butler.bet.domain.Player;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;

class DynastyProcessValuePreviewTest {
    @TempDir
    Path tempDir;

    @Test
    void previewReturnsImportDiagnosticsWithoutPersistingSnapshots() throws Exception {
        Database database = new Database(tempDir.resolve("preview.db"));
        database.initialize();
        PlayerRepository players = new PlayerRepository(database);
        PlayerValueRepository values = new PlayerValueRepository(database);
        players.save(new Player("p1", "100", "Matched Player", "WR", "KC"));
        players.save(new Player("p2", "999", "Unmatched Player", "RB", "DET"));

        String ids = "fantasypros_id,sleeper_id,name,position,team\n"
            + "19788,100,Matched Player,WR,KC\n";
        String providerValues = "player,pos,team,value_1qb,value_2qb,scrape_date,fp_id\n"
            + "Matched Player,WR,KC,10232,9055,2026-08-28,19788\n";

        var result = new DynastyProcessValueImporter(database).previewCsv(providerValues, ids);

        assertEquals(2, result.eligiblePlayers());
        assertEquals(1, result.matchedPlayers());
        assertEquals(1, result.unmatchedPlayers());
        assertEquals(2, result.valuesImported());
        assertEquals(1, result.diagnostics().providerRowsMapped());
        assertEquals(0, values.findByPlayerId("p1").size());
        assertEquals(0, values.findByPlayerId("p2").size());
    }

    @Test
    void regularImportStillPersistsAfterPreviewRefactor() throws Exception {
        Database database = new Database(tempDir.resolve("import.db"));
        database.initialize();
        PlayerRepository players = new PlayerRepository(database);
        PlayerValueRepository values = new PlayerValueRepository(database);
        players.save(new Player("p1", "100", "Matched Player", "WR", "KC"));

        String ids = "fantasypros_id,sleeper_id,name,position,team\n"
            + "19788,100,Matched Player,WR,KC\n";
        String providerValues = "player,pos,team,value_1qb,value_2qb,scrape_date,fp_id\n"
            + "Matched Player,WR,KC,10232,9055,2026-08-28,19788\n";

        var result = new DynastyProcessValueImporter(database).importCsv(providerValues, ids);

        assertEquals(2, result.valuesImported());
        assertEquals(1, values.findByPlayerIdAndSource("p1", DynastyProcessValueImporter.SOURCE_1QB).size());
        assertEquals(1, values.findByPlayerIdAndSource("p1", DynastyProcessValueImporter.SOURCE_2QB).size());
    }
}
