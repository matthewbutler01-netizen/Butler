package io.butler.bet.cli;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ButlerSleeperCurrentWeekMatchupSyncCliTest {

    @Test
    void acceptsExactlyOneButlerLeagueId() {
        assertEquals("league-id",
            ButlerSleeperCurrentWeekMatchupSyncCli.parse(new String[]{" league-id "}));
    }

    @Test
    void resolvesGovernedRuntimeDatabaseWhenConfigured() {
        Path absolute = Path.of(System.getProperty("java.io.tmpdir")).toAbsolutePath().resolve("butler-data");
        assertEquals(absolute.resolve("butler.db"),
            ButlerSleeperCurrentWeekMatchupSyncCli.databasePath(absolute.toString()));
        assertEquals(Path.of("butler.db"),
            ButlerSleeperCurrentWeekMatchupSyncCli.databasePath(" "));
        assertThrows(IllegalArgumentException.class,
            () -> ButlerSleeperCurrentWeekMatchupSyncCli.databasePath("relative-data"));
    }

    @Test
    void matchingPublicNflWeekIsRequiredBeforeAnyLocalEvidenceImport() {
        String current = "{\"season\":\"2026\",\"season_type\":\"regular\",\"week\":5}";
        ButlerSleeperCurrentWeekMatchupSyncCli.requirePublicWeekMatch(2026, 5, current);
        assertThrows(IllegalStateException.class,
            () -> ButlerSleeperCurrentWeekMatchupSyncCli.requirePublicWeekMatch(2026, 4, current));
        assertThrows(IllegalStateException.class,
            () -> ButlerSleeperCurrentWeekMatchupSyncCli.requirePublicWeekMatch(2025, 5, current));
        assertThrows(IllegalStateException.class,
            () -> ButlerSleeperCurrentWeekMatchupSyncCli.requirePublicWeekMatch(2026, 5, ""));
        assertThrows(IllegalStateException.class,
            () -> ButlerSleeperCurrentWeekMatchupSyncCli.requirePublicWeekMatch(
                2026, 5, "{\"season\":\"2026\",\"season_type\":\"regular\",\"week\":4,\"week\":5}"));
        assertThrows(IllegalStateException.class,
            () -> ButlerSleeperCurrentWeekMatchupSyncCli.requirePublicWeekMatch(
                2026, 5, "{\"season\":\"2026\",\"season_type\":\"post\",\"week\":5}"));
    }

    @Test
    void checkedPublicWeekMustPrecedeFirstMatchupEvidenceWrite() throws Exception {
        var source = java.nio.file.Files.readString(findSource(
            "bet/bet-cli/src/main/java/io/butler/bet/cli/ButlerSleeperCurrentWeekMatchupSyncCli.java"));
        int proof = source.indexOf("requirePublicWeekMatch(live.providerSeason(), live.providerLeg(), publicWeek)");
        int firstImport = source.indexOf(".importWeek(live.sleeperLeagueId(), live.providerLeg())");
        org.junit.jupiter.api.Assertions.assertTrue(proof > 0 && firstImport > proof,
            "public live week must be verified before the importer can persist any roster/matchup evidence");
    }

    private static Path findSource(String name) throws Exception {
        Path p = Path.of(System.getProperty("user.dir")).toAbsolutePath();
        for (int i = 0; i < 7 && p != null; i++, p = p.getParent()) {
            Path candidate = p.resolve(name);
            if (java.nio.file.Files.isRegularFile(candidate)) return candidate;
        }
        throw new java.io.IOException("BF-1059 source contract missing: " + name);
    }

    @Test
    void rejectsMissingOrExtraArguments() {
        assertThrows(IllegalArgumentException.class,
            () -> ButlerSleeperCurrentWeekMatchupSyncCli.parse(null));
        assertThrows(IllegalArgumentException.class,
            () -> ButlerSleeperCurrentWeekMatchupSyncCli.parse(new String[]{}));
        assertThrows(IllegalArgumentException.class,
            () -> ButlerSleeperCurrentWeekMatchupSyncCli.parse(new String[]{"league", "extra"}));
    }
}
