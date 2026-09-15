package io.butler.bet.cli;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ButlerReleaseVerificationCheckBf778Test {

    @Test
    void verifierSupportsCurrentHeadDefaultAndExplicitHistoricalRecord() throws Exception {
        String verifier = source("scripts/butler-release-verification-check.ps1");

        assertTrue(verifier.contains("[string]$RecordPath"));
        assertTrue(verifier.contains("if ([string]::IsNullOrWhiteSpace($RecordPath))"));
        assertTrue(verifier.contains("'rev-parse' '--verify' 'HEAD^{commit}'"));
        assertTrue(verifier.contains("if ([IO.Path]::IsPathRooted($RecordPath))"));
        assertTrue(verifier.contains("$commit = [string]$record['commit']"));
        assertFalse(verifier.contains("$commit -cne $headSha"),
            "explicit historical verification must not require the saved commit to equal current HEAD");
    }

    @Test
    void verifierRequiresCanonicalBf777RecordSchemaAndBindings() throws Exception {
        String verifier = source("scripts/butler-release-verification-check.ps1");

        assertTrue(verifier.contains("$recordLines.Count -ne 9"));
        assertTrue(verifier.contains("BUTLER_RELEASE_VERIFICATION_V1"));
        assertTrue(verifier.contains("duplicate verification record key"));
        assertTrue(verifier.contains("unexpected verification record key"));
        assertTrue(verifier.contains("^[0-9a-f]{40}$"));
        assertTrue(verifier.contains("^[0-9a-f]{8}$"));
        assertTrue(verifier.contains("^[0-9a-f]{64}$"));
        assertTrue(verifier.contains("Butler-runtime-$shortCommit.zip"));
        assertTrue(verifier.contains("Butler-runtime-$shortCommit.manifest.txt"));
        assertTrue(verifier.contains("Butler-release-$shortCommit.verified.txt"));
        assertTrue(verifier.contains("BF-776_PASS"));
        assertTrue(verifier.contains("CODE_PLUS_PREBUILT_READ_RUNTIME_NO_RUNTIME_DATA"));
        assertTrue(verifier.contains("RELEASE_METADATA_ONLY_NO_RUNTIME_DATA"));
    }

    @Test
    void verifierCrossChecksRecordArtifactSidecarAndManifest() throws Exception {
        String verifier = source("scripts/butler-release-verification-check.ps1");

        assertTrue(verifier.contains("Get-FileHash -LiteralPath $artifactPath -Algorithm SHA256"));
        assertTrue(verifier.contains("$actualHash -cne $recordHash"));
        assertTrue(verifier.contains("$checksumPath = $artifactPath + '.sha256'"));
        assertTrue(verifier.contains("$sidecarHash -cne $actualHash"));
        assertTrue(verifier.contains("$sidecarArtifact -cne $artifactName"));
        assertTrue(verifier.contains("BUTLER_RUNTIME_RELEASE_MANIFEST_V1"));
        assertTrue(verifier.contains("\"commit=$commit\""));
        assertTrue(verifier.contains("\"artifact=$artifactName\""));
        assertTrue(verifier.contains("\"sha256=$actualHash\""));
        assertTrue(verifier.contains("BF-778 RELEASE VERIFICATION CHECK: PASS"));
    }

    @Test
    void verifierIsOfflineReadOnlyAndAsciiOnly() throws Exception {
        String verifier = source("scripts/butler-release-verification-check.ps1");

        assertTrue(verifier.contains("OFFLINE_RELEASE_EVIDENCE_READ_ONLY_NO_RUNTIME_DATA"));
        assertFalse(verifier.contains("butler.db"));
        assertFalse(verifier.contains("BUTLER_APP_DATA_DIR"));
        assertFalse(verifier.contains("Start-Process"));
        assertFalse(verifier.contains("Invoke-WebRequest"));
        assertFalse(verifier.contains("Invoke-RestMethod"));
        assertFalse(verifier.contains("Set-Content"));
        assertFalse(verifier.contains("Out-File"));
        assertFalse(verifier.contains("WriteAllText"));
        assertFalse(verifier.contains("Move-Item"));

        byte[] encoded = verifier.getBytes(StandardCharsets.US_ASCII);
        assertEquals(verifier, new String(encoded, StandardCharsets.US_ASCII));
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
        throw new IOException("BF-778 test could not locate " + relativePath);
    }
}
