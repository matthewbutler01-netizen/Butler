package io.butler.bet.intelligence;

import io.butler.bet.data.Database;
import io.butler.bet.data.PlayerRepository;
import io.butler.bet.data.PlayerValueRepository;
import io.butler.bet.domain.Player;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class DynastyProcessStrictRefreshTest {
    @TempDir
    Path tempDir;

    @Test
    void blocksPartialProviderSnapshotWithoutPersisting() throws Exception {
        Database database = database("partial.db");
        new PlayerRepository(database).save(new Player("p1", "100", "Mapped", "WR", "KC"));
        PlayerValueRepository values = new PlayerValueRepository(database);

        String ids = "fantasypros_id,sleeper_id,name,position,team\n1,100,Mapped,WR,KC\n";
        String providerValues = "player,pos,team,value_1qb,value_2qb,scrape_date,fp_id\n"
            + "Mapped,WR,KC,10,20,2026-08-28,1\n"
            + "Unmapped,RB,DAL,30,40,2026-08-28,2\n";

        assertThrows(IllegalArgumentException.class,
            () -> new DynastyProcessStrictRefresh(database).importCsv(providerValues, ids));
        assertEquals(0, values.findByPlayerId("p1").size());
    }

    @Test
    void persistsReadyProviderSnapshot() throws Exception {
        Database database = database("ready.db");
        new PlayerRepository(database).save(new Player("p1", "100", "Mapped", "WR", "KC"));
        PlayerValueRepository values = new PlayerValueRepository(database);

        String ids = "fantasypros_id,sleeper_id,name,position,team\n1,100,Mapped,WR,KC\n";
        String providerValues = "player,pos,team,value_1qb,value_2qb,scrape_date,fp_id\n"
            + "Mapped,WR,KC,10,20,2026-08-28,1\n";

        var result = new DynastyProcessStrictRefresh(database).importCsv(providerValues, ids);

        assertEquals(2, result.valuesImported());
        assertEquals(DynastyProcessRefreshReadiness.Readiness.READY,
            DynastyProcessRefreshReadiness.classify(result.diagnostics()));
        assertEquals(1, values.findByPlayerIdAndSource("p1", DynastyProcessValueImporter.SOURCE_1QB).size());
        assertEquals(1, values.findByPlayerIdAndSource("p1", DynastyProcessValueImporter.SOURCE_2QB).size());
    }

    private Database database(String name) throws Exception {
        Database database = new Database(tempDir.resolve(name));
        database.initialize();
        return database;
    }
}
