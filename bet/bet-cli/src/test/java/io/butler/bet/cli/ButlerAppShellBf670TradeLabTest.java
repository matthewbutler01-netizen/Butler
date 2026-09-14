package io.butler.bet.cli;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ButlerAppShellBf670TradeLabTest {

    @Test
    void publicShellOwnsTradeRouteAndKeepsGetOnlyBoundary() throws Exception {
        String shell = script("scripts/butler-app-shell.ps1");
        String worker = script("scripts/butler-app-request-worker.ps1");
        String host = script("scripts/butler-trade-lab-host.ps1");

        assertTrue(shell.contains("butler-app-shell-core.ps1"));
        assertTrue(shell.contains("butler-trade-lab-host.ps1"));
        assertTrue(shell.contains("butler-trade-lab.ps1"));
        assertTrue(shell.contains(". $tradeHost"));
        assertTrue(shell.contains(". $tradeLab"));
        assertTrue(worker.contains("$path -eq '/trade'"));
        assertTrue(worker.contains("Invoke-TradeLabHtml"));
        assertTrue(worker.contains("$parts[0] -ne 'GET'"));
        assertTrue(worker.contains("-Body 'GET only'"));
        assertTrue(worker.contains("form-action 'self'"));
        assertTrue(host.contains("href=`\"/trade`\""));
        assertTrue(host.contains("Add-TradeNavigation"));
        assertFalse(shell.contains("method=\"post\""));
        assertFalse(worker.contains("method=\"post\""));
    }

    @Test
    void firstTradeNavigationPaintsBeforeGovernedGradleReads() throws Exception {
        String worker = script("scripts/butler-app-request-worker.ps1");
        String host = script("scripts/butler-trade-lab-host.ps1");

        assertTrue(worker.contains("$requestTarget -ceq '/trade'"));
        assertTrue(worker.contains("Get-TradeLabLoadingHtml -LeagueId $LeagueId"));
        assertTrue(host.contains("function Get-TradeLabLoadingHtml"));
        assertTrue(host.contains("http-equiv=\"refresh\" content=\"1;url=/trade?load=1\""));
        assertTrue(host.contains("Opening Trade Lab..."));
        assertTrue(host.contains("The workspace will appear automatically."));
        assertTrue(host.contains("No proposal, transaction, or Sleeper write is being executed."));

        int immediatePaint = worker.indexOf("Get-TradeLabLoadingHtml -LeagueId $LeagueId");
        int governedLoad = worker.indexOf("Invoke-TradeLabHtml -LeagueId $LeagueId -RequestTarget $requestTarget");
        assertTrue(immediatePaint >= 0 && governedLoad > immediatePaint);
    }

    @Test
    void windowsPowerShell51UsesUnambiguousTradeQueryParser() throws Exception {
        String shell = script("scripts/butler-app-shell.ps1");

        int tradeModuleLoad = shell.indexOf(". $tradeLab");
        int compatibilityOverride = shell.indexOf("function ConvertFrom-TradeRequestTarget");
        assertTrue(tradeModuleLoad >= 0 && compatibilityOverride > tradeModuleLoad);
        assertTrue(shell.contains("$equals = $pair.IndexOf('=')"));
        assertTrue(shell.contains("$pair.Substring(0, $equals)"));
        assertTrue(shell.contains("$pair.Substring($equals + 1)"));
        assertFalse(shell.contains(".Split(@('='), 2)"));
    }

    @Test
    void preservedCoreStillCarriesBf667AndBf668AppBehavior() throws Exception {
        String core = script("scripts/butler-app-shell-core-single.ps1");

        assertTrue(core.contains("$path -eq \"/league\""));
        assertTrue(core.contains("$path -eq \"/team\""));
        assertTrue(core.contains(":bet:bet-cli:sleeperLiveWaiverTargetRosterContextAudit"));
        assertTrue(core.contains("Exact BF-623-bound live roster context from BF-610"));
        assertTrue(core.contains("READ ONLY."));
    }

    @Test
    void tradeModuleUsesExactPersistedOwnershipAndCurrentGovernedV5Route() throws Exception {
        String trade = script("scripts/butler-trade-lab.ps1");

        assertTrue(trade.contains("league assets $LeagueId"));
        assertTrue(trade.contains(":bet:bet-cli:sleeperLiveWaiverTargetRosterContextAudit"));
        assertTrue(trade.contains("Assert-TradeAssetSelection"));
        assertTrue(trade.contains("$allowed.Contains($token)"));
        assertTrue(trade.contains("trade recommendation $LeagueId $($roster.Season) $sideA $sideB side-a"));
        assertTrue(trade.contains("PerspectiveTeamId -cne $userTeam.TeamId"));
        assertTrue(trade.contains("-Name 'give'"));
        assertTrue(trade.contains("-Name 'receive'"));
        assertTrue(trade.contains("name=\"opponent\""));
        assertTrue(trade.contains("method=\"get\" action=\"/trade\""));
        assertTrue(trade.contains("ConvertTo-HtmlText $Evaluation.Raw"));
    }

    @Test
    void tradeLabDoesNotExposeMutationCounterOrExecutionCommands() throws Exception {
        String trade = script("scripts/butler-trade-lab.ps1");
        String shell = script("scripts/butler-app-shell.ps1");
        String worker = script("scripts/butler-app-request-worker.ps1");

        assertFalse(trade.contains("trade counter-proposal"));
        assertFalse(trade.contains("trade counter-authorize"));
        assertFalse(trade.contains("trade counter-finalize"));
        assertFalse(trade.contains("trade counter-handoff"));
        assertFalse(trade.contains("sleeperLiveWaiverSnapshotSync"));
        assertFalse(trade.contains("sleeperLiveWaiverMarketAttentionSync"));
        assertFalse(trade.contains("Invoke-Expression"));
        assertFalse(trade.contains("Start-Job"));
        assertFalse(trade.contains("Stop-Process"));
        assertFalse(shell.contains("trade counter-"));
        assertFalse(worker.contains("trade counter-"));
        assertFalse(shell.contains("sleeperLiveWaiverSnapshotSync"));
        assertFalse(worker.contains("sleeperLiveWaiverSnapshotSync"));
        assertFalse(shell.contains("sleeperLiveWaiverMarketAttentionSync"));
        assertFalse(worker.contains("sleeperLiveWaiverMarketAttentionSync"));
    }

    @Test
    void tradeLabFilesRemainAsciiOnly() throws Exception {
        assertAscii(script("scripts/butler-app-shell.ps1"));
        assertAscii(script("scripts/butler-app-request-worker.ps1"));
        assertAscii(script("scripts/butler-app-shell-core.ps1"));
        assertAscii(script("scripts/butler-app-shell-core-single.ps1"));
        assertAscii(script("scripts/butler-trade-lab-host.ps1"));
        assertAscii(script("scripts/butler-trade-lab.ps1"));
    }

    private static void assertAscii(String text) {
        byte[] encoded = text.getBytes(StandardCharsets.US_ASCII);
        assertEquals(text, new String(encoded, StandardCharsets.US_ASCII));
    }

    private static String script(String relativePath) throws IOException {
        Path current = Path.of(System.getProperty("user.dir")).toAbsolutePath().normalize();
        for (int depth = 0; depth < 7 && current != null; depth++) {
            Path candidate = current.resolve(relativePath);
            if (Files.isRegularFile(candidate)) {
                return Files.readString(candidate, StandardCharsets.US_ASCII);
            }
            current = current.getParent();
        }
        throw new IOException("BF-670 test could not locate " + relativePath);
    }
}
