package io.butler.bet.intelligence;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TeamContextBf721SingleReadTest {

    @Test
    void portfolioExplicitSourceDelegatesWithoutPreReadingLeague() throws Exception {
        String source = source("bet/bet-cli/src/main/java/io/butler/bet/intelligence/TeamAssetPortfolioAnalyzer.java");
        String method = method(source,
            "public PortfolioReport analyze(String leagueId, String source)",
            "private void validateExplicitSource");

        assertFalse(method.contains("leagues.analyze(normalizedLeagueId)"));
        assertTrue(method.contains("return analyzeResolved(normalizedLeagueId, normalizedSource);"));
        assertTrue(source.contains("LeagueAnalyzer.LeagueReport league = leagues.analyze(leagueId);"));
    }

    @Test
    void inventoryExplicitSourceDelegatesWithoutPreReadingLeague() throws Exception {
        String source = source("bet/bet-cli/src/main/java/io/butler/bet/intelligence/LeagueAssetInventoryAnalyzer.java");
        String method = method(source,
            "public InventoryReport analyze(String leagueId, String source)",
            "private InventoryReport analyzeResolved");

        assertFalse(method.contains("leagues.analyze(normalizedLeagueId)"));
        assertTrue(method.contains("return analyzeResolved(normalizedLeagueId, requireText(source, \"source\"));"));
        assertTrue(source.contains("LeagueAnalyzer.LeagueReport league = leagues.analyze(leagueId);"));
    }

    private static String method(String source, String startToken, String endToken) {
        int start = source.indexOf(startToken);
        int end = source.indexOf(endToken, start + 1);
        assertTrue(start >= 0, "method start token must exist");
        assertTrue(end > start, "method end token must follow method start");
        return source.substring(start, end);
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
        throw new IOException("BF-721 test could not locate " + relativePath);
    }
}
