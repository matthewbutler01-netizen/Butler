package io.butler.bet.intelligence;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LeagueAssetInventoryAnalyzerBf725BatchReadTest {

    @Test
    void inventoryCompositionUsesBoundedLeagueAndSourceBatchReads() throws Exception {
        String analyzer = source("bet/bet-cli/src/main/java/io/butler/bet/intelligence/LeagueAssetInventoryAnalyzer.java");

        assertTrue(analyzer.contains("rosters.findByLeagueId(leagueId)"));
        assertTrue(analyzer.contains("players.findByLeagueId(leagueId)"));
        assertTrue(analyzer.contains("playerValues.findLatestBySource(source)"));
        assertTrue(analyzer.contains("draftPicks.findByLeagueId(leagueId)"));
        assertTrue(analyzer.contains("draftPickValues.findLatestBySource(source)"));

        assertFalse(analyzer.contains("rosters.findByTeamId(team.teamId())"));
        assertFalse(analyzer.contains("players.findById(roster.getPlayerId())"));
        assertFalse(analyzer.contains("playerValues.findLatestByPlayerIdAndSource"));
        assertFalse(analyzer.contains("draftPicks.findByOwnerTeamId(team.teamId())"));
        assertFalse(analyzer.contains("draftPickValues.findLatestByDraftPickIdAndSource"));
    }

    @Test
    void leaguePlayerBatchReadIsLeagueScopedAndDeterministic() throws Exception {
        String repository = source("bet/bet-cli/src/main/java/io/butler/bet/data/PlayerRepository.java");

        assertTrue(repository.contains("public List<Player> findByLeagueId(String leagueId)"));
        assertTrue(repository.contains("JOIN rosters r ON r.player_id = p.id"));
        assertTrue(repository.contains("JOIN teams t ON t.id = r.team_id"));
        assertTrue(repository.contains("WHERE t.league_id = ?"));
        assertTrue(repository.contains("SELECT DISTINCT p.id, p.external_id, p.display_name, p.position, p.nfl_team"));
        assertTrue(repository.contains("ORDER BY p.display_name COLLATE NOCASE ASC, p.display_name ASC, p.id ASC"));
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
        throw new IOException("BF-725 test could not locate " + relativePath);
    }
}
