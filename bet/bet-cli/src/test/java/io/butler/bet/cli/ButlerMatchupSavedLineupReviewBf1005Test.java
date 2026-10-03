package io.butler.bet.cli;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ButlerMatchupSavedLineupReviewBf1005Test {

    @Test
    void savedCurrentReviewHydratesMatchupInsteadOfIdleState() throws Exception {
        String t = source("scripts/butler-app-bf1005-matchup-saved-lineup-review-transform.ps1");
        assertTrue(t.contains("function Get-Bf1005SavedLineupReview"));
        assertTrue(t.contains("function ConvertTo-Bf1005SavedLineupReviewHtml"));
        assertTrue(t.contains("The saved Week $week Lineup Review is current for this roster"));
        assertTrue(t.contains("Get-Bf1005SavedLineupReview -LeagueId ([string]$LeagueId)"));
        assertTrue(t.contains("-SavedReview $savedReview"));
        assertTrue(t.contains("No new provider request was made to display it."));
    }

    @Test
    void savedReviewRequiresExactRosterWeekAndFreshTimestamp() throws Exception {
        String t = source("scripts/butler-app-bf1005-matchup-saved-lineup-review-transform.ps1");
        assertTrue(t.contains("ConvertTo-Bf1005TargetKey"));
        assertTrue(t.contains("$savedWeek -ne $Week"));
        assertTrue(t.contains("$ageHours -lt -0.1 -or $ageHours -gt 6.0"));
        assertTrue(t.contains("[string]$snapshot.LeagueId -cne $LeagueId"));
    }

    @Test
    void stagingRunsAfterWeekRolloverReconciliation() throws Exception {
        String s = source("scripts/butler-dashboard-bf715-transform.ps1");
        int a = s.indexOf("& $bf1004Transform -CorePath $stagedCore -DashboardPath $DashboardPath");
        int b = s.indexOf("& $bf1005Transform -CorePath $stagedCore");
        assertTrue(a >= 0);
        assertTrue(b > a);
    }

    @Test
    void addedSurfaceDoesNotAddProviderOrWritePath() throws Exception {
        String t = source("scripts/butler-app-bf1005-matchup-saved-lineup-review-transform.ps1");
        int safety = t.indexOf("$addedSurface -match");
        assertTrue(safety > 0);
        String operational = t.substring(0, safety);
        assertFalse(operational.contains("https://api.sleeper.app"));
        assertFalse(operational.contains("submitTransaction"));
        assertFalse(operational.contains("setFaab"));
        assertFalse(operational.contains("AutoFillLineupOptimizer"));
        assertFalse(operational.contains("javascript:"));
    }

    @Test
    void bf1005SourcesRemainAscii() throws Exception {
        for (String path : new String[] {
            "scripts/butler-app-bf1005-matchup-saved-lineup-review-transform.ps1",
            "scripts/butler-bf1005-matchup-saved-lineup-review-acceptance.ps1"
        }) {
            assertTrue(StandardCharsets.US_ASCII.newEncoder().canEncode(source(path)));
        }
    }

    private static String source(String relativePath) throws IOException {
        Path current = Path.of(System.getProperty("user.dir")).toAbsolutePath().normalize();
        for (int depth = 0; depth < 7 && current != null; depth++) {
            Path candidate = current.resolve(relativePath);
            if (Files.isRegularFile(candidate)) return Files.readString(candidate, StandardCharsets.UTF_8);
            current = current.getParent();
        }
        throw new IOException("BF-1005 test could not locate " + relativePath);
    }
}
