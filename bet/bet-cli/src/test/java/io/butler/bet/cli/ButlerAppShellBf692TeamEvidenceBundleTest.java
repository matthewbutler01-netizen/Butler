package io.butler.bet.cli;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ButlerAppShellBf692TeamEvidenceBundleTest {

    @Test
    void bundleKeepsAllSixEstablishedReadOnlySourcesInOneJvm() throws Exception {
        String bundle = source("bet/bet-cli/src/main/java/io/butler/bet/cli/ButlerMyTeamEvidenceBundleCli.java");

        assertTrue(bundle.contains("ButlerPersonalizedTargetCliSupport.verify(database, leagueId)"));
        assertTrue(bundle.contains("new SleeperLiveWaiverTargetRosterContextAudit(database).audit(leagueId, target.sleeperUserId())"));
        assertTrue(bundle.contains("new LeagueTeamContextAnalyzer(database).analyze(leagueId)"));
        assertTrue(bundle.contains("ButlerMain.printLeagueTeamContext(teamContextReport)"));
        assertTrue(bundle.contains("new LeagueRosterStrengthTierAnalyzer(database).analyze(leagueId)"));
        assertTrue(bundle.contains("ButlerLeagueRosterStrengthCli.print(rosterStrengthReport)"));
        assertTrue(bundle.contains("new LeaguePositionalPressureAnalyzer(database).analyze(leagueId)"));
        assertTrue(bundle.contains("ButlerLeaguePositionalPressureCli.print(positionalPressureReport)"));
        assertTrue(bundle.contains("teamPostureAnalyzer.analyzeCompetitiveEvidence(leagueId, season)"));
        assertTrue(bundle.contains("LeagueTeamPostureAnalyzer.compose("));
        assertTrue(bundle.contains("ButlerLeagueTeamPostureCli.print(teamPostureReport)"));
        assertTrue(bundle.contains("new LeagueFutureCapitalTierAnalyzer(database).analyze(leagueId)"));
        assertTrue(bundle.contains("ButlerLeagueFutureCapitalCli.print(futureCapitalReport)"));
        assertTrue(bundle.contains("int season = rosterContextReport.providerSeason();"));

        assertFalse(bundle.contains("providerSeason(rosterContext)"));
        assertFalse(bundle.contains("Pattern.compile"));
        assertFalse(bundle.contains("ProcessBuilder"));
        assertFalse(bundle.contains("Runtime.getRuntime"));
        assertFalse(bundle.contains("gradlew"));
        assertFalse(bundle.contains("create_transaction"));
        assertFalse(bundle.contains("submitTransaction"));
        assertFalse(bundle.contains("waiver_budget"));
    }

    @Test
    void bundleUsesOneInitializedDatabaseForAllSixReads() throws Exception {
        String bundle = source("bet/bet-cli/src/main/java/io/butler/bet/cli/ButlerMyTeamEvidenceBundleCli.java");

        assertEquals(1, occurrences(bundle, "new Database(DATABASE_PATH)"));
        assertEquals(1, occurrences(bundle, "database.initialize()"));
        assertEquals(1, occurrences(bundle, "ButlerPersonalizedTargetCliSupport.verify(database, leagueId)"));
        assertTrue(bundle.contains("Database database = initializedDatabase();"));
        assertFalse(bundle.contains("ButlerSleeperLiveWaiverTargetRosterContextAuditCli.main("));
        assertFalse(bundle.contains("ButlerMain.main("));
        assertFalse(bundle.contains("ButlerLeagueRosterStrengthCli.main("));
        assertFalse(bundle.contains("ButlerLeaguePositionalPressureCli.main("));
        assertFalse(bundle.contains("ButlerLeagueTeamPostureCli.main("));
        assertFalse(bundle.contains("ButlerLeagueFutureCapitalCli.main("));
    }

    @Test
    void bundleUsesDeterministicSixSectionContract() throws Exception {
        String bundle = source("bet/bet-cli/src/main/java/io/butler/bet/cli/ButlerMyTeamEvidenceBundleCli.java");

        for (String section : new String[]{
            "ROSTER_CONTEXT", "TEAM_CONTEXT", "ROSTER_STRENGTH",
            "POSITIONAL_PRESSURE", "TEAM_POSTURE", "FUTURE_CAPITAL"
        }) {
            assertTrue(bundle.contains("\"" + section + "\""));
        }
        assertTrue(bundle.contains("===BUTLER_TEAM_BUNDLE:"));
        assertTrue(bundle.contains(":BEGIN==="));
        assertTrue(bundle.contains(":END==="));
        assertTrue(bundle.contains("no Butler or Sleeper write is executed"));
    }

    @Test
    void providerSeasonComesDirectlyFromExactBf610RosterReport() throws Exception {
        String bundle = source("bet/bet-cli/src/main/java/io/butler/bet/cli/ButlerMyTeamEvidenceBundleCli.java");

        assertTrue(bundle.contains("SleeperLiveWaiverTargetRosterContextAudit.AuditReport rosterContextReport = await(rosterContextFuture);"));
        assertTrue(bundle.contains("int season = rosterContextReport.providerSeason();"));
        assertFalse(bundle.contains("Pattern PROVIDER_SEASON"));
        assertFalse(bundle.contains("static int providerSeason("));
    }

    @Test
    void existingBf610TaskExposesOnlyExplicitBundleMode() throws Exception {
        String bf610 = source("bet/bet-cli/src/main/java/io/butler/bet/cli/ButlerSleeperLiveWaiverTargetRosterContextAuditCli.java");

        assertTrue(bf610.contains("if (isTeamBundle(args))"));
        assertTrue(bf610.contains("ButlerMyTeamEvidenceBundleCli.main(new String[]{args[0].trim()})"));
        assertTrue(bf610.contains("args.length == 2"));
        assertTrue(bf610.contains("\"--team-bundle\".equals(args[1])"));
        assertTrue(bf610.contains("args.length != 1"));
        assertTrue(bf610.contains("ButlerPersonalizedTargetCliSupport.verify(database, leagueId)"));
    }

    @Test
    void teamRendererUsesOneTaskThenSameSixConvertersAndHtml() throws Exception {
        String script = source("scripts/butler-app-shell-core-single.ps1");
        int teamStart = script.indexOf("if ($path -eq \"/team\")");
        int teamEnd = script.indexOf("$candidate = $path -match", teamStart);
        assertTrue(teamStart >= 0 && teamEnd > teamStart);
        String teamBlock = script.substring(teamStart, teamEnd);

        assertTrue(teamBlock.contains("Invoke-ButlerReadOnlyTask"));
        assertTrue(teamBlock.contains("$LeagueId --team-bundle"));
        assertEquals(1, occurrences(teamBlock, "Invoke-ButlerReadOnlyTask"));
        assertEquals(0, occurrences(teamBlock, "Invoke-ButlerReadOnly -Arguments"));

        for (String section : new String[]{
            "ROSTER_CONTEXT", "TEAM_CONTEXT", "ROSTER_STRENGTH",
            "POSITIONAL_PRESSURE", "TEAM_POSTURE", "FUTURE_CAPITAL"
        }) {
            assertTrue(teamBlock.contains("-Name \"" + section + "\""));
        }
        assertTrue(teamBlock.contains("ConvertTo-RosterContextView"));
        assertTrue(teamBlock.contains("ConvertTo-TeamContextView"));
        assertTrue(teamBlock.contains("ConvertTo-RosterStrengthView"));
        assertTrue(teamBlock.contains("ConvertTo-PositionalPressureView"));
        assertTrue(teamBlock.contains("ConvertTo-TeamPostureView"));
        assertTrue(teamBlock.contains("ConvertTo-FutureCapitalView"));
        assertTrue(teamBlock.contains("ConvertTo-TeamHtml"));
    }

    @Test
    void sectionParserUsesWindowsSafeVariableDelimitingAndFailsClosed() throws Exception {
        String script = source("scripts/butler-app-shell-core-single.ps1");

        assertTrue(script.contains("function Get-TeamEvidenceBundleSection"));
        assertTrue(script.contains("$begin = \"===BUTLER_TEAM_BUNDLE:${Name}:BEGIN===\""));
        assertTrue(script.contains("$end = \"===BUTLER_TEAM_BUNDLE:${Name}:END===\""));
        assertFalse(script.contains("$begin = \"===BUTLER_TEAM_BUNDLE:$Name:BEGIN===\""));
        assertFalse(script.contains("$end = \"===BUTLER_TEAM_BUNDLE:$Name:END===\""));
        assertTrue(script.contains("BF-692 BLOCKED: My Team evidence bundle is missing $Name begin marker."));
        assertTrue(script.contains("BF-692 BLOCKED: My Team evidence bundle is missing $Name end marker."));
        assertTrue(script.contains("BF-692 BLOCKED: My Team evidence bundle section $Name is empty."));
        byte[] encoded = script.getBytes(StandardCharsets.US_ASCII);
        assertEquals(script, new String(encoded, StandardCharsets.US_ASCII));
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
        throw new IOException("BF-692 test could not locate " + relativePath);
    }
}
