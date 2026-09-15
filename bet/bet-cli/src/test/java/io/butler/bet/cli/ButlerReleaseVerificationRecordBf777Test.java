package io.butler.bet.cli;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ButlerReleaseVerificationRecordBf777Test {

    @Test
    void verificationRecordRunsOnlyAfterBothAcceptanceLayersSucceed() throws Exception {
        String gate = source("scripts/butler-release-acceptance.cmd");

        int runtimeGate = gate.indexOf("butler-runtime-packaged-launch-acceptance.ps1");
        int existingAcceptance = gate.indexOf("call \"%~dp0butler-acceptance.cmd\" %*");
        int acceptanceFailure = gate.indexOf("if not \"%BF776_ACCEPTANCE_ERROR%\"==\"0\" exit /b %BF776_ACCEPTANCE_ERROR%");
        int successMarker = gate.indexOf("set \"BUTLER_BF776_ACCEPTANCE_VERIFIED=1\"");
        int recordWriter = gate.indexOf("butler-release-verification-record.ps1");
        int bf776Pass = gate.indexOf("BF-776 RELEASE ACCEPTANCE: PASS");
        int bf777Pass = gate.indexOf("BF-777 RELEASE VERIFICATION RECORD: PASS");

        assertTrue(runtimeGate >= 0);
        assertTrue(existingAcceptance > runtimeGate);
        assertTrue(acceptanceFailure > existingAcceptance);
        assertTrue(successMarker > acceptanceFailure,
            "BF-777 success marker must only be set after existing acceptance succeeds");
        assertTrue(recordWriter > successMarker,
            "BF-777 record writer must only run after both acceptance layers succeed");
        assertTrue(bf776Pass > recordWriter);
        assertTrue(bf777Pass > bf776Pass);
    }

    @Test
    void verificationWriterRechecksArtifactAndManifestBeforeAsciiRecord() throws Exception {
        String writer = source("scripts/butler-release-verification-record.ps1");

        assertTrue(writer.contains("BUTLER_BF776_ACCEPTANCE_VERIFIED -cne '1'"));
        assertTrue(writer.contains("Get-FileHash -LiteralPath $artifactPath -Algorithm SHA256"));
        assertTrue(writer.contains("$checksumPath = $artifactPath + '.sha256'"));
        assertTrue(writer.contains("BUTLER_RUNTIME_RELEASE_MANIFEST_V1"));
        assertTrue(writer.contains("\"commit=$fullSha\""));
        assertTrue(writer.contains("\"sha256=$actualHash\""));
        assertTrue(writer.contains("boundary=CODE_PLUS_PREBUILT_READ_RUNTIME_NO_RUNTIME_DATA"));
        assertTrue(writer.contains("Butler-release-$shortSha.verified.txt"));
        assertTrue(writer.contains("release_acceptance=BF-776_PASS"));
        assertTrue(writer.contains("verification_boundary=RELEASE_METADATA_ONLY_NO_RUNTIME_DATA"));
        assertTrue(writer.contains("[Text.Encoding]::ASCII"));
        assertFalse(writer.contains("butler.db"),
            "BF-777 record writer must not read or copy runtime database material");
    }

    @Test
    void verificationRecordFailurePropagatesAndReleaseOutputIsIgnored() throws Exception {
        String gate = source("scripts/butler-release-acceptance.cmd");
        String gitignore = source(".gitignore");

        assertTrue(gate.contains("set \"BF777_RECORD_ERROR=%ERRORLEVEL%\""));
        assertTrue(gate.contains("set \"BUTLER_BF776_ACCEPTANCE_VERIFIED=\""));
        assertTrue(gate.contains("if not \"%BF777_RECORD_ERROR%\"==\"0\" exit /b %BF777_RECORD_ERROR%"));
        assertTrue(gitignore.contains("release-output/"));
    }

    @Test
    void bf777WindowsSourcesRemainAsciiOnly() throws Exception {
        for (String relativePath : new String[] {
            "scripts/butler-release-acceptance.cmd",
            "scripts/butler-release-verification-record.ps1"
        }) {
            String text = source(relativePath);
            byte[] encoded = text.getBytes(StandardCharsets.US_ASCII);
            assertEquals(text, new String(encoded, StandardCharsets.US_ASCII), relativePath);
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
        throw new IOException("BF-777 test could not locate " + relativePath);
    }
}
