package io.butler.bet.cli;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ButlerMobileManagerPolishBf898Test {

    @Test
    void mobileNavigationKeepsManagerTabsSwipeableWithoutDesktopScrollbar() throws Exception {
        String transform = source("scripts/butler-bf898-mobile-manager-polish-transform.ps1");

        assertTrue(transform.contains("BF-898 mobile manager polish"));
        assertTrue(transform.contains("flex-wrap:nowrap!important"));
        assertTrue(transform.contains("overflow-x:auto"));
        assertTrue(transform.contains("scrollbar-width:none"));
        assertTrue(transform.contains(".nav::-webkit-scrollbar{display:none"));
        assertTrue(transform.contains("min-height:44px"));
        assertTrue(transform.contains("white-space:nowrap"));
        assertTrue(transform.contains("scroll-snap-type:x proximity"));
    }

    @Test
    void staleLineupStateUsesManagerFirstRefreshCopy() throws Exception {
        String transform = source("scripts/butler-bf898-mobile-manager-polish-transform.ps1");

        assertTrue(transform.contains("Lineup needs a fresh review"));
        assertTrue(transform.contains("Your roster or weekly projection frame changed since the saved lineup review."));
        assertTrue(transform.contains("Refresh it before relying on the recommendation."));
        assertTrue(transform.contains("\"Refresh Lineup\""));
    }

    @Test
    void polishRunsAfterFinalDashboardPresentationAndBeforeDiagnostics() throws Exception {
        String staging = source("scripts/butler-dashboard-bf715-transform.ps1");

        int bf873 = staging.indexOf("& $bf873DashboardTransform -DashboardPath $DashboardPath");
        int bf898 = staging.indexOf("& $bf898Transform -DashboardPath $DashboardPath");
        int bf857 = staging.indexOf("# BF-857: diagnostic-only inner-core timing runs last");

        assertTrue(bf873 >= 0, "BF-873 staging marker missing");
        assertTrue(bf898 > bf873, "BF-898 must run after final Waiver/Dashboard presentation polish");
        assertTrue(bf857 > bf898, "BF-857 diagnostics must remain after BF-898 final presentation");
    }

    @Test
    void polishTouchesPresentationOnly() throws Exception {
        String transform = source("scripts/butler-bf898-mobile-manager-polish-transform.ps1");

        int safetyScan = transform.indexOf("$installedDashboard -match");
        assertTrue(safetyScan > 0, "BF-898 safety scan must remain present");
        String operational = transform.substring(0, safetyScan);

        for (String forbidden : new String[] {
            "Invoke-RestMethod",
            "Invoke-WebRequest",
            "https://api.sleeper.app",
            "Method = \"POST\"",
            "submitTransaction",
            "setFaab"
        }) {
            assertFalse(operational.contains(forbidden),
                "BF-898 operational transform introduced provider/write marker " + forbidden);
        }

        assertTrue(transform.contains("generated manager surface failed PowerShell parse"));
        assertTrue(transform.contains("mobile manager polish introduced a write-path marker"));
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
        throw new IOException("BF-898 test could not locate " + relativePath);
    }
}
