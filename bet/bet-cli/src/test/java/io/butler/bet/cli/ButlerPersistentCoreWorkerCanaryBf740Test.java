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
    void canaryTransformTargetsOnlyTeamAndLeagueCoreReads() throws Exception {
        String transform = source("scripts/butler-core-bf740-transform.ps1");
        String helper = source("scripts/butler-persistent-core-worker.ps1");
        String core = source("scripts/butler-app-shell-core-single.ps1");
        String dashboard = source("scripts/butler-dashboard.ps1");

        assertTrue(transform.contains("BUTLER_APP_PERSISTENT_CORE_WORKER"));
        assertTrue(transform.contains("Invoke-Bf740PersistentCoreWorker -Operation 'TEAM_BUNDLE'"));
        assertTrue(transform.contains("Invoke-Bf740PersistentCoreWorker -Operation 'LEAGUE_OVERVIEW'"));
        assertTrue(transform.contains(":bet:bet-cli:sleeperLiveWaiverTargetRosterContextAudit"));
        assertTrue(transform.contains("$Arguments -ceq \"$LeagueId --team-bundle\""));
        assertTrue(transform.contains("Start-Bf740PersistentCoreWorker"));
        assertTrue(transform.contains("Stop-Bf740PersistentCoreWorker"));

        assertTrue(helper.contains("ValidateSet('LEAGUE_OVERVIEW', 'TEAM_BUNDLE')"));
        assertTrue(helper.contains("TimeoutMs 180000"));
        assertTrue(helper.contains("^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$"));
        assertTrue(helper.contains("terminated to prevent protocol desynchronization"));
        assertFalse(helper.contains("sleeperLiveWaiverComparisonBundle"));
        assertFalse(helper.contains("production-refresh"));
        assertFalse(helper.contains("/refresh"));

        assertFalse(core.contains("Bf740PersistentCoreWorkerCanary"));
        assertFalse(dashboard.contains("Bf740PersistentCoreWorkerCanary"));
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
    void dashboardStagingInvokesCoreTransformOnlyWhenCanaryIsExplicitlyEnabled() throws Exception {
        String staging = source("scripts/butler-dashboard-bf715-transform.ps1");
        int writeDashboard = staging.indexOf("WriteAllText($DashboardPath");
        int canaryCheck = staging.indexOf("BUTLER_APP_PERSISTENT_CORE_WORKER");
        int coreTransform = staging.indexOf("butler-core-bf740-transform.ps1");

        assertTrue(writeDashboard >= 0);
        assertTrue(canaryCheck > writeDashboard);
        assertTrue(coreTransform > canaryCheck);
        assertTrue(staging.contains("-ceq '1'"));
        assertTrue(staging.contains("butler-app-shell-core-single.ps1"));
    }

    @Test
    void acceptanceScopesCanaryToOwnedBf698RunAndClearsItBeforeDiagnostics() throws Exception {
        String command = source("scripts/butler-acceptance.cmd");
        int enable = command.indexOf("set \"BUTLER_APP_PERSISTENT_CORE_WORKER=1\"");
        int acceptance = command.indexOf("butler-acceptance.ps1");
        int clear = command.indexOf("set \"BUTLER_APP_PERSISTENT_CORE_WORKER=\"");
        int slowDiagnostic = command.indexOf("butler-slow-route-stage-diagnostic.ps1");

        assertTrue(enable >= 0);
        assertTrue(acceptance > enable);
        assertTrue(clear > acceptance);
        assertTrue(slowDiagnostic > clear);
        assertTrue(command.contains("normal app launch remains unchanged"));
    }

    @Test
    void workerImplementationBindsAuthorizedOperationsToExactExistingCliEntryPoints() throws Exception {
        String worker = source("bet/bet-cli/src/main/java/io/butler/bet/cli/ButlerReadOnlyJvmWorker.java");

        assertTrue(worker.contains("new String[] {\"league\", \"overview\", request.leagueId()}"));
        assertTrue(worker.contains("ButlerSleeperLiveWaiverTargetRosterContextAuditCli.main("));
        assertTrue(worker.contains("new String[] {request.leagueId(), \"--team-bundle\"}"));
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
        throw new IOException("BF-740 test could not locate " + relativePath);
    }
}
