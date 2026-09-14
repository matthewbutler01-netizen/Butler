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
        int history = wrapper.indexOf("ButlerBf766HistoryScriptBlock");
        int detail = wrapper.indexOf("ButlerBf766DetailScriptBlock");
        int refresh = wrapper.indexOf("ButlerBf766DecisionRefreshScriptBlock");
        assertTrue(tradeHost >= 0 && tradeLab > tradeHost && history > tradeLab && detail > history && refresh > detail,
            "BF-766 must preserve TradeHost -> TradeLab -> History -> Detail -> DecisionRefresh execution order");
    }

    @Test
    void transformIsExactAndHistoricalWorkerKeepsOriginalDotSourceFallback() throws Exception {
        String wrapper = source("scripts/butler-app-request-worker-cache.ps1");
        String worker = source("scripts/butler-app-request-worker.ps1");

        assertTrue(wrapper.contains("$moduleLoadMatches = [regex]::Matches($implementation, $moduleLoadPattern)"));
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
