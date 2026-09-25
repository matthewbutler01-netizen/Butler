package io.butler.bet.cli;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ButlerLineupCompareReturnBf944Test {

    @Test
    void lineupSwapCompareAddsFixedContextAndSafeReturn() throws Exception {
        String transform = source("scripts/butler-app-bf944-lineup-compare-return-transform.ps1");

        assertTrue(transform.contains("&from=lineup"));
        assertTrue(transform.contains("from context may only be from=lineup"));
        assertTrue(transform.contains("lineup compare context requires two exact players"));
        assertTrue(transform.contains("FromLineup = $fromLineup"));
        assertTrue(transform.contains("Back to Lineup Review"));
        assertTrue(transform.contains("href=\"/team/autofill\""));
    }

    @Test
    void lineupContextSurvivesSwapAndSupportingEvidence() throws Exception {
        String transform = source("scripts/butler-app-bf944-lineup-compare-return-transform.ps1");

        assertTrue(transform.contains("$lineupContextSuffix = if ($Request.FromLineup)"));
        assertTrue(transform.contains("$supportHref = \"/compare?left=$leftHref&right=$rightHref$lineupContextSuffix&support=1#supporting-evidence\""));
        assertTrue(transform.contains("$swapHref = \"/compare?left=$rightHref&right=$leftHref$lineupContextSuffix$swapSuffix\""));
    }

    @Test
    void returnLoopRejectsArbitraryNavigationAndWrites() throws Exception {
        String transform = source("scripts/butler-app-bf944-lineup-compare-return-transform.ps1");

        assertTrue(transform.contains("'returnUrl'"));
        assertTrue(transform.contains("'redirectUrl'"));
        assertTrue(transform.contains("'window.history'"));
        assertTrue(transform.contains("'javascript:'"));
        assertTrue(transform.contains("'submitTransaction'"));
        assertTrue(transform.contains("'setFaab'"));
        assertFalse(transform.contains("https://api.sleeper.app"));
    }

    @Test
    void stagesAfterBf943AndBeforeDiagnosticTiming() throws Exception {
        String staging = source("scripts/butler-dashboard-bf715-transform.ps1");

        int bf943 = staging.indexOf("& $bf943Transform -CorePath $stagedCore");
        int bf944 = staging.indexOf("& $bf944Transform -CorePath $stagedCore");
        int bf857 = staging.indexOf("# BF-857: diagnostic-only inner-core timing");

        assertTrue(bf943 >= 0, "BF-943 staging marker missing");
        assertTrue(bf944 > bf943, "BF-944 must run after BF-943");
        assertTrue(bf857 > bf944, "BF-857 timing must remain after BF-944");
    }

    @Test
    void transformRemainsAscii() throws Exception {
        String transform = source("scripts/butler-app-bf944-lineup-compare-return-transform.ps1");
        assertTrue(StandardCharsets.US_ASCII.newEncoder().canEncode(transform));
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
        throw new IOException("BF-944 test could not locate " + relativePath);
    }
}
