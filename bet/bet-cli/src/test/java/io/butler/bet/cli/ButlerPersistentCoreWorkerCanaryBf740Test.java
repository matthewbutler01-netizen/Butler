package io.butler.bet.cli;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ButlerPersistentCoreWorkerCanaryBf740Test {
    @Test
    void sharedWorkerTransformKeepsOneDashboardOwnedWorkerPerCore() throws Exception {
        String transform = source("scripts/butler-core-bf742-transform.ps1");
        String helper = source("scripts/butler-persistent-core-worker.ps1");
        String core = source("scripts/butler-app-shell-core-single.ps1");
        String dashboard = source("scripts/butler-dashboard.ps1");

        assertTrue(transform.contains("BUTLER_APP_PERSISTENT_CORE_WORKER"));
        assertTrue(transform.contains("-ceq '0'"));
        assertTrue(transform.contains("BUTLER_APP_INTERNAL_DASHBOARD_TOKEN"));
        assertTrue(transform.contains("X-Butler-Internal-Token"));
        assertTrue(transform.contains("/__butler/internal/team-bundle"));
        assertTrue(transform.contains("/__butler/internal/league-overview"));
        assertTrue(transform.contains("$script:Bf740PersistentCoreWorkerCanary = $true"));
        assertTrue(transform.contains("Invoke-Bf740PersistentCoreWorker -Operation 'LATEST_SUMMARY'"));
        assertTrue(transform.contains("Invoke-Bf740PersistentCoreWorker -Operation 'WAIVER_DASHBOARD_BUNDLE'"));
        assertTrue(transform.contains("Invoke-Bf740PersistentCoreWorker -Operation 'EXPLANATION_LOOKUP'"));
        assertTrue(transform.contains("Start-Bf740PersistentCoreWorker"));
        assertTrue(transform.contains("Stop-Bf740PersistentCoreWorker"));
        assertTrue(transform.contains("staged core still owns a JVM worker"));

        assertTrue(helper.contains("ValidateSet('LEAGUE_OVERVIEW', 'TEAM_BUNDLE', 'LATEST_SUMMARY', 'LATEST_SUMMARY_DIAGNOSTIC', 'TARGET_VERIFY_DIAGNOSTIC', 'WAIVER_DASHBOARD_BUNDLE', 'MATCHUP_BUNDLE', 'EXPLANATION_LOOKUP')"));
        assertTrue(helper.contains("TimeoutMs 180000"));
        assertTrue(helper.contains("^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$"));
        assertTrue(helper.contains("^[A-Za-z0-9._:-]{1,128}$"));
        assertTrue(helper.contains("terminated to prevent protocol desynchronization"));
        assertFalse(helper.contains("sleeperLiveWaiverComparisonBundle"));
        assertFalse(helper.contains("production-refresh"));
        assertFalse(helper.contains("/refresh"));

        assertFalse(core.contains("Bf742DashboardToken"));
        assertFalse(core.contains("X-Butler-Internal-Token"));
        assertFalse(dashboard.contains("Bf740PersistentCoreWorkerCanary"));
        assertFalse(dashboard.contains("/__butler/internal/team-bundle"));
        assertTrue(dashboard.contains(":bet:bet-cli:sleeperLiveWaiverComparisonBundle"));
    }

    @Test
    void workerLeagueIdContractMatchesAppNormalizedUuidBoundary() throws Exception {
        String helper = source("scripts/butler-persistent-core-worker.ps1");
        String worker = source("bet/bet-cli/src/main/java/io/butler/bet/cli/ButlerReadOnlyJvmWorker.java");
        String app = source("scripts/butler-app.ps1");

        String uuidPattern = "[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}";
        assertTrue(app.contains("[Guid]::TryParse($candidate, [ref]$parsed)"));
        assertTrue(app.contains("$parsed.ToString(\"D\").ToLowerInvariant()"));
        assertTrue(helper.contains("^" + uuidPattern + "$"));
        assertTrue(worker.contains("\"" + uuidPattern + "\""));
        assertFalse(helper.contains("^[0-9]{1,32}$"));
        assertFalse(worker.contains("Pattern.compile(\"[0-9]{1,32}\")"));
    }

    @Test
    void dashboardStagingInvokesBf742OnlyWithSiblingCoreAndRetainsOptOut() throws Exception {
        String staging = source("scripts/butler-dashboard-bf715-transform.ps1");
        String transform = source("scripts/butler-core-bf742-transform.ps1");
        int writeDashboard = staging.indexOf("WriteAllText($DashboardPath");
        int defaultGate = staging.indexOf("-cne '0'");
        int bf742Transform = staging.indexOf("butler-core-bf742-transform.ps1");

        assertTrue(writeDashboard >= 0);
        assertTrue(defaultGate > writeDashboard);
        assertTrue(bf742Transform > defaultGate);
        assertTrue(staging.contains("butler-app-shell-core-single.ps1"));
        assertTrue(staging.contains("Test-Path -LiteralPath $stagedCore -PathType Leaf"));
        assertTrue(staging.contains("-cne '0'"));
        assertTrue(staging.contains("-ceq '1'"));
        assertTrue(staging.contains("-DashboardPath $DashboardPath"));
        assertTrue(transform.contains("-ceq '0'"));
        assertTrue(transform.contains("explicitly disabled by BUTLER_APP_PERSISTENT_CORE_WORKER=0"));
    }

    @Test
    void sharedDashboardEndpointsRequirePerCoreSecretAndAreNotPublicRoutes() throws Exception {
        String transform = source("scripts/butler-core-bf742-transform.ps1");
        String core = source("scripts/butler-app-shell-core-single.ps1");
        String dashboard = source("scripts/butler-dashboard.ps1");

        assertTrue(transform.contains("RandomNumberGenerator"));
        assertTrue(transform.contains("New-Object byte[] 32"));
        assertTrue(transform.contains("BUTLER_APP_INTERNAL_DASHBOARD_TOKEN"));
        assertTrue(transform.contains("X-Butler-Internal-Token"));
        assertTrue(transform.contains("StatusCode 403"));
        assertTrue(transform.contains("-cne $script:Bf742DashboardToken"));
        assertFalse(core.contains("/__butler/internal/team-bundle"));
        assertFalse(core.contains("/__butler/internal/league-overview"));
        assertFalse(dashboard.contains("/__butler/internal/team-bundle"));
        assertFalse(dashboard.contains("/__butler/internal/league-overview"));
    }

    @Test
    void acceptanceExercisesSharedDefaultThenDisablesWorkerBeforeStandaloneDiagnostics() throws Exception {
        String command = source("scripts/butler-acceptance.cmd");
        int defaultUnset = command.indexOf("set \"BUTLER_APP_PERSISTENT_CORE_WORKER=\"");
        int acceptance = command.indexOf("butler-acceptance.ps1");
        int diagnosticDisable = command.indexOf("set \"BUTLER_APP_PERSISTENT_CORE_WORKER=0\"");
        int slowDiagnostic = command.indexOf("butler-slow-route-stage-diagnostic.ps1");

        assertTrue(defaultUnset >= 0);
        assertTrue(acceptance > defaultUnset);
        assertTrue(diagnosticDisable > acceptance);
        assertTrue(slowDiagnostic > diagnosticDisable);
        assertTrue(command.contains("emergency opt-out"));
        assertFalse(command.contains("BF-740 canary:"));
    }

    @Test
    void workerImplementationBindsAuthorizedOperationsToExactExistingCliEntryPoints() throws Exception {
        String worker = source("bet/bet-cli/src/main/java/io/butler/bet/cli/ButlerReadOnlyJvmWorker.java");

        assertTrue(worker.contains("new String[] {\"league\", \"overview\", request.leagueId()}"));
        assertTrue(worker.contains("case TEAM_BUNDLE -> executeCapturedWithExitCode"));
        assertTrue(worker.contains("ButlerMyTeamEvidenceBundleCli.runEmbedded("));
        assertTrue(worker.contains("new String[] {request.leagueId()}"));
        assertFalse(worker.contains("new String[] {request.leagueId(), \"--team-bundle\"}"));
        assertTrue(worker.contains("case LATEST_SUMMARY -> executeCapturedWithExitCode"));
        assertTrue(worker.contains("ButlerSleeperLiveWaiverLatestGovernedDecisionSummaryCli.runPersistentWorkerEmbedded("));
        assertTrue(worker.contains("case LATEST_SUMMARY_DIAGNOSTIC -> executeCapturedWithExitCode"));
        assertTrue(worker.contains("ButlerSleeperLiveWaiverLatestGovernedDecisionSummaryCli.runDiagnosticEmbedded("));
        assertTrue(worker.contains("case TARGET_VERIFY_DIAGNOSTIC -> executeCapturedWithExitCode"));
        assertTrue(worker.contains("ButlerSleeperPersonalTargetVerificationDiagnosticCli.runEmbedded("));
        assertTrue(worker.contains("case WAIVER_DASHBOARD_BUNDLE -> executeCapturedWithExitCode"));
        assertTrue(worker.contains("ButlerWaiverDashboardEvidenceBundleCli.runEmbedded("));
        assertTrue(worker.contains("case MATCHUP_BUNDLE -> executeCapturedWithExitCode"));
        assertTrue(worker.contains("ButlerWeeklyMatchupEvidenceBundleCli.runEmbedded("));
        assertTrue(worker.contains("case EXPLANATION_LOOKUP -> executeCapturedWithExitCode"));
        assertTrue(worker.contains("ButlerSleeperLiveWaiverGovernedExplanationLookupCli.runEmbedded("));
        assertTrue(worker.contains("new String[] {request.leagueId(), request.argument()}"));
        assertFalse(worker.contains("ButlerSleeperLiveWaiverLatestGovernedDecisionSummaryCli.main("));
        assertFalse(worker.contains("ButlerSleeperLiveWaiverTargetRosterContextAuditCli.main("));
        assertFalse(worker.contains("ButlerSleeperLiveWaiverGovernedExplanationLookupCli.main("));
        assertFalse(worker.contains("Class.forName"));
        assertFalse(worker.contains("ProcessBuilder"));
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
        throw new IOException("BF-742 test could not locate " + relativePath);
    }
}
