package io.butler.bet.sleeper;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SleeperLiveWaiverProductionCoverageAuditBf746BatchTest {
    @Test
    void sourceBatchesCanonicalAndProductionReadsOutsideCandidateLoop() throws Exception {
        String source = source(
            "bet/bet-cli/src/main/java/io/butler/bet/sleeper/SleeperLiveWaiverProductionCoverageAudit.java");

        assertTrue(source.contains("static final int SQL_BATCH_SIZE = 400;"));
        assertTrue(source.contains("exactButlerPlayerIds(connection, marketActive)"));
        assertTrue(source.contains("latestProductionPerPlayerAndSource("));
        assertTrue(source.contains("GROUP BY player_id, source"));
        assertTrue(source.contains("latest.player_id = p.player_id"));
        assertEquals(1, occurrences(source, "requireTable(connection, \"player_season_production\")"));
        assertFalse(source.contains("private static String exactButlerPlayerId("));
        assertFalse(source.contains("private static List<ProductionObservation> latestProductionPerSource("));

        int loopStart = source.indexOf("for (MarketCandidate candidate : marketActive) {");
        int loopEnd = source.indexOf(
            "if (unmapped + mappedNoProduction + mappedWithProduction != marketActive.size())",
            loopStart);
        assertTrue(loopStart >= 0 && loopEnd > loopStart);
        String candidateLoop = source.substring(loopStart, loopEnd);
        assertFalse(candidateLoop.contains("prepareStatement"));
        assertFalse(candidateLoop.contains("exactButlerPlayerIds("));
        assertFalse(candidateLoop.contains("latestProductionPerPlayerAndSource("));
        assertFalse(candidateLoop.contains("database.openConnection"));
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
        throw new IOException("BF-746 test could not locate " + relativePath);
    }

    private static int occurrences(String text, String needle) {
        int count = 0;
        int offset = 0;
        while ((offset = text.indexOf(needle, offset)) >= 0) {
            count++;
            offset += needle.length();
        }
        return count;
    }
}
