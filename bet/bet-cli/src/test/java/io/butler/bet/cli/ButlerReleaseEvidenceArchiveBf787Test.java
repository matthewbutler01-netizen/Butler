package io.butler.bet.cli;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ButlerReleaseEvidenceArchiveBf787Test {

    @Test
    void evidenceBundleRequiresBf778BeforePackagingAndUsesExactAllowlist() throws Exception {
        String bundle = source("scripts/butler-release-evidence-bundle.ps1");

        int verify = bundle.indexOf("& $verifyScript");
        int copy = bundle.indexOf("Copy-Item -LiteralPath $approvedPaths[$i]");
        int compress = bundle.indexOf("Compress-Archive -Path (Join-Path $stageDir '*')");

        assertTrue(verify >= 0);
        assertTrue(copy > verify, "BF-787 must not stage evidence before BF-778 verification");
        assertTrue(compress > copy);
        assertTrue(bundle.contains("$approvedNames = @($artifactName, $checksumName, $manifestName, $recordName)"));
        assertTrue(bundle.contains("$stagedFiles.Count -ne 4"));
        assertTrue(bundle.contains("$entryNames.Count -ne 4"));
        assertTrue(bundle.contains("Butler-release-evidence-$shortCommit.zip"));
        assertTrue(bundle.contains("VERIFIED_RELEASE_EVIDENCE_ONLY_NO_RUNTIME_DATA_NO_PUBLICATION"));
        assertTrue(bundle.contains("BF-787 RELEASE EVIDENCE ARCHIVE: PASS"));

        assertFalse(bundle.contains("butler.db"));
        assertFalse(bundle.contains("BUTLER_APP_DATA_DIR"));
        assertFalse(bundle.contains("LOCALAPPDATA"));
        assertFalse(bundle.contains("Invoke-WebRequest"));
        assertFalse(bundle.contains("Invoke-RestMethod"));
        assertFalse(bundle.contains("git tag"));
        assertFalse(bundle.contains("gh release"));
    }

    @Test
    void unifiedGatePackagesEvidenceOnlyAfterOfflineVerification() throws Exception {
        String gate = source("scripts/butler-release-acceptance.cmd");

        int verify = gate.indexOf("butler-release-verification-check.ps1");
        int evidence = gate.indexOf("butler-release-evidence-bundle.ps1");
        int bf780 = gate.indexOf("BF-780 RELEASE SELF-VERIFICATION: PASS");
        int bf787 = gate.indexOf("BF-787 RELEASE EVIDENCE ARCHIVE: PASS");

        assertTrue(verify >= 0);
        assertTrue(evidence > verify);
        assertTrue(bf787 > evidence);
        assertTrue(bf787 > bf780);
        assertTrue(gate.contains("set \"BF787_EVIDENCE_ERROR=%ERRORLEVEL%\""));
        assertTrue(gate.contains("if not \"%BF787_EVIDENCE_ERROR%\"==\"0\" exit /b %BF787_EVIDENCE_ERROR%"));
    }

    @Test
    void isolatedAcceptanceUsesSyntheticV1EvidenceAndCleansOnlyOwnedPaths() throws Exception {
        String acceptance = source("scripts/butler-release-evidence-bundle-acceptance.ps1");

        assertTrue(acceptance.contains("BUTLER_RELEASE_VERIFICATION_V1"));
        assertTrue(acceptance.contains("release_acceptance=BF-776_PASS"));
        assertTrue(acceptance.contains("CODE_PLUS_PREBUILT_READ_RUNTIME_NO_RUNTIME_DATA"));
        assertTrue(acceptance.contains("RELEASE_METADATA_ONLY_NO_RUNTIME_DATA"));
        assertTrue(acceptance.contains("& $bundleScript -RecordPath $recordPath"));
        assertTrue(acceptance.contains("$ownedPaths = @($runtimePath, $runtimeChecksumPath, $manifestPath, $recordPath, $evidencePath, $evidenceChecksumPath)"));
        assertTrue(acceptance.contains("synthetic acceptance path already exists and will not be overwritten"));
        assertTrue(acceptance.contains("BF-787 RELEASE EVIDENCE ACCEPTANCE: PASS"));
    }

    @Test
    void dedicatedWindowsWorkflowExecutesPortableEvidenceAcceptance() throws Exception {
        String workflow = source(".github/workflows/bf787-release-evidence.yml");

        assertTrue(workflow.contains("name: BF-787 Release Evidence"));
        assertTrue(workflow.contains("runs-on: windows-latest"));
        assertTrue(workflow.contains("& '.\\scripts\\butler-release-evidence-bundle-acceptance.ps1'"));
        assertTrue(workflow.contains("persist-credentials: false"));
    }

    @Test
    void documentationDefinesPortableArchiveWithoutChangingDataBoundary() throws Exception {
        String docs = source("docs/release-evidence.md");

        assertTrue(docs.contains("Butler-runtime-<shortsha>.zip"));
        assertTrue(docs.contains("Butler-runtime-<shortsha>.zip.sha256"));
        assertTrue(docs.contains("Butler-runtime-<shortsha>.manifest.txt"));
        assertTrue(docs.contains("Butler-release-<shortsha>.verified.txt"));
        assertTrue(docs.contains("Butler-release-evidence-<shortsha>.zip"));
        assertTrue(docs.contains("BF-787 RELEASE EVIDENCE ARCHIVE: PASS"));
        assertTrue(docs.contains("does not upload anything, create a Git tag, or create a GitHub Release"));
    }

    @Test
    void bf787WindowsSourcesRemainAsciiOnly() throws Exception {
        assertAscii(source("scripts/butler-release-evidence-bundle.ps1"));
        assertAscii(source("scripts/butler-release-evidence-bundle-acceptance.ps1"));
        assertAscii(source("scripts/butler-release-acceptance.cmd"));
    }

    private static void assertAscii(String text) {
        byte[] encoded = text.getBytes(StandardCharsets.US_ASCII);
        assertEquals(text, new String(encoded, StandardCharsets.US_ASCII));
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
        throw new IOException("BF-787 test could not locate " + relativePath);
    }
}
