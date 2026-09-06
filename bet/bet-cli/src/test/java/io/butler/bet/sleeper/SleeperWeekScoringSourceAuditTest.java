package io.butler.bet.sleeper;

import io.butler.bet.data.Database;
import io.butler.bet.data.LeagueConfigurationObservationRepository;
import io.butler.bet.data.LeagueRepository;
import io.butler.bet.domain.League;
import io.butler.bet.domain.LeagueConfigurationObservation;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SleeperWeekScoringSourceAuditTest {
    @TempDir Path tempDir;

    @Test
    void provesExactOnlyWhenEveryNonzeroKeyIsExplicitAndProviderPointsMatch() throws Exception {
        Database database = fixture(Map.of("pass_td", 4.0, "sack", 1.0), List.of("QB", "DEF"));
        var audit = new SleeperWeekScoringSourceAudit(database, source(
            "{\"qb1\":{\"pass_td\":2,\"sack\":0},\"DET\":{\"pass_td\":0,\"sack\":3}}",
            "[{\"starters\":[\"qb1\",\"DET\"],\"players_points\":{\"qb1\":8,\"DET\":3}}]"));

        var report = audit.audit("l1", 2025, 1);

        assertEquals(SleeperWeekScoringSourceAudit.AuditState.PROOF_READY_EXACT_FOR_OBSERVED_STARTERS,
            report.state());
        assertTrue(report.blockers().isEmpty());
        assertTrue(report.globallyAbsentNonzeroScoringKeys().isEmpty());
        assertEquals(2, report.starters().size());
        assertTrue(report.starters().stream().allMatch(starter ->
            starter.state() == SleeperWeekScoringSourceAudit.StarterState.EXACT_MATCH));
        assertTrue(report.starters().stream().anyMatch(starter ->
            starter.playerId().equals("DET") && starter.defenseIdentity()));
    }

    @Test
    void sparsePayloadDoesNotInferMissingScoringKeyAsZeroEvenWhenKnownDotProductMatches() throws Exception {
        Database database = fixture(
            Map.of("pass_td", 4.0, "bonus_pass_yd_300", 1.0), List.of("QB"));
        var audit = new SleeperWeekScoringSourceAudit(database, source(
            "{\"qb1\":{\"pass_td\":2}}",
            "[{\"starters\":[\"qb1\"],\"players_points\":{\"qb1\":8}}]"));

        var report = audit.audit("l1", 2025, 1);
        var starter = report.starters().getFirst();

        assertEquals(SleeperWeekScoringSourceAudit.AuditState.PROOF_BLOCKED, report.state());
        assertEquals(List.of("bonus_pass_yd_300"), report.globallyAbsentNonzeroScoringKeys());
        assertEquals(SleeperWeekScoringSourceAudit.StarterState.MISSING_SCORING_KEYS, starter.state());
        assertEquals(List.of("bonus_pass_yd_300"), starter.missingNonzeroScoringKeys());
        assertTrue(starter.knownKeyDotProductMatchesProvider());
        assertFalse(report.blockers().isEmpty());
    }

    @Test
    void missingDefenseIdentityBlocksProofInsteadOfFabricatingZeroProduction() throws Exception {
        Database database = fixture(Map.of("pass_td", 4.0), List.of("QB", "DEF"));
        var audit = new SleeperWeekScoringSourceAudit(database, source(
            "{\"qb1\":{\"pass_td\":2}}",
            "[{\"starters\":[\"qb1\",\"DET\"],\"players_points\":{\"qb1\":8,\"DET\":0}}]"));

        var report = audit.audit("l1", 2025, 1);
        var defense = report.starters().stream()
            .filter(starter -> starter.playerId().equals("DET"))
            .findFirst().orElseThrow();

        assertEquals(SleeperWeekScoringSourceAudit.AuditState.PROOF_BLOCKED, report.state());
        assertEquals(SleeperWeekScoringSourceAudit.StarterState.MISSING_STAT_IDENTITY, defense.state());
        assertTrue(defense.defenseIdentity());
        assertTrue(report.blockers().stream().anyMatch(blocker -> blocker.contains("absent from stats payload")));
    }

    private Database fixture(Map<String, Double> scoring, List<String> lineupSlots) throws Exception {
        Database database = new Database(tempDir.resolve("bf558.db"));
        database.initialize();
        new LeagueRepository(database).save(new League("l1", "current-sleeper", "Audit League", 2026));
        new LeagueConfigurationObservationRepository(database).replace(new LeagueConfigurationObservation(
            "l1", "sleeper", LocalDate.of(2026, 9, 6), 2025, lineupSlots, scoring));
        return database;
    }

    private static SleeperWeekScoringSourceAudit.Source source(String weeklyStats, String matchups) {
        return new SleeperWeekScoringSourceAudit.Source() {
            @Override
            public SleeperLeagueLineageResolver.Lineage resolveLineage(String currentSleeperLeagueId) {
                return new SleeperLeagueLineageResolver.Lineage(
                    "current-sleeper", "historical-2025", 2026,
                    List.of(
                        new SleeperLeagueLineageResolver.LeagueLink("current-sleeper", 2026, "historical-2025"),
                        new SleeperLeagueLineageResolver.LeagueLink("historical-2025", 2025, null)));
            }

            @Override
            public String weeklyStats(int season, int week) {
                return weeklyStats;
            }

            @Override
            public String matchups(String sleeperLeagueId, int week) {
                assertEquals("historical-2025", sleeperLeagueId);
                return matchups;
            }
        };
    }
}
