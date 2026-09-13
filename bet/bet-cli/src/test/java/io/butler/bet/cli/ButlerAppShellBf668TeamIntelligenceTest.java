package io.butler.bet.cli;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ButlerAppShellBf668TeamIntelligenceTest {

    @Test
    void teamRouteIsAppNativeAndUsesOnlyExistingGovernedReadOnlySources() throws Exception {
        String script = source("scripts/butler-app-shell-core-single.ps1");
        String bundle = source("bet/bet-cli/src/main/java/io/butler/bet/cli/ButlerMyTeamEvidenceBundleCli.java");

        assertTrue(script.contains("$path -eq \"/team\""));
        assertTrue(script.contains(":bet:bet-cli:sleeperLiveWaiverTargetRosterContextAudit"));
        assertTrue(script.contains("$LeagueId --team-bundle"));
        assertTrue(bundle.contains("ButlerPersonalizedTargetCliSupport.verify(database, leagueId)"));
        assertTrue(bundle.contains("new SleeperLiveWaiverTargetRosterContextAudit(database).audit(leagueId, target.sleeperUserId())"));
        assertTrue(bundle.contains("new LeagueTeamContextAnalyzer(database).analyze(leagueId)"));
        assertTrue(bundle.contains("ButlerMain.printLeagueTeamContext(teamContextReport)"));
        assertTrue(bundle.contains("new LeagueRosterStrengthTierAnalyzer(database).analyze(leagueId)"));
        assertTrue(bundle.contains("ButlerLeagueRosterStrengthCli.print(rosterStrengthReport)"));
        assertTrue(bundle.contains("new LeaguePositionalPressureAnalyzer(database).analyze(leagueId)"));
        assertTrue(bundle.contains("ButlerLeaguePositionalPressureCli.print(positionalPressureReport)"));
        assertTrue(bundle.contains("new LeagueTeamPostureAnalyzer(database).analyze(leagueId, season)"));
        assertTrue(bundle.contains("ButlerLeagueTeamPostureCli.print(teamPostureReport)"));
        assertTrue(bundle.contains("new LeagueFutureCapitalTierAnalyzer(database).analyze(leagueId)"));
        assertTrue(bundle.contains("ButlerLeagueFutureCapitalCli.print(futureCapitalReport)"));
        assertTrue(script.contains("My team intelligence"));
        assertTrue(script.contains("Exact BF-623-bound live roster context from BF-610"));
        assertTrue(script.contains("ConvertTo-HtmlText"));
        assertTrue(script.contains("governed BF-623/BF-610 roster identity is missing or not live verified"));
        assertTrue(script.contains("$knownDashboardPath = $path -eq \"/\" -or $path -eq \"/index.html\" -or $path -eq \"/waivers\" -or $candidate"));

        assertFalse(script.contains("$knownDashboardPath = $path -eq \"/\" -or $path -eq \"/index.html\" -or $path -eq \"/team\""));
        assertFalse(script.contains("sleeperLiveWaiverSnapshotSync"));
        assertFalse(script.contains("sleeperLiveWaiverMarketAttentionSync"));
        assertFalse(script.contains("sleeperLiveWaiverRecommendationAuditCapture"));
        assertFalse(script.contains("sleeperLiveWaiverFinalRecommendationBundle"));
        assertFalse(script.contains("Invoke-Expression"));
        assertFalse(script.contains("Start-Job"));
        assertFalse(bundle.contains("SnapshotSync"));
        assertFalse(bundle.contains("MarketAttentionSync"));
        assertFalse(bundle.contains("RecommendationAuditCapture"));
        assertFalse(bundle.contains("FinalRecommendationBundle"));
    }

    @Test
    void teamPageDoesNotInventACompositeScoreOrLineupRecommendation() throws Exception {
        String script = source("scripts/butler-app-shell-core-single.ps1");
        String bundle = source("bet/bet-cli/src/main/java/io/butler/bet/cli/ButlerMyTeamEvidenceBundleCli.java");

        assertTrue(script.contains("No new team score or strategy model is created here."));
        assertTrue(script.contains("this page does not turn them into start/sit advice."));
        assertTrue(script.contains("It does not create a new score, recommend a lineup, rerank players"));
        assertFalse(script.contains("teamScore ="));
        assertFalse(script.contains("lineupRecommendation ="));
        assertFalse(bundle.contains("teamScore"));
        assertFalse(bundle.contains("lineupRecommendation"));
    }

    @Test
    void appShellRemainsAsciiOnly() throws Exception {
        String script = source("scripts/butler-app-shell-core-single.ps1");
        byte[] encoded = script.getBytes(StandardCharsets.US_ASCII);
        assertEquals(script, new String(encoded, StandardCharsets.US_ASCII));
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
        throw new IOException("BF-668 test could not locate " + relativePath);
    }
}
