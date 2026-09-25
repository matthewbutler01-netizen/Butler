package io.butler.bet.cli;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ButlerManagerJourneyStabilizationBf912Test {

    @Test
    void journeyTracksAllSevenCurrentManagerFirstPages() throws Exception {
        String script = source("scripts/butler-manager-journey-acceptance.ps1");

        for (String marker : new String[]{
                "BF-912 contract: current all-seven-page manager-first stabilization",
                "Markers @('Priority 01','Week at a glance','Your fantasy week in one view','After Priority 01','Other priorities')",
                "Markers @('Roster hub','Lineup and depth at a glance','Player Search','Player Compare','Roster construction','<h2>Draft capital</h2>')",
                "Markers @('Weekly matchup','What to do now','READ ONLY')",
                "Markers @('Butler waiver decision','Next step','Players Butler authorized for review','NOT A RANKING.','READ ONLY')",
                "Markers @('League hub','Top franchise snapshot','League pulse','Movement at a glance','Risers','Fallers','Tracked movers','View movement details','READ ONLY')",
                "Markers @('Analyze a trade','Trade partner','No new trade score is created here.','READ ONLY')",
                "Markers @('Your waiver decision timeline','Latest outcome','Latest recorded','Newest first','READ ONLY')",
                "BF-912 RESULT: COMPLETE"
        }) {
            assertTrue(script.contains(marker), "BF-912 current manager marker missing " + marker);
        }
    }

    @Test
    void everyPrimaryPageMustExposeTheSameSevenDestinationNavigation() throws Exception {
        String script = source("scripts/butler-manager-journey-acceptance.ps1");

        assertTrue(script.contains("function Assert-PrimaryNavigation"));
        for (String marker : new String[]{
                "href=\"/\">Dashboard</a>",
                "href=\"/team\">My Team</a>",
                "href=\"/matchup\">Matchup</a>",
                "href=\"/waivers\">Waiver Board</a>",
                "href=\"/league\">League</a>",
                "href=\"/trade\">Trade Analyzer</a>",
                "href=\"/history\">History</a>"
        }) {
            assertTrue(script.contains(marker), "BF-912 nav contract missing " + marker);
        }

        for (String stage : new String[]{
                "Dashboard",
                "My Team",
                "Matchup",
                "Waiver Board",
                "League",
                "Trade Analyzer",
                "Decision History"
        }) {
            assertTrue(script.contains("Assert-PrimaryNavigation -Html"), "BF-912 primary nav assertion missing");
            assertTrue(script.contains("-Stage '" + stage + "'"), "BF-912 nav stage missing " + stage);
        }
    }

    @Test
    void hiddenTechnicalProofDoesNotCountAsFirstScanLeakage() throws Exception {
        String script = source("scripts/butler-manager-journey-acceptance.ps1");

        assertTrue(script.contains("function Get-ManagerFirstScanHtml"));
        assertTrue(script.contains("(?is)<details\\b[^>]*>.*?</details>"));
        assertTrue(script.contains("$waiverFirstScan = Get-ManagerFirstScanHtml -Html $waivers.Body"));
        assertTrue(script.contains("$historyFirstScan = Get-ManagerFirstScanHtml -Html $history.Body"));

        assertTrue(script.contains("Waiver Board first scan"));
        assertTrue(script.contains("Decision History first scan"));
        assertTrue(script.contains("Current audit ID:"));
        assertTrue(script.contains("ADD / DROP Sleeper ids:"));
    }

    @Test
    void exactStagedTeamRenderDiagnosticUsesLiveReadOnlyBundleAndFinalTransforms() throws Exception {
        String script = source("scripts/butler-bf912-team-render-diagnostic.ps1");

        assertTrue(script.contains("butler-dashboard-bf715-transform.ps1"));
        assertTrue(script.contains("ButlerMyTeamEvidenceBundleCli"));
        assertTrue(script.contains("BF-912 STAGING: PASS"));
        assertTrue(script.contains("BF-912 DIRECT TEAM BUNDLE: PASS"));
        assertTrue(script.contains("ROSTER_CONTEXT parser"));
        assertTrue(script.contains("TEAM_CONTEXT parser"));
        assertTrue(script.contains("ROSTER_STRENGTH parser"));
        assertTrue(script.contains("POSITIONAL_PRESSURE parser"));
        assertTrue(script.contains("TEAM_POSTURE parser"));
        assertTrue(script.contains("FUTURE_CAPITAL parser"));
        assertTrue(script.contains("FINAL ConvertTo-TeamHtml"));
        assertTrue(script.contains("BF-912 TEAM RENDER RESULT: COMPLETE"));

        for (String forbidden : new String[]{
                "Invoke-RestMethod",
                "Invoke-WebRequest",
                "Method = 'POST'",
                "https://api.sleeper.app",
                "submitTransaction",
                "setFaab"
        }) {
            assertFalse(script.contains(forbidden), "BF-912 staged-render diagnostic introduced forbidden action " + forbidden);
        }
    }

    @Test
    void recoveryTechnicalDetailExtractionSurvivesDecoratedDisclosureMarkup() throws Exception {
        String script = source("scripts/butler-manager-journey-acceptance.ps1");

        assertTrue(script.contains("function Get-ManagerRecoveryTechnicalDetail"));
        assertTrue(script.contains("(?is)<details\\b[^>]*>\\s*<summary\\b[^>]*>\\s*Technical details"));
        assertTrue(script.contains("class=\"[^\"]*\\btechnical\\b[^\"]*\""));
        assertTrue(script.contains("$plain.IndexOf('Technical details'"));
        assertTrue(script.contains("Last-resort manager-recovery fallback"));
    }

    @Test
    void liveMyTeamFailureRunsExactReadOnlyEvidenceBundleDiagnostic() throws Exception {
        String script = source("scripts/butler-manager-journey-acceptance.ps1");

        assertTrue(script.contains("function Invoke-MyTeamDirectDiagnostic"));
        assertTrue(script.contains("io.butler.bet.cli.ButlerMyTeamEvidenceBundleCli"));
        assertTrue(script.contains("direct-team-bundle exit="));
        assertTrue(script.contains("if ($team.StatusCode -ne 200)"));
        assertTrue(script.contains("$teamTechnical = Get-ManagerRecoveryTechnicalDetail"));
        assertTrue(script.contains("$teamDirect = Invoke-MyTeamDirectDiagnostic"));
        assertTrue(script.contains("recovery-body="));
        assertTrue(script.contains("$teamPlainHtml = [regex]::Replace"));
        assertTrue(script.contains("Stop-OwnedButler -Process $process -Port $port"));
        assertTrue(script.contains("$teamProcessOutput = Get-BoundedStartupOutput -Process $process"));
        assertTrue(script.contains("process-output="));
        assertTrue(script.contains("BF-912 FAILED: My Team returned HTTP"));
    }

    @Test
    void leagueJourneyRejectsRawMovementSyntax() throws Exception {
        String script = source("scripts/butler-manager-journey-acceptance.ps1");

        assertTrue(script.contains("Assert-AbsentMarkers -Html $league.Body -Stage 'League' -Markers @('fantasy-team=')"));
    }

    @Test
    void stabilizationRemainsGetOnlyAndKeepsRecoveryAndHealth() throws Exception {
        String script = source("scripts/butler-manager-journey-acceptance.ps1");

        assertTrue(script.contains("$request.Method = 'GET'"));
        assertTrue(script.contains("no /refresh, no POST, no lineup/waiver/trade execution, no Sleeper write"));
        assertTrue(script.contains("/__bf885_not_found__"));
        assertTrue(script.contains("Health before journey"));
        assertTrue(script.contains("Health after journey"));
        assertTrue(script.contains("Working tree: CLEAN"));

        for (String forbidden : new String[]{
                "Method = 'POST'",
                "Method = \"POST\"",
                "submitTransaction",
                "setFaab",
                "Invoke-RestMethod",
                "Invoke-WebRequest"
        }) {
            assertFalse(script.contains(forbidden), "BF-912 introduced forbidden action " + forbidden);
        }
    }

    @Test
    void stabilizationChangesAcceptanceOnly() throws Exception {
        String issueBoundary = source("scripts/butler-manager-journey-acceptance.ps1");

        assertFalse(issueBoundary.contains("https://api.sleeper.app"));
        assertFalse(issueBoundary.contains("sleeperCurrentWeekMatchupSync"));
        assertFalse(issueBoundary.contains("sleeperLiveWaiverRecommendationAuditCapture"));
    }

    private static String source(String relativePath) throws IOException {
        Path current = Path.of(System.getProperty("user.dir")).toAbsolutePath().normalize();
        for (int depth = 0; depth < 7 && current != null; depth++) {
            Path candidate = current.resolve(relativePath);
            if (Files.isRegularFile(candidate)) {
                return Files.readString(candidate, StandardCharsets.UTF_8)
                    .replace("\r\n", "\n");
            }
            current = current.getParent();
        }
        throw new IOException("BF-912 test could not locate " + relativePath);
    }
}
