package io.butler.bet.cli;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ButlerInAppEvidenceRecoveryBf823Test {

    @Test
    void lineupAdvisorStagesManagerRecoveryAfterBf822Presentation() throws Exception {
        String bf817 = source("scripts/butler-app-bf817-lineup-advisor-transform.ps1");
        String bf823 = source("scripts/butler-app-bf823-evidence-recovery-transform.ps1");

        assertTrue(bf817.contains("butler-app-bf823-evidence-recovery-transform.ps1"));
        assertTrue(bf817.contains("& $bf823Transform -CorePath $CorePath"));
        assertTrue(bf823.contains("Butler data needs refresh"));
        assertTrue(bf823.contains("DATA REFRESH NEEDED"));
        assertTrue(bf823.contains("Refresh Butler Data"));
        assertTrue(bf823.contains("$retryHref = '/refresh'"));
        assertTrue(bf823.contains("View evidence details"));
        assertTrue(bf823.contains("Get-AppCss"));
        assertTrue(bf823.contains("Get-AppNav -Active \"team\""));
        assertTrue(bf823.contains("legacy raw AutoFill failure page remains"));
    }

    @Test
    void recoverySurfaceKeepsTechnicalDetailsOutOfManagerCopyAndDoesNotExecuteWrites() throws Exception {
        String transform = source("scripts/butler-app-bf823-evidence-recovery-transform.ps1");

        int managerTitle = transform.indexOf("Butler data needs refresh");
        int details = transform.indexOf("View evidence details");
        int rawReason = transform.indexOf("$safeReason", details);
        assertTrue(managerTitle >= 0);
        assertTrue(details > managerTitle);
        assertTrue(rawReason > details, "technical reason must remain behind the evidence disclosure");

        assertFalse(transform.contains("butler-recover-roster-drift"));
        assertFalse(transform.contains("Invoke-RestMethod"));
        assertFalse(transform.contains("Start-Process"));
        assertFalse(transform.contains("Method = \"POST\""));
        assertFalse(transform.contains("create_transaction"));
        assertFalse(transform.contains("submitTransaction"));
    }

    @Test
    void governedRecoveryProbesBeforeAnyLocalEvidenceWrite() throws Exception {
        String recovery = source("scripts/butler-lineup-evidence-recovery.ps1");

        int hydrationAudit = recovery.indexOf("Get-HydrationAudit");
        int unmappedGate = recovery.indexOf("if ($audit.UnmappedCount -gt 0)", hydrationAudit);
        int bf610Preflight = recovery.indexOf("$preflight = Invoke-ButlerRuntimeCommand -MainClass $bf610Class", unmappedGate);
        int bootstrap = recovery.indexOf("ButlerSleeperCurrentSeasonRosterBootstrapCli");
        int firstEvidenceWrite = recovery.indexOf("ButlerSleeperLiveWaiverSnapshotSyncCli");

        assertTrue(hydrationAudit >= 0);
        assertTrue(unmappedGate > hydrationAudit);
        assertTrue(bf610Preflight > unmappedGate);
        assertTrue(bootstrap > bf610Preflight);
        assertTrue(firstEvidenceWrite > bootstrap);
        assertTrue(recovery.contains("BF-823 PROBE: RECOVERY_REQUIRED"));
        assertTrue(recovery.contains("BF-823 PROBE: NO_RECOVERY_REQUIRED"));
        assertTrue(recovery.contains("BF-823 PROBE: DEFER_TO_BF676"));
        assertTrue(recovery.contains("BF-823 PROBE REASON: MARKET_CANONICAL_GAP"));
        assertTrue(recovery.contains("BF-608 BLOCKED: BF-604 has"));
        assertTrue(recovery.contains("BF-600 current-season roster/player bootstrap"));
        assertTrue(recovery.contains("Bootstrap state: HYDRATED_VERIFIED"));
        assertTrue(recovery.contains("BF-610 post-recovery target-roster verification"));
        assertTrue(recovery.contains("BF-615/BF-617 post-recovery waiver comparison verification"));
    }

    @Test
    void governedRecoveryUsesFixedSixDownstreamStagesAndNoSleeperMutationPath() throws Exception {
        String recovery = source("scripts/butler-lineup-evidence-recovery.ps1");
        String[] stages = {
            "io.butler.bet.cli.ButlerSleeperLiveWaiverSnapshotSyncCli",
            "io.butler.bet.cli.ButlerSleeperLiveWaiverMarketAttentionSyncCli",
            "io.butler.bet.cli.ButlerSleeperLiveWaiverProductionHydrationCli",
            "io.butler.bet.cli.ButlerSleeperLiveWaiverAvailabilitySyncCli",
            "io.butler.bet.cli.ButlerSleeperLiveWaiverCurrentWeekStatSyncCli",
            "io.butler.bet.cli.ButlerSleeperLiveWaiverTargetRosterProductionHydrationCli"
        };

        int previous = -1;
        for (String stage : stages) {
            assertEquals(1, occurrences(recovery, stage), stage + " must remain a single fixed governed stage");
            int at = recovery.indexOf(stage);
            assertTrue(at > previous, "BF-823 downstream recovery order must remain deterministic");
            previous = at;
        }

        assertFalse(recovery.contains("ButlerSleeperLiveWaiverFinalRecommendationBundleCli"));
        assertFalse(recovery.contains("ButlerSleeperLiveWaiverRecommendationAuditCaptureCli"));
        assertFalse(recovery.contains("Invoke-Expression"));
        assertFalse(recovery.contains("create_transaction"));
        assertFalse(recovery.contains("submitTransaction"));
        assertFalse(recovery.contains("/lineup"));
    }

    @Test
    void browserRefreshRemainsExplicitTokenGatedPostAndFallsBackToExistingRunner() throws Exception {
        String refresh = source("scripts/butler-decision-refresh.ps1");

        int form = refresh.indexOf("<form method=\"post\" action=\"/refresh\">");
        int token = refresh.indexOf("name=\"token\"", form);
        int probe = refresh.indexOf("$recoveryRunner -LeagueId $LeagueId -ProbeOnly");
        int recovery = refresh.indexOf("$recoveryRunner -LeagueId $LeagueId)", probe + 1);
        int fallback = refresh.indexOf("& $RunnerPath -LeagueId $LeagueId", recovery);

        assertTrue(form >= 0 && token > form);
        assertTrue(refresh.contains("refresh POST must contain only the one-use token"));
        assertTrue(probe >= 0);
        assertTrue(recovery > probe);
        assertTrue(refresh.contains("BF-823 PROBE: DEFER_TO_BF676"));
        assertTrue(refresh.contains("BF-823 PROBE REASON: MARKET_CANONICAL_GAP"));
        assertTrue(refresh.contains("$probeStateCount -ne 1"));
        assertTrue(fallback > recovery, "existing BF-676 runner must remain the fallback when lineup recovery is unnecessary or explicitly deferred");
        assertTrue(refresh.contains("If Butler already has an actionable waiver recommendation, BF-676 proceeds only when the existing governed refresh plan is exactly authorized."));
    }

    @Test
    void bf823PowerShellSourcesRemainAsciiForWindowsCompatibility() throws Exception {
        String transform = source("scripts/butler-app-bf823-evidence-recovery-transform.ps1");
        String recovery = source("scripts/butler-lineup-evidence-recovery.ps1");

        assertEquals(transform, new String(transform.getBytes(StandardCharsets.US_ASCII), StandardCharsets.US_ASCII));
        assertEquals(recovery, new String(recovery.getBytes(StandardCharsets.US_ASCII), StandardCharsets.US_ASCII));
    }

    private static int occurrences(String text, String needle) {
        int count = 0;
        for (int at = 0; (at = text.indexOf(needle, at)) >= 0; at += needle.length()) {
            count++;
        }
        return count;
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
        throw new IOException("BF-823 test could not locate " + relativePath);
    }
}
