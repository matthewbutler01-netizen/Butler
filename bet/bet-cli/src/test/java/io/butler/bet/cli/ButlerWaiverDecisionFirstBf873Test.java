package io.butler.bet.cli;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ButlerWaiverDecisionFirstBf873Test {

    @Test
    void stagingRunsAfterFinalDashboardVisualAndRouteOverlays() throws Exception {
        String staging = source("scripts/butler-dashboard-bf715-transform.ps1");

        int bf837 = staging.indexOf("& $bf837DashboardTransform -DashboardPath $DashboardPath");
        int bf871 = staging.indexOf("& $bf871DashboardTransform -DashboardPath $DashboardPath");
        int bf873 = staging.indexOf("& $bf873DashboardTransform -DashboardPath $DashboardPath");
        int bf857 = staging.indexOf("# BF-857: diagnostic-only inner-core timing runs last");

        assertTrue(bf837 >= 0, "BF-837 final dashboard visual staging must remain present");
        assertTrue(bf873 > bf837, "BF-873 must run after the final Waiver visual alignment");
        assertTrue(bf871 < 0 || bf873 > bf871, "BF-873 must run after the full-app Dashboard overlay when present");
        assertTrue(bf857 > bf873, "timing diagnostics must observe the final BF-873 presentation");
        assertTrue(staging.contains("butler-dashboard-bf873-waiver-decision-first-transform.ps1"));
    }

    @Test
    void firstScanLeadsWithDecisionPairAndNextStepNotRawAuditData() throws Exception {
        String transform = source("scripts/butler-dashboard-bf873-waiver-decision-first-transform.ps1");
        String hero = hereString(transform, "$heroNew = @'", "'@\n$waiverBlock");

        int details = hero.indexOf("<details class=\"waiver-decision-details\">");
        assertTrue(details > 0, "BF-873 decision disclosure must exist");
        String firstScan = hero.substring(0, details);

        assertTrue(firstScan.contains("Butler waiver decision"));
        assertTrue(firstScan.contains("$waiverPairHtml"));
        assertTrue(firstScan.contains("<strong>Next step</strong>"));
        assertTrue(firstScan.contains("$waiverHistoryLink"));

        assertFalse(firstScan.contains("Governed state:"));
        assertFalse(firstScan.contains("Current audit ID:"));
        assertFalse(firstScan.contains("Sleeper ID:"));
        assertFalse(firstScan.contains("href=\"/team\""));
        assertFalse(firstScan.contains("href=\"/\""));
    }

    @Test
    void rawDecisionAndPairIdentityRemainAvailableBehindDisclosure() throws Exception {
        String transform = source("scripts/butler-dashboard-bf873-waiver-decision-first-transform.ps1");

        assertTrue(transform.contains("<summary>Decision details</summary>"));
        assertTrue(transform.contains("Governed state: $(ConvertTo-HtmlText $current.State)"));
        assertTrue(transform.contains("Current audit ID: $(ConvertTo-HtmlText $current.AuditId)"));
        assertTrue(transform.contains("Pair ADD Sleeper ID: $(ConvertTo-HtmlText $pair.AddSleeperId)"));
        assertTrue(transform.contains("Pair DROP Sleeper ID: $(ConvertTo-HtmlText $pair.DropSleeperId)"));

        assertTrue(transform.contains("$addMetaNew = '$(ConvertTo-HtmlText $pair.Add.Position) &middot; NFL $(ConvertTo-HtmlText $pair.Add.Team)'"));
        assertTrue(transform.contains("$dropMetaNew = '$(ConvertTo-HtmlText $pair.Drop.Position) &middot; NFL $(ConvertTo-HtmlText $pair.Drop.Team)'"));
        assertTrue(transform.contains("Butler''s exact governed pair; board order does not create this decision."));
    }

    @Test
    void historyIsContextualAndCandidatePoolRemainsNonRanking() throws Exception {
        String transform = source("scripts/butler-dashboard-bf873-waiver-decision-first-transform.ps1");

        for (String state : new String[] {
            "CURRENT_AND_ACTIONABLE",
            "CURRENT_REFRESH_RECOMMENDED",
            "TRANSACTION_ALREADY_COMPLETE",
            "TRANSACTION_PENDING_DO_NOT_DUPLICATE",
            "STALE_DO_NOT_ACT",
            "NO_TRANSACTION_TO_ACT_ON"
        }) {
            assertTrue(transform.contains("\"" + state + "\""), "missing contextual history state " + state);
        }

        assertTrue(transform.contains("waiver-history-link"));
        assertTrue(transform.contains("href=\"/history\">Decision History</a>"));
        assertTrue(transform.contains("'Authorized review pool'"));
        assertTrue(transform.contains("'NOT A RANKING.'"));
        assertTrue(transform.contains("'Technical and audit details'"));
    }

    @Test
    void bf850AcceptanceTracksCurrentDecisionFirstMarkers() throws Exception {
        String acceptance = source("scripts/butler-waiver-shared-snapshot-acceptance.ps1");

        assertTrue(acceptance.contains("'Butler waiver decision'"));
        assertTrue(acceptance.contains("'Next step'"));
        assertTrue(acceptance.contains("'Authorized review pool'"));
        assertTrue(acceptance.contains("'Technical and audit details'"));
        assertTrue(acceptance.contains("'READ ONLY'"));
        assertFalse(acceptance.contains("'Decision details'"));
        assertFalse(acceptance.contains("'What to do now'"));
    }

    @Test
    void polishRemainsPresentationOnlyAndFailClosed() throws Exception {
        String transform = source("scripts/butler-dashboard-bf873-waiver-decision-first-transform.ps1");

        int safetyScan = transform.indexOf("$installedWaiver -match");
        assertTrue(safetyScan > 0, "BF-873 safety scan must remain present");
        String operational = transform.substring(0, safetyScan);

        for (String forbidden : new String[] {
            "Invoke-RestMethod",
            "Invoke-WebRequest",
            "https://api.sleeper.app",
            "Method = \"POST\"",
            "submitTransaction",
            "setFaab",
            "AutoFillLineupOptimizer"
        }) {
            assertFalse(operational.contains(forbidden),
                "BF-873 operational transform introduced provider/write behavior " + forbidden);
        }

        assertTrue(transform.contains("BF-873 is presentation-only"));
        assertTrue(transform.contains("Waiver polish introduced provider, optimizer, FAAB, or write behavior"));
        assertTrue(transform.contains("generated Dashboard failed PowerShell parse"));
    }

    private static String hereString(String text, String startMarker, String endMarker) {
        String normalized = text.replace("\r\n", "\n");
        int start = normalized.indexOf(startMarker);
        assertTrue(start >= 0, "start marker missing: " + startMarker);
        start += startMarker.length();
        int end = normalized.indexOf(endMarker, start);
        assertTrue(end > start, "end marker missing: " + endMarker);
        return normalized.substring(start, end);
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
        throw new IOException("BF-873 test could not locate " + relativePath);
    }
}
