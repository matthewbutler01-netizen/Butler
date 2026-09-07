package io.butler.bet.sleeper;

import io.butler.bet.data.Database;
import io.butler.bet.data.LeagueRepository;
import io.butler.bet.domain.League;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.math.BigDecimal;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SleeperSeasonProviderPointsDuplicateRosterDiagnosticTest {
    @TempDir Path tempDir;

    @Test
    void reportsCrossRosterDuplicateWithRawSourceRowsWithoutChoosingCanonicalRoster() throws Exception {
        Database database = initialized();
        String week1 = """
            [
              {"roster_id":2,"matchup_id":1,"players":["p1","p2"],"starters":[],"players_points":{"p1":4.5,"p2":2.0}},
              {"roster_id":1,"matchup_id":1,"players":["p1"],"starters":["p1"],"players_points":{"p1":10.0}}
            ]
            """;

        var report = new SleeperSeasonProviderPointsDuplicateRosterDiagnostic(database, source(week1))
            .diagnose("l1", 2025);

        assertEquals(1, report.duplicates().size());
        var duplicate = report.duplicates().getFirst();
        assertEquals(1, duplicate.week());
        assertEquals("p1", duplicate.playerId());
        assertEquals(SleeperSeasonProviderPointsDuplicateRosterDiagnostic.DuplicateKind.CROSS_ROSTER,
            duplicate.kind());
        assertEquals(List.of(1, 2), duplicate.rows().stream().map(row -> row.rosterId()).toList());
        assertTrue(duplicate.rows().getFirst().inStarters());
        assertEquals(new BigDecimal("10.0"), duplicate.rows().getFirst().providerPoints());
        assertEquals(new BigDecimal("4.5"), duplicate.rows().get(1).providerPoints());
    }

    @Test
    void reportsIntraRosterDuplicateSeparately() throws Exception {
        Database database = initialized();
        String week1 = """
            [{"roster_id":1,"matchup_id":1,"players":["p1","p1"],"starters":["p1"],"players_points":{"p1":8.0}}]
            """;

        var report = new SleeperSeasonProviderPointsDuplicateRosterDiagnostic(database, source(week1))
            .diagnose("l1", 2025);

        assertEquals(1, report.duplicates().size());
        var duplicate = report.duplicates().getFirst();
        assertEquals(SleeperSeasonProviderPointsDuplicateRosterDiagnostic.DuplicateKind.INTRA_ROSTER,
            duplicate.kind());
        assertEquals(2, duplicate.rows().getFirst().playerOccurrences());
        assertEquals(1, duplicate.rows().getFirst().starterOccurrences());
    }

    @Test
    void cleanWeeksProduceNoDuplicateObservations() throws Exception {
        Database database = initialized();
        String week1 = """
            [
              {"roster_id":1,"matchup_id":1,"players":["p1"],"starters":["p1"],"players_points":{"p1":10.0}},
              {"roster_id":2,"matchup_id":1,"players":["p2"],"starters":["p2"],"players_points":{"p2":9.0}}
            ]
            """;

        var report = new SleeperSeasonProviderPointsDuplicateRosterDiagnostic(database, source(week1))
            .diagnose("l1", 2025);

        assertTrue(report.duplicates().isEmpty());
    }

    private Database initialized() throws Exception {
        Database database = new Database(tempDir.resolve("duplicate-roster-diagnostic.db"));
        database.initialize();
        new LeagueRepository(database).save(new League("l1", "cur", "League", 2026));
        return database;
    }

    private static SleeperSeasonProviderPointsDuplicateRosterDiagnostic.Source source(String week1) {
        return new SleeperSeasonProviderPointsDuplicateRosterDiagnostic.Source() {
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
}
