package io.butler.bet.cli;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ButlerGovernedToolingBf772Test {

    @Test
    void dispatchDiagnosticUsesOwnedGovernedDataDirectoryAndRestoresEnvironment() throws Exception {
        String script = source("scripts/butler-dispatch-startup-diagnostic.ps1");

        assertTrue(script.contains("dispatch-diagnostic-data-{0}"));
        assertTrue(script.contains("$originalDataDir = $env:BUTLER_APP_DATA_DIR"));
        assertTrue(script.contains("$env:BUTLER_APP_DATA_DIR = $tempDataDir"));
        assertTrue(script.contains("$start.WorkingDirectory = $tempDataDir"));
        assertTrue(script.contains("Remove-Item Env:BUTLER_APP_DATA_DIR"));
        assertTrue(script.contains("foreach ($ownedPath in @($tempRoot, $tempDataDir))"));
        assertTrue(script.contains("owned temporary governed data directory"));

        assertFalse(script.contains("butler.db"));
        assertFalse(script.contains("Copy-Item -LiteralPath $dataDir"));
        assertFalse(script.contains("create_transaction"));
        assertFalse(script.contains("submitTransaction"));
    }

    @Test
    void packagedAcceptanceAutoBuildsOnlyImplicitCurrentHeadArtifact() throws Exception {
        String packaged = source("scripts/butler-packaged-launch-acceptance.ps1");

        assertTrue(packaged.contains("$sourceZipExplicit = $PSBoundParameters.ContainsKey('SourceZip')"));
        assertTrue(packaged.contains("Join-Path $scriptDir 'butler-release-bundle.ps1'"));
        assertTrue(packaged.contains("gitCommand.Source 'rev-parse' '--short=8' 'HEAD'"));
        assertTrue(packaged.contains("current HEAD release artifact is absent"));
        assertTrue(packaged.contains("& $releaseBuilder -Force"));
        assertTrue(packaged.contains("explicit -SourceZip must not be blank"));
        assertTrue(packaged.contains("BF-769 source release ZIP not found"));

        int implicit = packaged.indexOf("if (-not $sourceZipExplicit)");
        int builder = packaged.indexOf("& $releaseBuilder -Force", implicit);
        int explicit = packaged.indexOf("\nelse {", implicit);
        assertTrue(implicit >= 0 && builder > implicit && explicit > builder,
            "BF-769 auto-build must remain inside the omitted-SourceZip branch only");
    }

    @Test
    void bf772KeepsRuntimeAndReleaseSafetyBoundaries() throws Exception {
        String packaged = source("scripts/butler-packaged-launch-acceptance.ps1");
        String diagnostic = source("scripts/butler-dispatch-startup-diagnostic.ps1");

        assertTrue(packaged.contains("Get-FileHash -LiteralPath $SourceZip -Algorithm SHA256"));
        assertTrue(packaged.contains("EXTRACTED_PACKAGE_CODE_ONLY; SQLITE_RUNTIME_DATA_EXTERNAL"));
        assertTrue(packaged.contains("$start.EnvironmentVariables['BUTLER_APP_DATA_DIR'] = $dataDir"));
        assertTrue(packaged.contains("butler-release-security-check.ps1"));
        assertFalse(packaged.contains("POST /refresh"));
        assertFalse(packaged.contains("Copy-Item -LiteralPath $databasePath"));
        assertFalse(packaged.contains("Remove-Item -LiteralPath $databasePath"));

        assertTrue(diagnostic.contains("ButlerCommandRouter help"));
        assertTrue(diagnostic.contains("--args=help"));
        assertFalse(diagnostic.contains("Invoke-RestMethod"));
        assertFalse(diagnostic.contains("Invoke-WebRequest"));
    }

    @Test
    void bf772WindowsSourcesRemainAsciiOnly() throws Exception {
        for (String path : new String[]{
            "scripts/butler-dispatch-startup-diagnostic.ps1",
            "scripts/butler-packaged-launch-acceptance.ps1"
        }) {
            String text = source(path);
            byte[] encoded = text.getBytes(StandardCharsets.US_ASCII);
            assertEquals(text, new String(encoded, StandardCharsets.US_ASCII));
        }
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
        throw new IOException("BF-772 test could not locate " + relativePath);
    }
}
