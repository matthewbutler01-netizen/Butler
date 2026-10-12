package io.butler.bet.cli;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ButlerAppPublicUiModuleCacheBf766Test {

    @Test
    void cachedEntrypointParsesFivePublicUiModulesOncePerRunspace() throws Exception {
        String wrapper = source("scripts/butler-app-request-worker-cache.ps1");

        assertTrue(wrapper.contains("BF-766 preserves the historical worker source and its health fast path"));
        assertTrue(wrapper.contains("[System.IO.File]::ReadAllText($bf766ModuleSpec.Path, [System.Text.Encoding]::ASCII)"));
        assertTrue(wrapper.contains("$bf766Module = [scriptblock]::Create($bf766Source)"));
        assertTrue(wrapper.contains("Set-Variable -Name $bf766ModuleSpec.CacheName -Scope Global -Value $bf766Module"));
        assertTrue(wrapper.contains("Set-Variable -Name $bf766PathCacheName -Scope Global -Value ([string]$bf766ModuleSpec.Path)"));
        assertTrue(wrapper.contains("$bf766Module -isnot [scriptblock]"));
        assertTrue(wrapper.contains("[string]$bf766CachedPath -cne [string]$bf766ModuleSpec.Path"));
        assertTrue(wrapper.contains(". $bf766Module"));

        int tradeHost = wrapper.indexOf("ButlerBf766TradeHostScriptBlock");
        int tradeLab = wrapper.indexOf("ButlerBf766TradeLabScriptBlock");
        int history = wrapper.lastIndexOf("ButlerBf766HistoryScriptBlock");
        int detail = wrapper.indexOf("ButlerBf766DetailScriptBlock");
        int refresh = wrapper.lastIndexOf("ButlerBf766DecisionRefreshScriptBlock");
        assertTrue(tradeHost >= 0 && tradeLab > tradeHost && history > tradeLab && detail > history && refresh > detail,
            "BF-766 full module path must preserve TradeHost -> TradeLab -> History -> Detail -> DecisionRefresh execution order");
    }

    @Test
    void transformIsExactAndHistoricalWorkerKeepsOriginalDotSourceFallback() throws Exception {
        String wrapper = source("scripts/butler-app-request-worker-cache.ps1");
        String worker = source("scripts/butler-app-request-worker.ps1");

        assertTrue(wrapper.contains("$moduleLoadMatches = [regex]::Matches($implementation, $moduleLoadPattern)"));
        assertTrue(wrapper.contains("\\$DecisionRefresh\\r?$'"),
            "BF-766 transform must match both LF and CRLF on the final module line");
        assertTrue(wrapper.contains("if ($moduleLoadMatches.Count -ne 1)"));
        assertTrue(wrapper.contains("expected exactly 1"));
        assertTrue(wrapper.contains("$moduleLoadMatch = $moduleLoadMatches[0]"));
        assertTrue(wrapper.contains("$implementation.Substring(0, $moduleLoadMatch.Index)"));
        assertTrue(wrapper.contains("$implementation.Substring($moduleLoadMatch.Index + $moduleLoadMatch.Length)"));

        int tradeHost = worker.indexOf("    . $TradeHost");
        int tradeLab = worker.indexOf("    . $TradeLab");
        int history = worker.indexOf("    . $History");
        int detail = worker.indexOf("    . $Detail");
        int refresh = worker.indexOf("    . $DecisionRefresh");
        assertTrue(tradeHost >= 0 && tradeLab > tradeHost && history > tradeLab && detail > history && refresh > detail);

        assertFalse(worker.contains("ButlerBf766TradeHostScriptBlock"));
        assertFalse(worker.contains("ButlerBf766TradeLabScriptBlock"));
        assertFalse(worker.contains("ButlerBf766HistoryScriptBlock"));
        assertFalse(worker.contains("ButlerBf766DetailScriptBlock"));
        assertFalse(worker.contains("ButlerBf766DecisionRefreshScriptBlock"));
    }

    @Test
    void invalidFirstOpenUiModulesReturnAUsefulFailClosed503() throws Exception {
        String wrapper = source("scripts/butler-app-request-worker-cache.ps1");
        String worker = source("scripts/butler-app-request-worker.ps1");

        int loadStart = wrapper.indexOf("try {\n        foreach ($bf766ModuleSpec in $bf766ModuleSpecs)");
        int moduleLoad = wrapper.indexOf(". $bf766Module", loadStart);
        int recover = wrapper.indexOf("catch {\n        # No response headers have been written", moduleLoad);
        int response = wrapper.indexOf("Send-HttpResponse -Stream $stream -StatusCode 503", recover);
        int exit = wrapper.indexOf("\n        return\n    }", response);
        int workerEntry = wrapper.indexOf("$worker = [scriptblock]::Create($implementation)");

        assertTrue(loadStart >= 0 && moduleLoad > loadStart && recover > moduleLoad);
        assertTrue(response > recover && exit > response && workerEntry > exit,
            "BF-1125 UI module failures must respond and stop before routing");
        String safeCatch = wrapper.substring(recover, exit);
        assertTrue(safeCatch.contains("Get-ButlerMinimalBlockedPageHtml"));
        assertTrue(safeCatch.contains("-FailureLayer 'PUBLIC_WORKER'"));
        assertFalse(safeCatch.contains("$_.Exception.Message"));
        assertFalse(safeCatch.contains("$LeagueId"));
        assertFalse(safeCatch.contains("submitTransaction"));
        assertTrue(worker.contains("function Get-ButlerMinimalBlockedPageHtml"));
        assertTrue(worker.contains("function Send-HttpResponse"));
        assertTrue(worker.contains("BF-1112 BLOCKED: invalid local response failure layer"));
    }

    @Test
    void firstOpenPublicWorker503BypassesOptionalPresentationWithoutWeakeningNormalPages() throws Exception {
        // BF-1127: rendering a fail-closed first-open 503 must not depend on
        // optional UI presentation helpers after a module import has failed.
        String wrapper = source("scripts/butler-app-request-worker-cache.ps1");
        String worker = source("scripts/butler-app-request-worker.ps1");

        int sendBoundary = wrapper.indexOf("$bodyEncodingReplacement = @'");
        int gate = wrapper.indexOf("if ($ContentType -match '^text/html' -and", sendBoundary);
        int exception = wrapper.indexOf(
            "-not ($StatusCode -eq 503 -and $FailureLayer -ceq 'PUBLIC_WORKER')", gate);
        int present = wrapper.indexOf("ConvertTo-ButlerUserFacingHtml -Html $Body", exception);
        int accessible = wrapper.indexOf("Add-ButlerAccessibility -Html $Body", present);
        int encode = wrapper.indexOf("$bodyBytes = [System.Text.Encoding]::UTF8.GetBytes($Body)", accessible);

        assertTrue(sendBoundary >= 0 && gate > sendBoundary && exception > gate);
        assertTrue(present > exception && accessible > present && encode > accessible,
            "BF-1127 only the safe public-worker 503 may skip optional HTML presentation");
        assertTrue(wrapper.contains("if (-not $bf890AcceptanceDiagnostic)"),
            "normal HTML responses must retain their existing presentation controls");
        assertTrue(wrapper.contains("-StatusCode 503 -StatusText 'Service Unavailable'"));
        assertTrue(wrapper.contains("-FailureLayer 'PUBLIC_WORKER'"));
        assertTrue(worker.contains("function Get-ButlerMinimalBlockedPageHtml"));
        assertTrue(worker.contains("No Sleeper transaction was submitted by this page."));
        assertFalse(wrapper.contains("submitTransaction"));
    }

    @Test
    void independentManager500DoesNotReenterBrokenPresentationPipeline() throws Exception {
        // BF-1129: a failed styled error renderer falls back to the small,
        // independently escaped HTML page; the response writer must not
        // send that same page back through optional UI presentation.
        String wrapper = source("scripts/butler-app-request-worker-cache.ps1");
        String worker = source("scripts/butler-app-request-worker.ps1");

        int responseParam = worker.indexOf("[switch]$SkipOptionalHtmlPresentation");
        int managerError = worker.indexOf("$bf1129MinimalFallback = $false");
        int styled = worker.indexOf("$errorHtml = Get-ButlerBlockedPageHtml", managerError);
        int minimal = worker.indexOf("$errorHtml = Get-ButlerMinimalBlockedPageHtml", styled);
        int activate = worker.indexOf("$bf1129MinimalFallback = $true", minimal);
        int send = worker.indexOf("-SkipOptionalHtmlPresentation:$bf1129MinimalFallback", activate);
        assertTrue(responseParam > 0 && managerError > responseParam && styled > managerError);
        assertTrue(minimal > styled && activate > minimal && send > activate,
            "BF-1129 only the independent 500 fallback may set the presentation bypass");

        int gate = wrapper.indexOf("$bf1129SkipMinimal500 = $SkipOptionalHtmlPresentation -and");
        int code = wrapper.indexOf("$StatusCode -eq 500", gate);
        int layer = wrapper.indexOf("@('CORE_READ', 'PUBLIC_WORKER') -ccontains $FailureLayer", code);
        int present = wrapper.indexOf("-not $bf1129SkipMinimal500 -and", layer);
        int format = wrapper.indexOf("ConvertTo-ButlerUserFacingHtml -Html $Body", present);
        assertTrue(gate >= 0 && code > gate && layer > code && present > layer && format > present,
            "BF-1129 bypass must require explicit fallback, exact HTTP 500 and known failure layer");
        assertTrue(wrapper.contains("-not ($StatusCode -eq 503 -and $FailureLayer -ceq 'PUBLIC_WORKER')"));
        assertFalse(wrapper.contains("submitTransaction"));
        assertFalse(worker.contains("submitTransaction"));
    }

    @Test
    void firstOpen503UsesOnlyFixedRouteRetrySpec() throws Exception {
        // BF-1131: a failed UI module may not reflect arbitrary request-target
        // strings or secrets back into a browser hyperlink or page heading.
        String wrapper = source("scripts/butler-app-request-worker-cache.ps1");
        String worker = source("scripts/butler-app-request-worker.ps1");

        int catchStart = wrapper.indexOf("catch {\n        # No response headers have been written");
        int helper = wrapper.indexOf(
            "Get-ButlerManagerFailureSpec -RequestTarget $requestTarget -FailurePhase 'PUBLIC_WORKER'",
            catchStart);
        int safe = wrapper.indexOf(
            "Get-ButlerMinimalBlockedPageHtml -Title $bf1131Retry.Title" +
            " -PrimaryHref $bf1131Retry.PrimaryHref -PrimaryLabel $bf1131Retry.PrimaryLabel",
            helper);
        int send = wrapper.indexOf("Send-HttpResponse -Stream $stream -StatusCode 503", safe);
        assertTrue(catchStart >= 0 && helper > catchStart && safe > helper && send > safe);
        assertFalse(wrapper.substring(catchStart, send).contains("$_"), 
            "BF-1131 no raw module exception may enter the HTTP 503 retry page");
        assertTrue(worker.contains("switch -CaseSensitive ($RequestTarget)"));
        assertTrue(worker.contains("$spec.PrimaryHref = '/team'"));
        assertTrue(worker.contains("$spec.PrimaryHref = '/waivers'"));
        assertTrue(worker.contains("$spec.PrimaryHref = '/matchup/autofill'"));
        assertTrue(worker.contains("$spec.PrimaryHref = '/league'"));
        assertTrue(worker.contains("PrimaryHref = '/'"));
        assertTrue(worker.contains("@('/', '/team', '/waivers', '/matchup',"));
    }

    @Test
    void liveReadinessRejectsPreRecoveryWorkerWithOldHealthMarkers() throws Exception {
        // BF-1134: a 200 from an outdated cached worker is not proof its
        // first-open module errors can return safe HTTP 503/500 pages.
        String worker = source("scripts/butler-app-request-worker.ps1");
        String diagnostic = source("scripts/butler-v04-live-page-check.ps1");
        String acceptance = source("scripts/butler-v04-live-page-check-acceptance.ps1");
        String recovery = "v04-safe-page-recovery-bf1134";

        assertTrue(worker.contains("\\"workerRecoveryContract\\":\\"" + recovery + "\\""));
        assertTrue(diagnostic.contains("$health.workerRecoveryContract -ceq '" + recovery + "'"));
        assertTrue(acceptance.contains("BF-1134 BLOCKED: old manager without safe worker fallback"));
        assertTrue(acceptance.contains("BF-1134 BLOCKED: untrusted manager recovery claim"));
        assertTrue(acceptance.contains("BF-1134 BLOCKED: canonical worker does not advertise"));
        assertTrue(diagnostic.contains("this diagnostic does not restart any process"));
        assertFalse(diagnostic.contains("submitTransaction"));
    }

    @Test
    void healthFastPathStillReturnsBeforeAnyUiModuleLoad() throws Exception {
        String worker = source("scripts/butler-app-request-worker.ps1");

        int health = worker.indexOf("if ($path -eq '/health')");
        int moduleLoad = worker.indexOf("    . $TradeHost");
        assertTrue(health >= 0 && moduleLoad > health,
            "BF-697 health must remain ahead of BF-766 UI module loading");
        assertTrue(worker.contains("\"service\":\"butler-app-shell\""));
    }

    @Test
    void bf766PreservesReadOnlyAndRefreshSafetyBoundariesAndAscii() throws Exception {
        String wrapper = source("scripts/butler-app-request-worker-cache.ps1");
        String worker = source("scripts/butler-app-request-worker.ps1");

        assertTrue(worker.contains("Consume-RefreshToken -State $RefreshState -SubmittedToken $submittedToken"));
        assertTrue(worker.contains("if ($requestTarget -cne '/refresh')"));
        assertTrue(worker.contains("Local\\Butler.Team.Read.{0}"));
        assertTrue(worker.contains("Local\\Butler.Expensive.Read.{0}.{1}"));
        assertTrue(worker.contains("Local\\Butler.Companion.Heavy.{0}"));
        assertFalse(wrapper.contains("create_transaction"));
        assertFalse(wrapper.contains("submitTransaction"));
        assertFalse(wrapper.contains("waiver_budget"));
        assertFalse(worker.contains("create_transaction"));
        assertFalse(worker.contains("submitTransaction"));
        assertFalse(worker.contains("waiver_budget"));

        assertAscii(wrapper);
        assertAscii(worker);
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
                return Files.readString(candidate, StandardCharsets.US_ASCII);
            }
            current = current.getParent();
        }
        throw new IOException("BF-766 test could not locate " + relativePath);
    }
}
