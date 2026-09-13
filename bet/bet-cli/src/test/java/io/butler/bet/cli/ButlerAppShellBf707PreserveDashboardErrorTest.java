package io.butler.bet.cli;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ButlerAppShellBf707PreserveDashboardErrorTest {

    @Test
    void stagedCoreInjectsLeagueNavigationOnlyIntoSuccessfulHtmlAcrossAllSites() throws Exception {
        String core = source("scripts/butler-app-shell-core.ps1");

        assertTrue(core.contains("$coreSingleNavigationOriginal = 'if ($proxied.ContentType -match ''^text/html'') {'"));
        assertTrue(core.contains("$coreSingleNavigationReplacement = 'if ($proxied.StatusCode -ge 200 -and $proxied.StatusCode -lt 300 -and $proxied.ContentType -match ''^text/html'') {'"));
        assertTrue(core.contains("$navigationMatchCount = [regex]::Matches($coreSingleText, [regex]::Escape($coreSingleNavigationOriginal)).Count"));
        assertTrue(core.contains("if ($navigationMatchCount -lt 1)"));
        assertTrue(core.contains("BF-707 BLOCKED: staged core navigation injection contract is missing."));
        assertFalse(core.contains("LastIndexOf($coreSingleNavigationOriginal"));
        assertFalse(core.contains("missing or ambiguous"));
        assertTrue(core.contains("Replace($coreSingleNavigationOriginal, $coreSingleNavigationReplacement)"));
        assertTrue(core.contains("WriteAllText($runtimeCoreSingle, $coreSingleText"));
    }

    @Test
    void bf707StagingRemainsAsciiOnly() throws Exception {
        String core = source("scripts/butler-app-shell-core.ps1");
        byte[] encoded = core.getBytes(StandardCharsets.US_ASCII);
        assertEquals(core, new String(encoded, StandardCharsets.US_ASCII));
    }

    private static String source(String relativePath) throws IOException {
        Path current = Path.of(System.getProperty("user.dir")).toAbsolutePath().normalize();
        for (int depth = 0; depth < 7 && current != null; depth++) {
            Path candidate = current.resolve(relativePath);
            if (Files.isRegularFile(candidate)) {
                return Files.readString(candidate, StandardCharsets.US_ASCII);
            }
            current = current.getParent();
        }
        throw new IOException("BF-707 test could not locate " + relativePath);
    }
}
