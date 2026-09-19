package io.butler.bet.cli;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ButlerDashboardDecisionFirstPolishBf871Test {

    @Test
    void heroCapturesExactResolvedPriorityOneCard() throws Exception {
        String transform = source("scripts/butler-dashboard-bf871-decision-first-polish-transform.ps1");

        assertTrue(transform.contains("if ($managerIndex -eq 0)"));
        assertTrue(transform.contains("$bf871HeroKind = [string]$kind"));
        assertTrue(transform.contains("$bf871HeroTitle = [string]$title"));
        assertTrue(transform.contains("$bf871HeroCopy = [string]$copy"));
        assertTrue(transform.contains("$bf871HeroStatus = [string]$status"));
        assertTrue(transform.contains("$bf871HeroStatusClass = [string]$statusClass"));
        assertTrue(transform.contains("$bf871HeroActionHref = [string]$actionHref"));
        assertTrue(transform.contains("$bf871HeroActionLabel = [string]$actionLabel"));
    }

    @Test
    void heroShowsDecisionStatusPrimaryActionAndExistingDetailsJump() throws Exception {
        String transform = source("scripts/butler-dashboard-bf871-decision-first-polish-transform.ps1");

        assertTrue(transform.contains("Priority 01 &middot; $(ConvertTo-HtmlText $bf871HeroKind)"));
        assertTrue(transform.contains("<h1 class=\"command-title\">$(ConvertTo-HtmlText $bf871HeroTitle)</h1>"));
        assertTrue(transform.contains("<p class=\"command-copy\">$(ConvertTo-HtmlText $bf871HeroCopy)</p>"));
        assertTrue(transform.contains("href=\"$(ConvertTo-HtmlText $bf871HeroActionHref)\">$(ConvertTo-HtmlText $bf871HeroActionLabel)</a>"));
        assertTrue(transform.contains("href=\"#decision-details\">View decision details</a>"));
        assertTrue(transform.contains("<div class=\"status $bf871HeroStatusClass\">$(ConvertTo-HtmlText $bf871HeroStatus)</div>"));
        assertTrue(transform.contains("<h2>Your decision queue</h2>"));
        assertTrue(transform.contains("id=\"decision-details\""));
    }

    @Test
    void stagingRunsAfterMatchupRoutingAndBeforeTimingDiagnostics() throws Exception {
        String staging = source("scripts/butler-dashboard-bf715-transform.ps1");

        int bf843 = staging.indexOf("& $bf843DashboardTransform -DashboardPath $DashboardPath");
        int bf871 = staging.indexOf("& $bf871DashboardTransform -DashboardPath $DashboardPath");
        int bf857 = staging.indexOf("# BF-857: diagnostic-only inner-core timing runs last");

        assertTrue(bf843 >= 0, "BF-843 staging marker missing");
        assertTrue(bf871 > bf843, "BF-871 must run after BF-843 finalizes Matchup-aware action routing");
        assertTrue(bf857 > bf871, "BF-857 diagnostics must remain after BF-871 final presentation");
    }

    @Test
    void polishRemainsPresentationOnly() throws Exception {
        String transform = source("scripts/butler-dashboard-bf871-decision-first-polish-transform.ps1");

        for (String forbidden : new String[] {
            "Invoke-RestMethod",
            "Invoke-WebRequest",
            "https://api.sleeper.app",
            "Method = \"POST\"",
            "submitTransaction",
            "setFaab",
            "AutoFillLineupOptimizer"
        }) {
            assertFalse(transform.contains(forbidden),
                "BF-871 transform introduced provider/write marker " + forbidden);
        }

        assertTrue(transform.contains("BF-871 is presentation-only"));
        assertTrue(transform.contains("generated Dashboard failed PowerShell parse"));
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
        throw new IOException("BF-871 test could not locate " + relativePath);
    }
}
