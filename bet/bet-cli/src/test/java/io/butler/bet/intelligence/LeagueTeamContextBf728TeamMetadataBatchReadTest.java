package io.butler.bet.intelligence;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LeagueTeamContextBf728TeamMetadataBatchReadTest {

    @Test
    void franchiseInventoryUsesBoundedTeamMetadataInsteadOfLeagueExpansion() throws Exception {
        String source = source("bet/bet-cli/src/main/java/io/butler/bet/intelligence/LeagueAssetInventoryAnalyzer.java");

        assertTrue(source.contains("new TeamRepository(database)"));
        assertTrue(source.contains("teams.findByLeagueId(leagueId)"));
        assertFalse(source.contains("new LeagueAnalyzer(database)"));
        assertFalse(source.contains("leagues.analyze("));
    }

    @Test
    void teamPortfolioUsesBoundedTeamMetadataInsteadOfLeagueExpansion() throws Exception {
        String source = source("bet/bet-cli/src/main/java/io/butler/bet/intelligence/TeamAssetPortfolioAnalyzer.java");

        assertTrue(source.contains("new TeamRepository(database)"));
        assertTrue(source.contains("teams.findByLeagueId(leagueId)"));
        assertFalse(source.contains("new LeagueAnalyzer(database)"));
        assertFalse(source.contains("leagues.analyze("));
    }

    @Test
    void healthAnalyzerSeparateMeasurementFollowupNowUsesBoundedCounts() throws Exception {
        String source = source("bet/bet-cli/src/main/java/io/butler/bet/intelligence/LeagueHealthAnalyzer.java");

        assertTrue(source.contains("teams.findByLeagueId(normalizedLeagueId)"));
        assertTrue(source.contains("rosters.findByLeagueId(normalizedLeagueId)"));
        assertTrue(source.contains("players.findByLeagueId(normalizedLeagueId)"));
        assertFalse(source.contains("new LeagueAnalyzer(database)"));
        assertFalse(source.contains("leagueAnalyzer.analyze("));
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
        throw new IOException("BF-728 test could not locate " + relativePath);
    }
}
