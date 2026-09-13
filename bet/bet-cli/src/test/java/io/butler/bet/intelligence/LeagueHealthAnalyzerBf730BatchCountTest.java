package io.butler.bet.intelligence;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LeagueHealthAnalyzerBf730BatchCountTest {

    @Test
    void healthCountsUseBoundedLeagueReadsInsteadOfLeagueAnalyzerExpansion() throws Exception {
        String analyzer = source("bet/bet-cli/src/main/java/io/butler/bet/intelligence/LeagueHealthAnalyzer.java");

        assertTrue(analyzer.contains("teams.findByLeagueId(normalizedLeagueId).size()"));
        assertTrue(analyzer.contains("players.findByLeagueId(normalizedLeagueId)"));
        assertTrue(analyzer.contains("rosters.findByLeagueId(normalizedLeagueId)"));

        assertFalse(analyzer.contains("new LeagueAnalyzer(database)"));
        assertFalse(analyzer.contains("leagueAnalyzer.analyze("));
        assertFalse(analyzer.contains("rosters.findByTeamId("));
        assertFalse(analyzer.contains("players.findById("));
    }

    @Test
    void rosteredPlayerCountPreservesLegacyValidPlayerMembershipSemantics() throws Exception {
        String analyzer = source("bet/bet-cli/src/main/java/io/butler/bet/intelligence/LeagueHealthAnalyzer.java");

        assertTrue(analyzer.contains("existingPlayerIds.add(player.getId())"));
        assertTrue(analyzer.contains("if (existingPlayerIds.contains(roster.getPlayerId())) rosteredPlayers++"));
        assertFalse(analyzer.contains("int rosteredPlayers = rosters.findByLeagueId(normalizedLeagueId).size()"));
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
        throw new IOException("BF-730 test could not locate " + relativePath);
    }
}
