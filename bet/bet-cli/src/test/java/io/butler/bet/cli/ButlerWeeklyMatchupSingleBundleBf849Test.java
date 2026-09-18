package io.butler.bet.cli;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ButlerWeeklyMatchupSingleBundleBf849Test {

    @Test
    void dedicatedBundleUsesOneDatabaseAndOnlyMatchupRequiredEvidence() throws Exception {
        String source = source("bet/bet-cli/src/main/java/io/butler/bet/cli/ButlerWeeklyMatchupEvidenceBundleCli.java");

        assertTrue(source.contains("Database database = initializedDatabase();"));
        assertTrue(source.contains("new PersonalizedSleeperTargetRepository(database)"));
        assertTrue(source.contains("new TeamWeekRosterEvidenceRepository(database)"));
        assertTrue(source.contains("new TeamRepository(database)"));
        assertTrue(source.contains("new LeagueRosterStrengthTierAnalyzer(database)"));
        assertTrue(source.contains("new LeaguePositionalPressureAnalyzer(database)"));
        assertTrue(source.contains("new WeeklyMatchupWorkspaceAnalyzer(database)"));
        assertTrue(source.contains("ButlerWeeklyMatchupWorkspaceCli.print(report)"));
        assertTrue(source.contains("ButlerMyTeamEvidenceBundleCli.emit(MATCHUP_CONTEXT, matchupContext)"));
        assertTrue(source.contains("ButlerMyTeamEvidenceBundleCli.emit(MATCHUP, matchup)"));

        assertFalse(source.contains("LeagueTeamContextAnalyzer"));
        assertFalse(source.contains("LeagueTeamPostureAnalyzer"));
        assertFalse(source.contains("LeagueFutureCapitalTierAnalyzer"));
    }

    @Test
    void passiveBundleUsesPersistedContextAndKeepsLiveRosterBehindAutofill() throws Exception {
        String source = source("bet/bet-cli/src/main/java/io/butler/bet/cli/ButlerWeeklyMatchupEvidenceBundleCli.java");
        int runStart = source.indexOf("static int runEmbedded");
        int autoFillHelper = source.indexOf("private static SleeperLiveAutoFillLineupRecommendation.RecommendationReport autoFillSafely", runStart);
        assertTrue(runStart >= 0 && autoFillHelper > runStart);
        String composition = source.substring(runStart, autoFillHelper);

        assertTrue(composition.contains("loadPersistedContext(database, leagueId)"));
        assertTrue(composition.contains("includeAutoFill"));
        assertTrue(composition.contains("autoFillSafely(database, leagueId, context)"));
        assertFalse(composition.contains("ButlerPersonalizedTargetCliSupport.verify"));
        assertFalse(composition.contains("new SleeperLiveWaiverTargetRosterContextAudit"));
        assertTrue(source.substring(autoFillHelper).contains("ButlerPersonalizedTargetCliSupport.verify(database, leagueId)"));
        assertTrue(source.substring(autoFillHelper).contains("new SleeperLiveWaiverTargetRosterContextAudit(database)"));
    }

    @Test
    void bundledPairingFailureIsIsolatedAndExplicitlyFailClosed() {
        String unavailable = ButlerWeeklyMatchupEvidenceBundleCli.unavailableMatchupSection("pairing missing");

        assertTrue(unavailable.contains("State: UNAVAILABLE"));
        assertTrue(unavailable.contains("Reason: pairing missing"));
        assertTrue(unavailable.contains("no opponent is guessed"));
    }

    @Test
    void explicitAutofillRemainsOptIn() {
        assertTrue(ButlerWeeklyMatchupEvidenceBundleCli.validAutoFillArgs(
            new String[]{"league", "--autofill"}));
        assertFalse(ButlerWeeklyMatchupEvidenceBundleCli.validAutoFillArgs(
            new String[]{"league"}));
        assertFalse(ButlerWeeklyMatchupEvidenceBundleCli.validAutoFillArgs(
            new String[]{"league", "--matchup"}));
    }

    @Test
    void wrapperMapsDedicatedMatchupFlagsWithoutChangingMyTeamFlags() throws Exception {
        String wrapper = source("bet/bet-cli/src/main/java/io/butler/bet/cli/ButlerSleeperLiveWaiverTargetRosterContextAuditCli.java");

        assertTrue(wrapper.contains("--weekly-matchup-bundle"));
        assertTrue(wrapper.contains("--weekly-matchup-bundle-autofill"));
        assertTrue(wrapper.contains("ButlerWeeklyMatchupEvidenceBundleCli.main"));
        assertTrue(wrapper.contains("--team-bundle"));
        assertTrue(wrapper.contains("--team-bundle-autofill"));
        assertTrue(wrapper.contains("ButlerMyTeamEvidenceBundleCli.main"));
    }

    @Test
    void httpMatchupRouteUsesOneGovernedTaskAndConsumesBundledMatchup() throws Exception {
        String transform = source("scripts/butler-app-bf840-weekly-matchup-transform.ps1");
        int routeStart = transform.indexOf("$matchupRoute = @'");
        int routeEnd = transform.indexOf("'@", routeStart);
        assertTrue(routeStart >= 0 && routeEnd > routeStart);
        String route = transform.substring(routeStart, routeEnd);

        assertTrue(route.contains("--weekly-matchup-bundle"));
        assertTrue(route.contains("--weekly-matchup-bundle-autofill"));
        assertTrue(route.contains("Get-TeamEvidenceBundleSection -Text $bundleText -Name \"MATCHUP_CONTEXT\""));
        assertTrue(route.contains("ConvertTo-MatchupRosterContextView"));
        assertTrue(route.contains("Get-TeamEvidenceBundleSection -Text $bundleText -Name \"MATCHUP\""));
        assertTrue(route.contains("State: UNAVAILABLE"));
        assertTrue(route.contains("exact matchup frame does not match the bound roster frame"));
        assertTrue(occurrences(route, "Invoke-ButlerReadOnlyTask") == 1,
            "BF-849 Matchup route must use exactly one governed task call");
        assertFalse(route.contains(":bet:bet-cli:weeklyMatchupWorkspace"));
    }

    @Test
    void singleFlightAcceptanceUsesCurrentManagerMatchupStates() throws Exception {
        String acceptance = source("scripts/butler-matchup-single-flight-acceptance.ps1");

        assertTrue(acceptance.contains("OPPONENT CONFIRMED"));
        assertTrue(acceptance.contains("Opponent not confirmed"));
        assertFalse(acceptance.contains("PAIRING VERIFIED"));
        assertFalse(acceptance.contains("Opponent pairing unavailable"));
    }

    @Test
    void singleFlightAcceptanceSurfacesColdHttpFailureBody() throws Exception {
        String acceptance = source("scripts/butler-matchup-single-flight-acceptance.ps1");

        assertTrue(acceptance.contains("body=$plain"));
        assertTrue(acceptance.contains("WebUtility]::HtmlDecode"));
    }

    @Test
    void standaloneWeeklyMatchupWorkspaceRemainsAvailableForDiagnostics() throws Exception {
        String dispatch = source("scripts/butler-direct-java-dispatch.ps1");
        String build = source("bet/bet-cli/build.gradle.kts");

        assertTrue(dispatch.contains(":bet:bet-cli:weeklyMatchupWorkspace"));
        assertTrue(dispatch.contains("ButlerWeeklyMatchupWorkspaceCli"));
        assertTrue(build.contains("val weeklyMatchupWorkspace by tasks.registering(JavaExec::class)"));
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
        throw new IOException("BF-849 test could not locate " + relativePath);
    }
}
