package io.butler.bet.cli;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ButlerReleaseSelfVerificationBf780Test {

    @Test
    void unifiedGateSelfVerifiesRecordAfterCreation() throws Exception {
        String gate = source("scripts/butler-release-acceptance.cmd");

        int runtime = gate.indexOf("butler-runtime-packaged-launch-acceptance.ps1");
        int acceptance = gate.indexOf("call \"%~dp0butler-acceptance.cmd\" %*");
        int record = gate.indexOf("butler-release-verification-record.ps1");
        int verify = gate.indexOf("butler-release-verification-check.ps1");
        int pass = gate.indexOf("BF-780 RELEASE SELF-VERIFICATION: PASS");

        assertTrue(runtime >= 0);
        assertTrue(acceptance > runtime);
        assertTrue(record > acceptance);
        assertTrue(verify > record,
            "BF-780 must verify the BF-777 record only after it has been created");
        assertTrue(pass > verify,
            "BF-780 must not report final PASS until BF-778 verification completes");
    }

    @Test
    void unifiedGatePropagatesOfflineVerifierFailure() throws Exception {
        String gate = source("scripts/butler-release-acceptance.cmd");

        assertTrue(gate.contains("set \"BF778_VERIFY_ERROR=%ERRORLEVEL%\""));
        assertTrue(gate.contains("if not \"%BF778_VERIFY_ERROR%\"==\"0\" exit /b %BF778_VERIFY_ERROR%"));
        assertTrue(gate.contains("powershell.exe -NoLogo -NoProfile -ExecutionPolicy Bypass -File \"%~dp0butler-release-verification-check.ps1\""));
    }

    @Test
    void unifiedGatePreservesEarlierPassMarkersAndAddsBf780Marker() throws Exception {
        String gate = source("scripts/butler-release-acceptance.cmd");

        int verify = gate.indexOf("butler-release-verification-check.ps1");
        int bf776 = gate.indexOf("BF-776 RELEASE ACCEPTANCE: PASS");
        int bf777 = gate.indexOf("BF-777 RELEASE VERIFICATION RECORD: PASS");
        int bf780 = gate.indexOf("BF-780 RELEASE SELF-VERIFICATION: PASS");

        assertTrue(bf776 > verify);
        assertTrue(bf777 > verify);
        assertTrue(bf780 > bf777);
    }

    @Test
    void bf780CommandSourceRemainsAsciiOnly() throws Exception {
        String gate = source("scripts/butler-release-acceptance.cmd");
        byte[] encoded = gate.getBytes(StandardCharsets.US_ASCII);
        assertEquals(gate, new String(encoded, StandardCharsets.US_ASCII));
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
        throw new IOException("BF-780 test could not locate " + relativePath);
    }
}
