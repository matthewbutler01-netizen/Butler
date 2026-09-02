package io.butler.bet.intelligence;

import io.butler.bet.data.Database;
import io.butler.bet.data.LeagueRepository;
import io.butler.bet.data.LeagueValueFormatRepository;
import io.butler.bet.domain.League;
import io.butler.bet.domain.LeagueValueFormat;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class LeagueValueSourceResolverTest {
    @TempDir
    Path tempDir;

    @Test
    void resolvesDynastyProcessSourceFromPersistedLeagueFormat() throws Exception {
        Database database = database();
        League league = new League("league", "sleeper", "League");
        new LeagueRepository(database).save(league);
        LeagueValueFormatRepository formats = new LeagueValueFormatRepository(database);
        LeagueValueSourceResolver resolver = new LeagueValueSourceResolver(database);

        formats.save(league.getId(), LeagueValueFormat.ONE_QB);
        assertEquals(DynastyProcessValueImporter.SOURCE_1QB, resolver.resolve(league.getId()));

        formats.save(league.getId(), LeagueValueFormat.TWO_QB);
        assertEquals(DynastyProcessValueImporter.SOURCE_2QB, resolver.resolve(league.getId()));
    }

    @Test
    void explicitOverrideWinsWithoutChangingStoredFormat() throws Exception {
        Database database = database();
        League league = new League("league", null, "League");
        new LeagueRepository(database).save(league);
        new LeagueValueFormatRepository(database).save(league.getId(), LeagueValueFormat.TWO_QB);

        LeagueValueSourceResolver resolver = new LeagueValueSourceResolver(database);
        assertEquals("custom-source", resolver.resolve(league.getId(), "  custom-source  "));
        assertEquals(DynastyProcessValueImporter.SOURCE_2QB, resolver.resolve(league.getId()));
    }

    @Test
    void refusesToGuessWhenFormatIsMissingOrUnknown() throws Exception {
        Database database = database();
        League league = new League("league", null, "League");
        new LeagueRepository(database).save(league);
        LeagueValueSourceResolver resolver = new LeagueValueSourceResolver(database);

        assertThrows(IllegalArgumentException.class, () -> resolver.resolve(league.getId()));

        new LeagueValueFormatRepository(database).save(league.getId(), LeagueValueFormat.UNKNOWN);
        assertThrows(IllegalArgumentException.class, () -> resolver.resolve(league.getId()));
    }

    private Database database() throws Exception {
        Database database = new Database(tempDir.resolve("resolver.db"));
        database.initialize();
        return database;
    }
}
