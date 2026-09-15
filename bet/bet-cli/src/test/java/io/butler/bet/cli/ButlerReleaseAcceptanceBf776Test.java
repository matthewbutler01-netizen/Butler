package io.butler.bet.cli;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ButlerReleaseAcceptanceBf776Test {

    @Test
    void releaseGateRunsPackagedRuntimeBeforeExistingAcceptance() throws Exception {
        String gate = source("scripts/butler-release-acceptance.cmd");

        int runtimeGate = gate.indexOf("butler-runtime-packaged-launch-acceptance.ps1");
        int existingAcceptance = gate.indexOf("call \"%~dp0butler-acceptance.cmd\" %*");
        int passMarker = gate.indexOf("BF-776 RELEASE ACCEPTANCE: PASS");

        assertTrue(runtimeGate >= 0, "BF-776 must invoke packaged-runtime acceptance");
        assertTrue(existingAcceptance > runtimeGate,
            "BF-776 must run packaged-runtime acceptance before existing Butler acceptance");
        assertTrue(passMarker > existingAcceptance,
            "BF-776 must not report PASS before both acceptance layers run");
    }

    @Test
    void releaseGatePropagatesBothFailureCodesAndReusesExistingSuite() throws Exception {
        String gate = source("scripts/butler-release-acceptance.cmd");

        assertTrue(gate.contains("set \"BF776_RUNTIME_ERROR=%ERRORLEVEL%\""));
        assertTrue(gate.contains("if not \"%BF776_RUNTIME_ERROR%\"==\"0\" exit /b %BF776_RUNTIME_ERROR%"));
        assertTrue(gate.contains("set \"BF776_ACCEPTANCE_ERROR=%ERRORLEVEL%\""));
        assertTrue(gate.contains("if not \"%BF776_ACCEPTANCE_ERROR%\"==\"0\" exit /b %BF776_ACCEPTANCE_ERROR%"));
        assertTrue(gate.contains("call \"%~dp0butler-acceptance.cmd\" %*"));
        assertFalse(gate.contains("butler-release-security-acceptance.ps1"),
            "BF-776 must reuse butler-acceptance.cmd rather than duplicate its security gate");
    }

    @Test
    void bf776CommandSourceRemainsAsciiOnly() throws Exception {
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
        throw new IOException("BF-776 test could not locate " + relativePath);
    }
}
