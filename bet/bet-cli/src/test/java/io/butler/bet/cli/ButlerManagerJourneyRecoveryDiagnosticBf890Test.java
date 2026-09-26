package io.butler.bet.cli;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ButlerManagerJourneyRecoveryDiagnosticBf890Test {

    @Test
    void journeySetsDiagnosticFlagOnlyOnOwnedButlerChild() throws Exception {
        String journey = source("scripts/butler-manager-journey-acceptance.ps1");

        assertTrue(journey.contains(
                "$start.EnvironmentVariables['BUTLER_BF890_ACCEPTANCE_DIAGNOSTICS'] = '1'"));
        assertTrue(journey.contains("$start.UseShellExecute = $false"));
        assertFalse(journey.contains("$env:BUTLER_BF890_ACCEPTANCE_DIAGNOSTICS = '1'"));
    }

    @Test
    void onlyFailedAcceptanceHtmlBypassesPresentationCleanup() throws Exception {
        String cache = source("scripts/butler-app-request-worker-cache.ps1");

        assertTrue(cache.contains(
                "[string]$env:BUTLER_BF890_ACCEPTANCE_DIAGNOSTICS -ceq '1' -and"));
        assertTrue(cache.contains("$StatusCode -ge 400"));
        assertTrue(cache.contains("if (-not $bf890AcceptanceDiagnostic)"));
        assertTrue(cache.contains("$Body = ConvertTo-ButlerUserFacingHtml -Html $Body"));
    }

    @Test
    void normalPublicHtmlStillRemovesTechnicalRecords() throws Exception {
        String cache = source("scripts/butler-app-request-worker-cache.ps1");

        assertTrue(cache.contains("BF-794 removes technical record disclosures from normal user-facing HTML"));
        assertTrue(cache.contains(
                "<details\\b[^>]*>\\s*<summary>Advanced technical record</summary>.*?</details>"));
        assertFalse(cache.contains("BUTLER_BF890_ACCEPTANCE_DIAGNOSTICS=1"));
        assertFalse(cache.contains("Method = 'POST'"));
    }

    @Test
    void changedWindowsSourcesRemainAsciiOnly() throws Exception {
        for (String path : new String[]{
                "scripts/butler-manager-journey-acceptance.ps1",
                "scripts/butler-app-request-worker-cache.ps1"
        }) {
            assertTrue(StandardCharsets.US_ASCII.newEncoder().canEncode(source(path)), path);
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
        throw new IOException("BF-890 test could not locate " + relativePath);
    }
}
