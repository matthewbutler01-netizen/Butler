package io.butler.bet.cli;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ButlerCurrentWeekAndDarkContrastBf1003Test {

    @Test
    void bothRecoveryPathsSynchronizeCurrentMatchupBeforeFinalVerification() throws Exception {
        for (String path : new String[] {
            "scripts/butler-recover-roster-drift.ps1",
            "scripts/butler-lineup-evidence-recovery.ps1"
        }) {
            String script = source(path);
            int sync = script.indexOf("BF-840 current weekly matchup sync");
            int roster = script.indexOf("BF-610 post-recovery target-roster verification");
            assertTrue(sync >= 0, path + " must synchronize the current matchup");
            assertTrue(roster > sync, path + " must sync current matchup before final roster verification");
            assertTrue(script.contains("ButlerSleeperCurrentWeekMatchupSyncCli"));
        }
    }

    @Test
    void finalUiContrastTransformCoversCoreAndDashboard() throws Exception {
        String transform = source("scripts/butler-app-bf1003-current-week-dark-contrast-transform.ps1");

        assertTrue(transform.contains("function Get-AppCss {"));
        assertTrue(transform.contains("function Get-SharedCss {"));
        assertTrue(transform.contains("color:#A8D3B5!important"));
        assertTrue(transform.contains("color:#B9DCC3!important"));
        assertTrue(transform.contains("background:#69A27D!important"));
        assertTrue(transform.contains("color:#C1CAC4!important"));
        assertTrue(transform.contains("text-decoration:underline"));
    }

    @Test
    void stagingRunsAfterFinalPlayerHubPolish() throws Exception {
        String staging = source("scripts/butler-dashboard-bf715-transform.ps1");
        int bf1001 = staging.indexOf("& $bf1001Transform -CorePath $stagedCore");
        int bf1003 = staging.indexOf("& $bf1003Transform -CorePath $stagedCore -DashboardPath $DashboardPath");

        assertTrue(bf1001 >= 0);
        assertTrue(bf1003 > bf1001);
    }

    @Test
    void presentationCloseoutIncludesBf1003() throws Exception {
        String closeout = source("scripts/butler-presentation-closeout-acceptance.ps1");
        assertTrue(closeout.contains("Id = 'BF-1003'"));
        assertTrue(closeout.contains("butler-bf1003-current-week-dark-contrast-acceptance.ps1"));
    }

    @Test
    void contrastTransformAddsNoOperationalBehavior() throws Exception {
        String transform = source("scripts/butler-app-bf1003-current-week-dark-contrast-transform.ps1");
        for (String forbidden : new String[] {
            "https://api.sleeper.app",
            "submitTransaction",
            "setFaab",
            "AutoFillLineupOptimizer",
            "returnUrl",
            "redirectUrl",
            "javascript:"
        }) {
            assertFalse(transform.contains(forbidden), "contrast transform introduced forbidden behavior " + forbidden);
        }
    }

    @Test
    void bf1003SourcesRemainAsciiOnly() throws Exception {
        for (String path : new String[] {
            "scripts/butler-app-bf1003-current-week-dark-contrast-transform.ps1",
            "scripts/butler-bf1003-current-week-dark-contrast-acceptance.ps1"
        }) {
            String script = source(path);
            assertTrue(StandardCharsets.US_ASCII.newEncoder().canEncode(script), path + " must remain ASCII");
        }
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
        throw new IOException("BF-1003 test could not locate " + relativePath);
    }
}
