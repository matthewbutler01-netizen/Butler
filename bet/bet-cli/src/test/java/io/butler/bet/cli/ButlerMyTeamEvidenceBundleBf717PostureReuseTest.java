package io.butler.bet.cli;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ButlerMyTeamEvidenceBundleBf717PostureReuseTest {

    @Test
    void bundleComputesRosterStrengthOnceAndReusesItForPosture() throws Exception {
        String source = source("bet/bet-cli/src/main/java/io/butler/bet/cli/ButlerMyTeamEvidenceBundleCli.java");

        assertEquals(1, occurrences(source, "new LeagueRosterStrengthTierAnalyzer(database).analyze(leagueId)"),
            "BF-717 must leave exactly one roster-strength analysis in the My Team bundle");
        assertTrue(source.contains("teamPostureAnalyzer.analyzeCompetitiveEvidence(leagueId, season)"));
        assertTrue(source.contains("LeagueTeamPostureAnalyzer.compose(\n                    await(postureCompetitiveFuture), rosterStrengthReport)"));
        assertFalse(source.contains("new LeagueTeamPostureAnalyzer(database).analyze(leagueId, season)"),
            "posture must not launch a second roster-strength analysis");
        assertTrue(source.contains("static final int POST_ROSTER_WORKERS = 5;"),
            "BF-717 must not raise the five-worker evidence ceiling");
    }

    @Test
    void postureAnalyzerKeepsExistingStandaloneBehaviorBehindReusableCompetitiveEvidence() throws Exception {
        String source = source("bet/bet-cli/src/main/java/io/butler/bet/intelligence/LeagueTeamPostureAnalyzer.java");

        assertTrue(source.contains("public LeagueCompetitiveTierAnalyzer.CompetitiveTierReport analyzeCompetitiveEvidence"));
        assertTrue(source.contains("return competitive.analyze(performance.analyze(leagueId, season));"));
        assertEquals(4, occurrences(source, "var competitiveReport = analyzeCompetitiveEvidence(leagueId, season);"),
            "all existing posture entry points should preserve their competitive evidence semantics");
        assertEquals(4, occurrences(source, "var rosterReport = rosterStrength.analyze("),
            "standalone posture entry points must continue to compute their own roster evidence");
    }

    private static int occurrences(String text, String needle) {
        int count = 0;
        for (int at = 0; (at = text.indexOf(needle, at)) >= 0; at += needle.length()) count++;
        return count;
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
        throw new IOException("BF-717 test could not locate " + relativePath);
    }
}
