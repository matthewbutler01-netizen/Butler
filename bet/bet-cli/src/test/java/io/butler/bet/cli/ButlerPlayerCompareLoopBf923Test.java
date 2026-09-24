package io.butler.bet.cli;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ButlerPlayerCompareLoopBf923Test {

    @Test
    void resultCanSwapExactSidesAndPreserveLoadedSupport() throws Exception {
        String transform = source("scripts/butler-app-bf923-player-compare-loop-transform.ps1");

        assertTrue(transform.contains(
                "$swapSuffix = if ($Request.LoadSupportingEvidence) { '&support=1#supporting-evidence' } else { '' }"));
        assertTrue(transform.contains(
                "$swapHref = \"/compare?left=$rightHref&right=$leftHref$swapSuffix\""));
        assertTrue(transform.contains("href=\"$swapHref\">Swap sides</a>"));
    }

    @Test
    void eachCompareCardCanRestartAgainstSamePosition() throws Exception {
        String transform = source("scripts/butler-app-bf923-player-compare-loop-transform.ps1");

        assertTrue(transform.contains(
                "$positionHref = [System.Uri]::EscapeDataString([string]$Player.Position)"));
        assertTrue(transform.contains(
                "href=\"/compare?left=$hrefId&q=$positionHref\">Compare with another $(ConvertTo-HtmlText $Player.Position)</a>"));
    }

    @Test
    void stagingRunsAfterPlayerDiscoveryBeforeRecoveryPolish() throws Exception {
        String staging = source("scripts/butler-dashboard-bf715-transform.ps1");

        int bf922 = staging.indexOf("& $bf922CoreTransform -CorePath $stagedCore");
        int bf923 = staging.indexOf("& $bf923CoreTransform -CorePath $stagedCore");
        int bf884 = staging.indexOf("& $bf884CoreTransform -CorePath $stagedCore");

        assertTrue(bf922 >= 0, "BF-922 staging marker missing");
        assertTrue(bf923 > bf922, "BF-923 must run after player discovery");
        assertTrue(bf884 > bf923, "BF-884 must remain after BF-923");
        assertTrue(staging.contains("butler-app-bf923-player-compare-loop-transform.ps1"));
    }

    @Test
    void managerJourneyExercisesComparisonAndSwap() throws Exception {
        String journey = source("scripts/butler-manager-journey-acceptance.ps1");

        assertTrue(journey.contains("Player Compare start"));
        assertTrue(journey.contains("Player Compare choices"));
        assertTrue(journey.contains("Player Compare result"));
        assertTrue(journey.contains("Player Compare swapped"));
        assertTrue(journey.contains("Player Compare workflow"));
        assertTrue(journey.contains("BF-923 FAILED: Player Compare result did not expose an exact swap path."));
    }

    @Test
    void transformStaysPresentationOnlyAndAscii() throws Exception {
        String transform = source("scripts/butler-app-bf923-player-compare-loop-transform.ps1");

        assertFalse(transform.contains("Invoke-RestMethod"));
        assertFalse(transform.contains("Invoke-WebRequest"));
        assertFalse(transform.contains("https://api.sleeper.app"));
        assertFalse(transform.contains("Method = \"POST\""));
        assertFalse(transform.contains("submitTransaction"));
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
        throw new IOException("BF-923 test could not locate " + relativePath);
    }
}
