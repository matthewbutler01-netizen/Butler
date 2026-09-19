package io.butler.bet.cli;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ButlerDashboardReadyExplanationCacheBf861Test {

    @Test
    void cacheIsExactAuditPositiveOnlyAndProcessScoped() throws Exception {
        String transform = source("scripts/butler-core-bf742-transform.ps1");

        assertTrue(transform.contains("$script:Bf861ReadyExplanationAuditId = $null"));
        assertTrue(transform.contains("$script:Bf861ReadyExplanationText = $null"));
        assertTrue(transform.contains("$script:Bf861ReadyExplanationAuditId -ceq $AuditId"));
        assertTrue(transform.contains("Lookup state:\\s*EXPLANATION_READY"));
        assertTrue(transform.contains("BF-627 audit id:\\s*"));
        assertTrue(transform.contains("[regex]::Escape($AuditId)"));
        assertTrue(transform.contains("$script:Bf861ReadyExplanationAuditId = $AuditId"));
        assertTrue(transform.contains("$script:Bf861ReadyExplanationText = [string]$text"));
    }

    @Test
    void missStillUsesPersistentWorkerAndNegativeResultIsNotCached() throws Exception {
        String transform = source("scripts/butler-core-bf742-transform.ps1");

        int cacheCheck = transform.indexOf("$script:Bf861ReadyExplanationAuditId -ceq $AuditId");
        int workerLookup = transform.indexOf(
            "Invoke-Bf740PersistentCoreWorker -Operation 'EXPLANATION_LOOKUP' -BoundaryName \"BF-654\" -AuditId $AuditId");
        int readyCheck = transform.indexOf("$ready = [regex]::IsMatch");
        int cacheWrite = transform.indexOf("$script:Bf861ReadyExplanationAuditId = $AuditId");

        assertTrue(cacheCheck >= 0);
        assertTrue(workerLookup > cacheCheck);
        assertTrue(readyCheck > workerLookup);
        assertTrue(cacheWrite > readyCheck);
        assertTrue(transform.contains("if ($ready -and $auditMatches)"));
        assertFalse(transform.contains("EXPLANATION_NOT_CAPTURED'"));
        assertFalse(transform.contains("EXPLANATION_NOT_CAPTURED\""));
    }

    @Test
    void everyPreservedCorePrimesDashboardBeforePublicEligibility() throws Exception {
        String core = source("scripts/butler-app-shell-core.ps1");

        String dashboardUrl = "http://127.0.0.1:$BackendPort/";
        assertTrue(core.contains(dashboardUrl));
        assertTrue(core.contains("BF-861 preserved-core dashboard warmup skipped"));
        assertTrue(core.contains("Invoke-PreservedCoreWarmup -BackendPort $replacementPort"));
        assertTrue(core.contains("Invoke-PreservedCoreWarmup -BackendPort $backendPort"));
        assertTrue(core.indexOf("Invoke-PreservedCoreWarmup -BackendPort $backendPort")
            < core.indexOf("$requestPool.Open()"));
        assertFalse(core.contains("Method = 'POST'"));
    }

    @Test
    void cacheDoesNotChangeExplanationGenerationOrWrites() throws Exception {
        String transform = source("scripts/butler-core-bf742-transform.ps1");

        assertFalse(transform.contains("GovernedExplanationCapture"));
        assertFalse(transform.contains("sleeperLiveWaiverGovernedExplanationCapture"));
        assertFalse(transform.contains("submitTransaction"));
        assertFalse(transform.contains("setFaab"));
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
        throw new IOException("BF-861 test could not locate " + relativePath);
    }
}
