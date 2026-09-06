package io.butler.bet.sleeper;

import io.butler.bet.data.Database;
import io.butler.bet.data.LeagueRepository;
import io.butler.bet.domain.League;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SleeperSeasonProviderPointsCoverageAuditTest {
    @TempDir Path tempDir;

    @Test
    void provesRosterWidePointsIncludingBenchDefensePlaceholderAndEmptyWeeks() throws Exception {
        Database database = initialized();
        String week1 = """
            [{"roster_id":1,"players":["p1","p2","CHI","0"],"starters":["p1","CHI","0"],
              "players_points":{"p1":12.3,"p2":4.5,"CHI":7.0}}]
            """;
        var audit = new SleeperSeasonProviderPointsCoverageAudit(database, source(week1));

        var report = audit.audit("l1", 2025);

        assertEquals(SleeperSeasonProviderPointsCoverageAudit.AuditState.PROOF_READY_ROSTER_WIDE_PROVIDER_POINTS,
            report.state());
        assertEquals(1, report.populatedWeeks());
        assertEquals(3, report.rosterPlayerObservations());
        assertEquals(3, report.rosterPlayerPointsPresent());
        assertEquals(2, report.starterObservations());
        assertEquals(2, report.starterPointsPresent());
        assertEquals(List.of("CHI"), report.defenseIdentities());
        assertTrue(report.weeks().getFirst().complete());
        assertFalse(report.weeks().get(1).populated());
    }

    @Test
    void blocksWhenBenchPlayerHasNoProviderPoints() throws Exception {
        Database database = initialized();
        String week1 = """
            [{"roster_id":1,"players":["p1","p2"],"starters":["p1"],
              "players_points":{"p1":10.0}}]
            """;
        var report = new SleeperSeasonProviderPointsCoverageAudit(database, source(week1)).audit("l1", 2025);

        assertEquals(SleeperSeasonProviderPointsCoverageAudit.AuditState.PROOF_BLOCKED, report.state());
        assertEquals(1, report.weeks().getFirst().missingRosterPointIdentities().size());
        assertEquals("p2", report.weeks().getFirst().missingRosterPointIdentities().getFirst());
        assertTrue(report.blockers().stream().anyMatch(value -> value.contains("roster-player")));
    }

    @Test
    void blocksStarterThatIsNotInPlayersEvenWhenPointsExist() throws Exception {
        Database database = initialized();
        String week1 = """
            [{"roster_id":1,"players":["p1"],"starters":["p1","p2"],
              "players_points":{"p1":10.0,"p2":5.0}}]
            """;
        var report = new SleeperSeasonProviderPointsCoverageAudit(database, source(week1)).audit("l1", 2025);

        assertEquals(SleeperSeasonProviderPointsCoverageAudit.AuditState.PROOF_BLOCKED, report.state());
        assertEquals(List.of("p2"), report.weeks().getFirst().starterNotInPlayersIdentities());
        assertTrue(report.blockers().stream().anyMatch(value -> value.contains("not present")));
    }

    @Test
    void blocksDuplicateRosterIdentityAcrossMatchupRows() throws Exception {
        Database database = initialized();
        String week1 = """
            [
              {"roster_id":1,"players":["p1"],"starters":["p1"],"players_points":{"p1":10.0}},
              {"roster_id":2,"players":["p1"],"starters":[],"players_points":{"p1":10.0}}
            ]
            """;
        var report = new SleeperSeasonProviderPointsCoverageAudit(database, source(week1)).audit("l1", 2025);

        assertEquals(SleeperSeasonProviderPointsCoverageAudit.AuditState.PROOF_BLOCKED, report.state());
        assertEquals(List.of("p1"), report.weeks().getFirst().duplicateRosterIdentities());
    }

    private Database initialized() throws Exception {
        Database database = new Database(tempDir.resolve("provider-points-audit.db"));
        database.initialize();
        new LeagueRepository(database).save(new League("l1", "cur", "League", 2026));
        return database;
    }

    private static SleeperSeasonProviderPointsCoverageAudit.Source source(String week1) {
        return new SleeperSeasonProviderPointsCoverageAudit.Source() {
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
