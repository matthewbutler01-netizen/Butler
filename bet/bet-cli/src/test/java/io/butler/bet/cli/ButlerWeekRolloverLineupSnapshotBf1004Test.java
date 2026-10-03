package io.butler.bet.cli;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ButlerWeekRolloverLineupSnapshotBf1004Test {

    @Test
    void dashboardPublishesSavedLineupWeekForPostRenderReconciliation() throws Exception {
        String transform = source("scripts/butler-app-bf1004-week-rollover-lineup-snapshot-transform.ps1");

        assertTrue(transform.contains("butler-lineup-snapshot-frame"));
        assertTrue(transform.contains("data-lineup-week="));
        assertTrue(transform.contains("$bf1004SnapshotWeek = if ($null -ne $lineupSnapshot)"));
        assertTrue(transform.contains("dashboard-weekly-attention"));
        assertTrue(transform.contains("id=`\"weekly-attention`\""), "BF-987 Weekly Attention return anchor must be preserved");
    }

    @Test
    void currentMatchupWeekExpiresPriorWeeklyLineupAdvice() throws Exception {
        String transform = source("scripts/butler-app-bf1004-week-rollover-lineup-snapshot-transform.ps1");

        assertTrue(transform.contains("$currentMatchupWeek = [int]$matchup.Week"));
        assertTrue(transform.contains("$savedLineupWeek -ne $currentMatchupWeek"));
        assertTrue(transform.contains("Butler will not carry those player holds or lineup recommendations into Week"));
        assertTrue(transform.contains("Old week expired"));
        assertTrue(transform.contains("Old lineup advice expired"));
        assertTrue(transform.contains("Review Week $currentMatchupWeek lineup"));
    }

    @Test
    void rolloverUsesExistingDashboardMatchupReadOnly() throws Exception {
        String transform = source("scripts/butler-app-bf1004-week-rollover-lineup-snapshot-transform.ps1");
        int safetyScan = transform.indexOf("$installedText -match");
        assertTrue(safetyScan > 0);
        String operational = transform.substring(0, safetyScan);

        assertFalse(operational.contains("https://api.sleeper.app"));
        assertFalse(operational.contains("submitTransaction"));
        assertFalse(operational.contains("setFaab"));
        assertFalse(operational.contains("AutoFillLineupOptimizer"));
        assertFalse(operational.contains("returnUrl"));
        assertFalse(operational.contains("redirectUrl"));
        assertFalse(operational.contains("javascript:"));
        assertTrue(transform.contains("expected one existing read"));
    }

    @Test
    void presentationCloseoutIncludesBf1004() throws Exception {
        String closeout = source("scripts/butler-presentation-closeout-acceptance.ps1");
        assertTrue(closeout.contains("Id = 'BF-1004'"));
        assertTrue(closeout.contains("butler-bf1004-week-rollover-lineup-snapshot-acceptance.ps1"));
    }

    @Test
    void stagingRunsAfterCurrentWeekAndContrastFix() throws Exception {
        String staging = source("scripts/butler-dashboard-bf715-transform.ps1");
        int bf1003 = staging.indexOf("& $bf1003Transform -CorePath $stagedCore -DashboardPath $DashboardPath");
        int bf1004 = staging.indexOf("& $bf1004Transform -CorePath $stagedCore -DashboardPath $DashboardPath");

        assertTrue(bf1003 >= 0);
        assertTrue(bf1004 > bf1003);
    }

    @Test
    void bf1004SourcesRemainAscii() throws Exception {
        for (String path : new String[] {
            "scripts/butler-app-bf1004-week-rollover-lineup-snapshot-transform.ps1",
            "scripts/butler-bf1004-week-rollover-lineup-snapshot-acceptance.ps1"
        }) {
            String text = source(path);
            assertTrue(StandardCharsets.US_ASCII.newEncoder().canEncode(text));
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
        throw new IOException("BF-1004 test could not locate " + relativePath);
    }
}
