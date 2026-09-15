package io.butler.bet.cli;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ButlerMissingRuntimeDatabaseAcceptanceBf786Test {

    @Test
    void isolatedProbeUsesTemporaryPackageAndRequiresExactBf785Failure() throws Exception {
        String probe = source("scripts/butler-missing-runtime-db-acceptance.ps1");

        assertTrue(probe.contains("Copy-Item -LiteralPath $sourceLauncher -Destination $probeLauncher"));
        assertTrue(probe.contains("$tempLocalAppData = Join-Path $tempRoot 'localappdata'"));
        assertTrue(probe.contains("$tempData = Join-Path $tempRoot 'data'"));
        assertTrue(probe.contains("$env:LOCALAPPDATA = $tempLocalAppData"));
        assertTrue(probe.contains("$env:BUTLER_APP_DATA_DIR = $tempData"));
        assertTrue(probe.contains("11111111-1111-1111-1111-111111111111"));
        assertTrue(probe.contains("BF-785 BLOCKED: governed Butler runtime database is missing at $databasePath."));
        assertTrue(probe.contains("The Butler runtime package is code/runtime-only; restore or migrate an existing governed Butler database before launching."));
        assertTrue(probe.contains("$observedFailure -cne $expectedFailure"));
    }

    @Test
    void isolatedProbeCannotReachRealAppShellOrPersistRuntimeState() throws Exception {
        String probe = source("scripts/butler-missing-runtime-db-acceptance.ps1");

        assertTrue(probe.contains("throw 'BF-786 BLOCKED: app shell was invoked during missing-runtime-database acceptance.'"));
        assertTrue(probe.contains("if (Test-Path -LiteralPath $configPath)"));
        assertTrue(probe.contains("launcher persisted league configuration despite missing governed database"));
        assertTrue(probe.contains("if (Test-Path -LiteralPath $databasePath)"));
        assertTrue(probe.contains("launcher created a governed database during missing-database acceptance"));
        assertTrue(probe.contains("Remove-Item -LiteralPath $tempRoot -Recurse -Force"));
        assertTrue(probe.contains("BF-786 MISSING RUNTIME DATABASE ACCEPTANCE: PASS"));
    }

    @Test
    void unifiedReleaseGateRunsNegativeProbeBetweenPackagedSuccessAndNormalAcceptance() throws Exception {
        String gate = source("scripts/butler-release-acceptance.cmd");

        int packaged = gate.indexOf("butler-runtime-packaged-launch-acceptance.ps1");
        int missingDb = gate.indexOf("butler-missing-runtime-db-acceptance.ps1");
        int normal = gate.indexOf("call \"%~dp0butler-acceptance.cmd\" %*");
        int verify = gate.indexOf("butler-release-verification-check.ps1");
        int finalMarker = gate.indexOf("BF-786 MISSING RUNTIME DATABASE ACCEPTANCE: PASS", verify);

        assertTrue(packaged >= 0);
        assertTrue(missingDb > packaged);
        assertTrue(normal > missingDb);
        assertTrue(verify > normal);
        assertTrue(finalMarker > verify);
        assertTrue(gate.contains("set \"BF786_MISSING_DB_ERROR=%ERRORLEVEL%\""));
        assertTrue(gate.contains("if not \"%BF786_MISSING_DB_ERROR%\"==\"0\" exit /b %BF786_MISSING_DB_ERROR%"));
    }

    @Test
    void windowsWorkflowExecutesTheBf786Probe() throws Exception {
        String workflow = source(".github/workflows/windows-powershell-parse.yml");

        assertTrue(workflow.contains("Exercise BF-786 missing runtime database acceptance"));
        assertTrue(workflow.contains("& '.\\scripts\\butler-missing-runtime-db-acceptance.ps1'"));
    }

    @Test
    void bf786WindowsSourcesRemainAsciiOnly() throws Exception {
        assertAscii(source("scripts/butler-missing-runtime-db-acceptance.ps1"));
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
        throw new IOException("BF-786 test could not locate " + relativePath);
    }
}
