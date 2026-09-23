package io.butler.bet.cli;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ButlerLeagueHubAutofillBoundaryBf913Test {

    @Test
    void leagueHubStopsBeforeSharedAutoFillHelpers() throws Exception {
        String transform = source("scripts/butler-app-bf909-league-hub-transform.ps1");

        assertTrue(transform.contains(
            "$leagueEndMarker = 'function New-AutoFillIdleView {'"));
        assertTrue(transform.contains(
            "$leagueEnd = $core.IndexOf($leagueEndMarker, $leagueStart"));
        assertTrue(transform.contains(
            "$installedEnd = $core.IndexOf($leagueEndMarker, $installedStart"));

        assertFalse(transform.contains(
            "$leagueEnd = $core.IndexOf('function ConvertTo-TeamHtml {'"));
        assertFalse(transform.contains(
            "$installedEnd = $core.IndexOf('function ConvertTo-TeamHtml {'"));
    }

    @Test
    void leagueHubExplicitlyGuardsAllSharedLineupHelpers() throws Exception {
        String transform = source("scripts/butler-app-bf909-league-hub-transform.ps1");

        for (String helper : new String[] {
            "function New-AutoFillIdleView {",
            "function ConvertTo-AutoFillView {",
            "function ConvertTo-AutoFillHtml {"
        }) {
            assertTrue(transform.contains(helper),
                "BF-913 preservation guard missing " + helper);
        }

        assertTrue(transform.contains(
            "BF-913 BLOCKED: League Hub staging removed required shared helper"));
        assertTrue(transform.contains(
            "League renderer end must stop before the AutoFill helper region"));
    }

    @Test
    void productionStagingStillRunsLeagueHubAfterLineupAndBeforeRecovery() throws Exception {
        String staging = source("scripts/butler-dashboard-bf715-transform.ps1");

        int bf800 = staging.indexOf("& $bf800Transform -CorePath $stagedCore");
        int bf909 = staging.indexOf("& $bf909CoreTransform -CorePath $stagedCore");
        int bf884 = staging.indexOf("& $bf884CoreTransform -CorePath $stagedCore");

        assertTrue(bf800 >= 0, "BF-800 AutoFill staging marker missing");
        assertTrue(bf909 > bf800,
            "BF-909 must run after AutoFill helpers are installed");
        assertTrue(bf884 > bf909,
            "BF-884 recovery polish must remain after BF-909");
    }

    @Test
    void repairDoesNotBroadenLeagueHubOperationalBoundary() throws Exception {
        String transform = source("scripts/butler-app-bf909-league-hub-transform.ps1");

        for (String forbidden : new String[] {
            "Invoke-RestMethod",
            "Invoke-WebRequest",
            "https://api.sleeper.app",
            "Method = \"POST\"",
            "submitTransaction",
            "setFaab",
            "AutoFillLineupOptimizer"
        }) {
            int safetyScan = transform.indexOf("$installedLeague -match");
            assertTrue(safetyScan > 0);
            assertFalse(transform.substring(0, safetyScan).contains(forbidden),
                "BF-913 repair introduced forbidden operational marker " + forbidden);
        }
    }

    private static String source(String relativePath) throws IOException {
        Path current = Path.of(System.getProperty("user.dir"))
            .toAbsolutePath()
            .normalize();

        for (int depth = 0; depth < 7 && current != null; depth++) {
            Path candidate = current.resolve(relativePath);
            if (Files.isRegularFile(candidate)) {
                return Files.readString(candidate, StandardCharsets.UTF_8)
                    .replace("\r\n", "\n");
            }
            current = current.getParent();
        }

        throw new IOException("BF-913 test could not locate " + relativePath);
    }
}
