package io.butler.bet.intelligence;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LeagueDraftCapitalTimelineAnalyzerBf731BatchValueTest {

    @Test
    void futureCapitalUsesOneLatestValueReadPerSource() throws Exception {
        String analyzer = source("bet/bet-cli/src/main/java/io/butler/bet/intelligence/LeagueDraftCapitalTimelineAnalyzer.java");

        assertTrue(analyzer.contains("values.findLatestBySource(source)"));
        assertTrue(analyzer.contains("latestValues.put(value.getDraftPickId(), value)"));
        assertTrue(analyzer.contains("latestValues.get(pick.getId())"));
        assertFalse(analyzer.contains("values.findLatestByDraftPickIdAndSource("));
    }

    @Test
    void currentOwnerSeasonCoverageAndFreshnessRemainInBatchComposition() throws Exception {
        String analyzer = source("bet/bet-cli/src/main/java/io/butler/bet/intelligence/LeagueDraftCapitalTimelineAnalyzer.java");

        assertTrue(analyzer.contains("pick.getOwnerTeamId()"));
        assertTrue(analyzer.contains("pick.getSeason()"));
        assertTrue(analyzer.contains("pick.getRound()"));
        assertTrue(analyzer.contains("season.missingPicks++"));
        assertTrue(analyzer.contains("team.missingPicks++"));
        assertTrue(analyzer.contains("value.getAsOfDate().isBefore(minimumAsOfDate)"));
        assertTrue(analyzer.contains("season.stalePicks++"));
        assertTrue(analyzer.contains("team.stalePicks++"));
        assertTrue(analyzer.contains("season.valuedPicks++"));
        assertTrue(analyzer.contains("team.valuedPicks++"));
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
        throw new IOException("BF-731 test could not locate " + relativePath);
    }
}
