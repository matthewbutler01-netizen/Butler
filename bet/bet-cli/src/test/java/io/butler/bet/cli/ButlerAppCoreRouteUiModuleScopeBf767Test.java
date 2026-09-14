package io.butler.bet.cli;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ButlerAppCoreRouteUiModuleScopeBf767Test {

    @Test
    void exactCoreReadsUseOnlyHistoryAndDecisionRefresh() throws Exception {
        String wrapper = source("scripts/butler-app-request-worker-cache.ps1");

        assertTrue(wrapper.contains("$bf767CoreRead = $requestTarget -ceq '/' -or"));
        assertTrue(wrapper.contains("$requestTarget -ceq '/team' -or"));
        assertTrue(wrapper.contains("$requestTarget -ceq '/waivers' -or"));
        assertTrue(wrapper.contains("$requestTarget -ceq '/league'"));
        assertTrue(wrapper.contains("$bf766ModuleSpecs = if ($bf767CoreRead)"));

        String coreBlock = between(wrapper,
            "$bf766ModuleSpecs = if ($bf767CoreRead) {",
            "    else {");
        assertTrue(coreBlock.contains("Name = 'History'"));
        assertTrue(coreBlock.contains("ButlerBf766HistoryScriptBlock"));
        assertTrue(coreBlock.contains("Name = 'DecisionRefresh'"));
        assertTrue(coreBlock.contains("ButlerBf766DecisionRefreshScriptBlock"));
        assertFalse(coreBlock.contains("Name = 'TradeHost'"));
        assertFalse(coreBlock.contains("Name = 'TradeLab'"));
        assertFalse(coreBlock.contains("Name = 'Detail'"));
        assertTrue(coreBlock.indexOf("Name = 'History'") < coreBlock.indexOf("Name = 'DecisionRefresh'"));
    }

    @Test
    void nonCoreRoutesRetainHistoricalFiveModuleOrder() throws Exception {
        String wrapper = source("scripts/butler-app-request-worker-cache.ps1");
        String fullBlock = between(wrapper,
            "    else {\n        @(\n            [pscustomobject]@{ Name = 'TradeHost'",
            "\n        )\n    }\n\n    foreach ($bf766ModuleSpec");

        int tradeHost = fullBlock.indexOf("Name = 'TradeHost'");
        int tradeLab = fullBlock.indexOf("Name = 'TradeLab'");
        int history = fullBlock.indexOf("Name = 'History'");
        int detail = fullBlock.indexOf("Name = 'Detail'");
        int refresh = fullBlock.indexOf("Name = 'DecisionRefresh'");
        assertTrue(tradeHost >= 0 && tradeLab > tradeHost && history > tradeLab && detail > history && refresh > detail,
            "BF-767 non-core requests must preserve TradeHost -> TradeLab -> History -> Detail -> DecisionRefresh");
    }

    @Test
    void bf766CacheSafetyAndHistoricalWorkerFallbackRemainIntact() throws Exception {
        String wrapper = source("scripts/butler-app-request-worker-cache.ps1");
        String worker = source("scripts/butler-app-request-worker.ps1");

        assertTrue(wrapper.contains("[System.IO.File]::ReadAllText($bf766ModuleSpec.Path, [System.Text.Encoding]::ASCII)"));
        assertTrue(wrapper.contains("$bf766Module = [scriptblock]::Create($bf766Source)"));
        assertTrue(wrapper.contains("$bf766Module -isnot [scriptblock]"));
        assertTrue(wrapper.contains("[string]$bf766CachedPath -cne [string]$bf766ModuleSpec.Path"));
        assertTrue(wrapper.contains(". $bf766Module"));
        assertTrue(wrapper.contains("\\. \\$DecisionRefresh\\r?$"));

        int tradeHost = worker.indexOf("    . $TradeHost");
        int tradeLab = worker.indexOf("    . $TradeLab");
        int history = worker.indexOf("    . $History");
        int detail = worker.indexOf("    . $Detail");
        int refresh = worker.indexOf("    . $DecisionRefresh");
        assertTrue(tradeHost >= 0 && tradeLab > tradeHost && history > tradeLab && detail > history && refresh > detail);
        assertFalse(worker.contains("bf767CoreRead"));
        assertFalse(worker.contains("ButlerBf766HistoryScriptBlock"));

        assertTrue(worker.contains("Local\\Butler.Team.Read.{0}"));
        assertTrue(worker.contains("Local\\Butler.Expensive.Read.{0}.{1}"));
        assertTrue(worker.contains("Local\\Butler.Companion.Heavy.{0}"));
        assertTrue(worker.contains("Consume-RefreshToken -State $RefreshState -SubmittedToken $submittedToken"));
        assertFalse(worker.contains("create_transaction"));
        assertFalse(worker.contains("submitTransaction"));
        assertFalse(worker.contains("waiver_budget"));
    }

    @Test
    void bf767WindowsSourcesRemainAsciiOnly() throws Exception {
        assertAscii(source("scripts/butler-app-request-worker-cache.ps1"));
        assertAscii(source("scripts/butler-app-request-worker.ps1"));
    }

    private static String between(String text, String start, String end) {
        int startIndex = text.indexOf(start);
        assertTrue(startIndex >= 0, "missing start marker: " + start);
        int endIndex = text.indexOf(end, startIndex + start.length());
        assertTrue(endIndex > startIndex, "missing end marker: " + end);
        return text.substring(startIndex, endIndex);
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
        throw new IOException("BF-767 test could not locate " + relativePath);
    }
}
