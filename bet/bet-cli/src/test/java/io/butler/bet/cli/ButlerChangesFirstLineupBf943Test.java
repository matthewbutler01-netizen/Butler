package io.butler.bet.cli;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ButlerChangesFirstLineupBf943Test {

    @Test
    void changesStayVisibleAndUnchangedSlotsUseProgressiveDisclosure() throws Exception {
        String transform = source("scripts/butler-app-bf943-changes-first-lineup-transform.ps1");

        assertTrue(transform.contains("CHANGES FIRST"));
        assertTrue(transform.contains("ALL KEEP"));
        assertTrue(transform.contains("View $unchangedCount unchanged lineup $slotWord"));
        assertTrue(transform.contains(".lineup-focus .lineup-row:not(.changed){display:none}"));
        assertTrue(transform.contains(".lineup-unchanged .lineup-row.changed{display:none}"));
        assertTrue(transform.contains("$unchangedCount = @($AutoFill.Assignments | Where-Object { -not $_.Changed }).Count"));
    }

    @Test
    void preservesSwapCompareAndProjectionDeltaEvidence() throws Exception {
        String transform = source("scripts/butler-app-bf943-changes-first-lineup-transform.ps1");

        assertTrue(transform.contains("Compare this swap"));
        assertTrue(transform.contains("CurrentPoints"));
        assertTrue(transform.contains("RecommendedPoints"));
        assertTrue(transform.contains("SlotGain"));
    }

    @Test
    void remainsPresentationOnly() throws Exception {
        String transform = source("scripts/butler-app-bf943-changes-first-lineup-transform.ps1");

        assertFalse(transform.contains("Invoke-ButlerReadOnlyTask"));
        assertFalse(transform.contains("Invoke-Bf742DashboardWorkerRead"));
        assertTrue(transform.contains("'Invoke-RestMethod'"));
        assertTrue(transform.contains("'Invoke-WebRequest'"));
        assertTrue(transform.contains("'Method = \"POST\"'"));
        assertTrue(transform.contains("'submitTransaction'"));
        assertTrue(transform.contains("'setFaab'"));
    }

    @Test
    void stagesAfterBf942AndBeforeDiagnosticTiming() throws Exception {
        String staging = source("scripts/butler-dashboard-bf715-transform.ps1");

        int bf942 = staging.indexOf("& $bf942Transform -CorePath $stagedCore");
        int bf943 = staging.indexOf("& $bf943Transform -CorePath $stagedCore");
        int bf857 = staging.indexOf("# BF-857: diagnostic-only inner-core timing");

        assertTrue(bf942 >= 0, "BF-942 staging marker missing");
        assertTrue(bf943 > bf942, "BF-943 must run after BF-942");
        assertTrue(bf857 > bf943, "BF-857 timing must remain after BF-943");
    }

    @Test
    void transformRemainsAscii() throws Exception {
        String transform = source("scripts/butler-app-bf943-changes-first-lineup-transform.ps1");
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
        throw new IOException("BF-943 test could not locate " + relativePath);
    }
}
