package io.butler.bet.intelligence;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TeamAssetPortfolioAnalyzerBf724BatchReadTest {

    @Test
    void portfolioCompositionUsesBoundedLeagueAndSourceBatchReads() throws Exception {
        String analyzer = source("bet/bet-cli/src/main/java/io/butler/bet/intelligence/TeamAssetPortfolioAnalyzer.java");

        assertTrue(analyzer.contains("rosters.findByLeagueId(leagueId)"));
        assertTrue(analyzer.contains("playerValues.findLatestBySource(source)"));
        assertTrue(analyzer.contains("draftPicks.findByLeagueId(leagueId)"));
        assertTrue(analyzer.contains("draftPickValues.findLatestBySource(source)"));

        assertFalse(analyzer.contains("rosters.findByTeamId(team.teamId())"));
        assertFalse(analyzer.contains("draftPicks.findByOwnerTeamId(team.teamId())"));
        assertFalse(analyzer.contains("playerValues.findLatestByPlayerIdAndSource"));
        assertFalse(analyzer.contains("draftPickValues.findLatestByDraftPickIdAndSource"));
    }

    @Test
    void repositoryBatchReadsRemainReadOnlyAndDeterministic() throws Exception {
        String rosters = source("bet/bet-cli/src/main/java/io/butler/bet/data/RosterRepository.java");
        String pickValues = source("bet/bet-cli/src/main/java/io/butler/bet/data/DraftPickValueRepository.java");

        assertTrue(rosters.contains("public List<Roster> findByLeagueId(String leagueId)"));
        assertTrue(rosters.contains("JOIN teams t ON t.id = r.team_id"));
        assertTrue(rosters.contains("WHERE t.league_id = ?"));
        assertTrue(rosters.contains("ORDER BY r.team_id, r.slot, r.player_id"));

        assertTrue(pickValues.contains("public List<DraftPickValue> findLatestBySource(String source)"));
        assertTrue(pickValues.contains("SELECT draft_pick_id, MAX(as_of_date) AS max_date"));
        assertTrue(pickValues.contains("WHERE source = ?"));
        assertTrue(pickValues.contains("ORDER BY dpv.value DESC, dpv.draft_pick_id ASC"));
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
        throw new IOException("BF-724 test could not locate " + relativePath);
    }
}
