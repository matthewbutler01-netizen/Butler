package io.butler.bet.cli;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ButlerLeaguePlayerCompareCliTest {

    @Test
    void parsesTwoExactPlayersAndOptionalSeason() {
        var normal = ButlerLeaguePlayerCompareCli.parse(
            new String[]{"league", "player-compare", "l1", "p1", "p2"});
        assertEquals("l1", normal.leagueId());
        assertEquals("p1", normal.leftPlayerId());
        assertEquals("p2", normal.rightPlayerId());
        assertEquals(null, normal.season());

        var withSeason = ButlerLeaguePlayerCompareCli.parse(
            new String[]{"league", "player-compare", "l1", "p1", "p2", "2026"});
        assertEquals(2026, withSeason.season());
    }

    @Test
    void rejectsSamePlayerMissingIdsAndInvalidSeason() {
        assertThrows(IllegalArgumentException.class, () -> ButlerLeaguePlayerCompareCli.parse(
            new String[]{"league", "player-compare", "l1", "p1", "p1"}));
        assertThrows(IllegalArgumentException.class, () -> ButlerLeaguePlayerCompareCli.parse(
            new String[]{"league", "player-compare", "l1", "p1"}));
        assertThrows(IllegalArgumentException.class, () -> ButlerLeaguePlayerCompareCli.parse(
            new String[]{"league", "player-compare", "l1", "p1", "p2", "bad"}));
    }

    @Test
    void recognizesCompareCommandIndependently() {
        assertTrue(ButlerLeaguePlayerCompareCli.isCommand(
            new String[]{"league", "player-compare", "l1", "p1", "p2"}));
        assertFalse(ButlerLeaguePlayerCompareCli.isCommand(
            new String[]{"league", "player-detail", "l1", "p1"}));
    }

    @Test
    void sharedEvidenceFrameIsBuiltOnceAndNoWinnerModelIsIntroduced() throws Exception {
        String source = source(
            "bet/bet-cli/src/main/java/io/butler/bet/cli/ButlerLeaguePlayerCompareCli.java");

        assertEquals(1, count(source, "new LeaguePlayerEvidenceProfileAnalyzer("));
        assertEquals(1, count(source, "new LeagueAgeProductionContextAnalyzer("));
        assertEquals(2, count(source, "new LeagueAssetInventoryAnalyzer("));
        assertEquals(1, count(source, "var profileReport ="));
        assertEquals(1, count(source, "var ageReport = ageProduction.analyze(profileReport);"));
        assertEquals(1, count(source, "var inventory = inventoryAnalyzer.analyze(options.leagueId());"));
        assertEquals(2, count(source, "player(options."));
        assertTrue(source.contains("ButlerLeaguePlayerDetailCli.select("));

        for (String forbidden : new String[]{
            "better player", "WINNER =", "winner =", "BUY", "SELL", "player score", "confidence",
            "probability", "recommendation ="
        }) {
            assertFalse(source.contains(forbidden), "compare CLI introduced forbidden judgment marker " + forbidden);
        }
        assertTrue(source.contains("does not select a winner"));
        assertTrue(source.contains("Side-by-side neutral evidence only"));
    }

    @Test
    void summaryPathUsesTargetedCurrentEvidenceAndDefersHistoricalSupportingModel() throws Exception {
        String source = source(
            "bet/bet-cli/src/main/java/io/butler/bet/cli/ButlerLeaguePlayerCompareCli.java");

        int summaryStart = source.indexOf("static int runEmbeddedSummary(String[] args)");
        int parseStart = source.indexOf("static Options parse(String[] args)");
        assertTrue(summaryStart >= 0);
        assertTrue(parseStart > summaryStart);
        String summary = source.substring(summaryStart, parseStart);

        assertTrue(summary.contains("new LeagueAssetInventoryAnalyzer(database).analyze(options.leagueId())"));
        assertTrue(summary.contains("new PlayerProfileSnapshotRepository(database)"));
        assertTrue(summary.contains("findLatestByPlayerIdsAndSeasonAndSource("));
        assertTrue(summary.contains("Supporting evidence: DEFERRED"));
        assertFalse(summary.contains("LeaguePlayerEvidenceProfileAnalyzer"));
        assertFalse(summary.contains("LeagueAgeProductionContextAnalyzer"));
        assertFalse(summary.contains("LeagueAgeOutlookSupportingEvidenceAnalyzer"));
        assertFalse(summary.contains("supportingEvidence.analyze"));
    }

    private static int count(String text, String needle) {
        int total = 0;
        int from = 0;
        while (true) {
            int index = text.indexOf(needle, from);
            if (index < 0) return total;
            total++;
            from = index + needle.length();
        }
    }

    private static String source(String relativePath) throws IOException {
        Path current = Path.of(System.getProperty("user.dir")).toAbsolutePath().normalize();
        for (int depth = 0; depth < 7 && current != null; depth++) {
            Path candidate = current.resolve(relativePath);
            if (Files.isRegularFile(candidate)) {
                return Files.readString(candidate, StandardCharsets.UTF_8)
                    .replace("\r\n", "\n");
            }
            current = current.getParent();
        }
        throw new IOException("BF-906 test could not locate " + relativePath);
    }
}
