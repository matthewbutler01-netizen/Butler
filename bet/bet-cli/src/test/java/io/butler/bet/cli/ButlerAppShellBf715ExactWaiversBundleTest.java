package io.butler.bet.cli;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ButlerAppShellBf715ExactWaiversBundleTest {

    @Test
    void stagedWaiversRouteUsesOneThreeSourceBundleCall() throws Exception {
        String transform = source("scripts/butler-dashboard-bf715-transform.ps1");

        assertTrue(transform.contains("elseif ($path -eq \"/waivers\")"));
        assertTrue(transform.contains("$waiverEvidence = Invoke-ButlerReadOnlyWaiverEvidenceBundle"));
        assertTrue(transform.contains("$summary = $waiverEvidence.Summary"));
        assertTrue(transform.contains("$waiverBundle = $waiverEvidence.WaiverBoard"));
        assertTrue(transform.contains("$rosterContext = $waiverEvidence.RosterContext"));
        assertTrue(transform.contains("\"--args=$LeagueId --waiver-dashboard-bundle\""));
        assertTrue(transform.contains("Get-Bf715WaiverBundleSection -Text $text -Name \"SUMMARY\""));
        assertTrue(transform.contains("Get-Bf715WaiverBundleSection -Text $text -Name \"WAIVER_BOARD\""));
        assertTrue(transform.contains("Get-Bf715WaiverBundleSection -Text $text -Name \"ROSTER_CONTEXT\""));
    }

    @Test
    void stagingTransformFailsClosedOnDashboardSourceDrift() throws Exception {
        String transform = source("scripts/butler-dashboard-bf715-transform.ps1");

        assertTrue(transform.contains("$helperMatches -ne 1"));
        assertTrue(transform.contains("$routeMatches -ne 1"));
        assertTrue(transform.contains("staged /waivers route still contains sequential governed reads"));
        assertTrue(transform.contains("staged dashboard is missing the exact waiver evidence bundle helper"));
    }

    @Test
    void outerCoreRunsTransformBeforeStartingPreservedWorkers() throws Exception {
        String core = source("scripts/butler-app-shell-core.ps1");

        int transformSource = core.indexOf("butler-dashboard-bf715-transform.ps1");
        int copyDashboard = core.indexOf("Copy-Item -LiteralPath $dashboardSource -Destination $runtimeDashboard -Force");
        int runTransform = core.indexOf("& $dashboardTransformSource -DashboardPath $runtimeDashboard");
        int startWorkers = core.indexOf("for ($index = 0; $index -lt $maxCoreWorkers; $index++)");
        assertTrue(transformSource >= 0);
        assertTrue(copyDashboard >= 0 && runTransform > copyDashboard);
        assertTrue(startWorkers > runTransform);
    }

    @Test
    void homepageTeamAndCandidateSourceRoutesRemainUnchanged() throws Exception {
        String transform = source("scripts/butler-dashboard-bf715-transform.ps1");

        assertFalse(transform.contains("if ($path -eq \"/team\")"));
        assertFalse(transform.contains("$candidateMatch.Success"));
        assertFalse(transform.contains("ConvertTo-TeamHtml"));
        assertFalse(transform.contains("Resolve-WaiverCandidateById"));
    }

    @Test
    void dispatcherWhitelistStaysFailClosedAndRouteAgnostic() throws Exception {
        String dispatch = source("scripts/butler-direct-java-dispatch.ps1");

        assertTrue(dispatch.contains("':bet:bet-cli:sleeperLiveWaiverTargetRosterContextAudit'"));
        assertTrue(dispatch.contains("':bet:bet-cli:sleeperLiveWaiverLatestGovernedDecisionSummary'"));
        assertTrue(dispatch.contains("':bet:bet-cli:sleeperLiveWaiverComparisonBundle'"));
        assertTrue(dispatch.contains("':bet:bet-cli:sleeperLiveWaiverGovernedExplanationLookup'"));
        assertTrue(dispatch.contains("default { $null }"));
        assertFalse(dispatch.contains("Get-ButlerDashboardAncestorPid"));
        assertFalse(dispatch.contains(".bf713-waiver-context-cache"));
        assertFalse(dispatch.contains("--waiver-board-context-bundle"));
    }

    @Test
    void windowsSourcesRemainAsciiOnly() throws Exception {
        for (String path : new String[]{
            "scripts/butler-dashboard-bf715-transform.ps1",
            "scripts/butler-app-shell-core.ps1",
            "scripts/butler-direct-java-dispatch.ps1"}) {
            String text = source(path);
            assertEquals(text, new String(text.getBytes(StandardCharsets.US_ASCII), StandardCharsets.US_ASCII), path);
        }
    }

    @Test
    void noWriteBoundaryIsExpanded() throws Exception {
        String transform = source("scripts/butler-dashboard-bf715-transform.ps1");
        String dispatch = source("scripts/butler-direct-java-dispatch.ps1");
        String combined = transform + "\n" + dispatch;

        assertFalse(combined.contains("/refresh"));
        assertFalse(combined.contains("create_transaction"));
        assertFalse(combined.contains("submitTransaction"));
        assertFalse(combined.contains("waiver_budget"));
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
        throw new IOException("BF-715 test could not locate " + relativePath);
    }
}
