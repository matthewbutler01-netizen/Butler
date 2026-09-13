package io.butler.bet.intelligence;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LeagueValueMoverAnalyzerBf726BatchReadTest {

    @Test
    void movementAnalysisUsesBoundedLeagueAndDateWindowReads() throws Exception {
        String analyzer = source("bet/bet-cli/src/main/java/io/butler/bet/intelligence/LeagueValueMoverAnalyzer.java");

        assertTrue(analyzer.contains("teams.findByLeagueId(normalizedLeagueId)"));
        assertTrue(analyzer.contains("rosters.findByLeagueId(normalizedLeagueId)"));
        assertTrue(analyzer.contains("players.findByLeagueId(normalizedLeagueId)"));
        assertTrue(analyzer.contains("values.findBySourceAndDates("));

        assertFalse(analyzer.contains("new LeagueAnalyzer(database)"));
        assertFalse(analyzer.contains("leagues.analyze("));
        assertFalse(analyzer.contains("rosters.findByTeamId("));
        assertFalse(analyzer.contains("values.findByPlayerIdAndSource("));
        assertFalse(analyzer.contains("players.findById("));
    }

    @Test
    void playerValueBatchReadIsBoundedToSourceAndExactWindowDates() throws Exception {
        String repository = source("bet/bet-cli/src/main/java/io/butler/bet/data/PlayerValueRepository.java");

        assertTrue(repository.contains("public List<PlayerValue> findBySourceAndDates("));
        assertTrue(repository.contains("WHERE source = ? AND as_of_date IN (?, ?)"));
        assertTrue(repository.contains("statement.setString(1, normalizedSource)"));
        assertTrue(repository.contains("statement.setString(2, normalizedPreviousDate.toString())"));
        assertTrue(repository.contains("statement.setString(3, normalizedLatestDate.toString())"));
        assertTrue(repository.contains("ORDER BY player_id ASC, as_of_date ASC"));
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
        throw new IOException("BF-726 test could not locate " + relativePath);
    }
}
