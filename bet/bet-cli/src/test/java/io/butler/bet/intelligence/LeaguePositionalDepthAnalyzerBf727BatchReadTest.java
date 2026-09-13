package io.butler.bet.intelligence;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LeaguePositionalDepthAnalyzerBf727BatchReadTest {

    @Test
    void positionalDepthUsesBoundedLeagueAndSourceBatchReads() throws Exception {
        String analyzer = source("bet/bet-cli/src/main/java/io/butler/bet/intelligence/LeaguePositionalDepthAnalyzer.java");

        assertTrue(analyzer.contains("teams.findByLeagueId(normalizedLeagueId)"));
        assertTrue(analyzer.contains("rosters.findByLeagueId(normalizedLeagueId)"));
        assertTrue(analyzer.contains("players.findByLeagueId(normalizedLeagueId)"));
        assertTrue(analyzer.contains("values.findLatestBySource(normalizedSource)"));

        assertFalse(analyzer.contains("new LeagueAnalyzer(database)"));
        assertFalse(analyzer.contains("leagues.analyze("));
        assertFalse(analyzer.contains("rosters.findByTeamId("));
        assertFalse(analyzer.contains("players.findById("));
        assertFalse(analyzer.contains("values.findLatestByPlayerIdAndSource("));
    }

    @Test
    void staleAndMissingClassificationRemainsAfterBatchComposition() throws Exception {
        String analyzer = source("bet/bet-cli/src/main/java/io/butler/bet/intelligence/LeaguePositionalDepthAnalyzer.java");

        assertTrue(analyzer.contains("summary.missingPlayers++"));
        assertTrue(analyzer.contains("value.getAsOfDate().isBefore(minimumAsOfDate)"));
        assertTrue(analyzer.contains("summary.stalePlayers++"));
        assertTrue(analyzer.contains("normalizePosition(player.getPosition())"));
        assertTrue(analyzer.contains("roster.getSlot()"));
    }

    private static String source(String relativePath) throws IOException {
        Path current = Path.of(System.getProperty("user.dir")).toAbsolutePath().normalize();
        for (int depth = 0; depth < 7 && current != null; depth++) {
            Path candidate = current.resolve(relativePath);
            if (Files.isRegularFile(candidate)) {
                return Files.readString(candidate, StandardCharsets.UTF_8);
            }
            current = current.getParent();
        }
        throw new IOException("BF-727 test could not locate " + relativePath);
    }
}
