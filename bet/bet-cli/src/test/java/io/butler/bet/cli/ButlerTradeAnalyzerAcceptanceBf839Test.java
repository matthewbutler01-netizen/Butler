package io.butler.bet.cli;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ButlerTradeAnalyzerAcceptanceBf839Test {

    @Test
    void acceptanceDrivesLiveTradeJourneyWithoutHardCodedAssets() throws Exception {
        String script = source("scripts/butler-trade-analyzer-acceptance.ps1");

        for (String marker : new String[]{
                "Butler Trade Analyzer end-to-end acceptance (BF-839)",
                "/trade?load=1",
                "Get-OpponentId",
                "Get-ValuedAsset",
                "Get-ValuedAsset -Html $opponentPage.Body -Name \'give\'",
                "Get-ValuedAsset -Html $opponentPage.Body -Name \'receive\'",
                "&evaluate=1&give=",
                "&receive=",
                "Butler recommendation",
                "Package recommendation:",
                "Why Butler says this",
                "Raw decision record",
                "GOVERNED_TRADE_RECOMMENDATION_RENDERED",
                "Working tree: CLEAN",
                "BF-839 RESULT: COMPLETE"
        }) {
            assertTrue(script.contains(marker), "BF-839 acceptance missing " + marker);
        }

        assertFalse(script.contains("a75ccbfa-18b4-4e02-9d21-ccb0356568cf"));
        assertFalse(script.contains("1312110516008677376"));
        assertFalse(script.contains("mbutler0624"));
    }

    @Test
    void acceptanceIsGetOnlyAndOwnsItsProcessCleanup() throws Exception {
        String script = source("scripts/butler-trade-analyzer-acceptance.ps1");
        String cmd = source("scripts/butler-trade-analyzer-acceptance.cmd");

        assertTrue(script.contains("$request.Method = 'GET'"));
        assertTrue(script.contains("GET-only local Butler requests"));
        assertTrue(script.contains("/refresh excluded"));
        assertTrue(script.contains("taskkill /PID $Process.Id /T /F"));
        assertTrue(script.contains("running-port-{0}.txt"));
        assertTrue(script.contains("git status --porcelain=v1 --untracked-files=all"));
        assertTrue(cmd.contains("butler-trade-analyzer-acceptance.ps1"));

        assertFalse(script.contains("Method = 'POST'"));
        assertFalse(script.contains("Method = \"POST\""));
        assertFalse(script.contains("Invoke-RestMethod"));
        assertFalse(script.contains("Invoke-WebRequest"));
        assertFalse(script.contains("submitTransaction"));
        assertFalse(script.contains("setFaab"));
    }

    @Test
    void acceptanceFailsClosedOnMissingLiveEvidenceOrBlockedSurface() throws Exception {
        String script = source("scripts/butler-trade-analyzer-acceptance.ps1");

        assertTrue(script.contains("no selectable current league opponent was rendered"));
        assertTrue(script.contains("no currently valued $Name asset was rendered"));
        assertTrue(script.contains("Butler Trade Analyzer blocked"));
        assertTrue(script.contains("retired Trade Lab"));
        assertTrue(script.contains("repository must be clean before acceptance"));
        assertTrue(script.contains("repository became dirty during acceptance"));
        assertTrue(script.contains("public recommendation exposed internal technical label"));
        assertTrue(script.contains("Advanced technical record"));
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
        throw new IOException("BF-839 test could not locate " + relativePath);
    }
}
