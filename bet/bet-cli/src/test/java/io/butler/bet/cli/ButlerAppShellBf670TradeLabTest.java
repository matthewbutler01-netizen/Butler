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
    void shellOwnsNativeTradeRouteAndKeepsGetOnlyBoundary() throws Exception {
        String shell = script("scripts/butler-app-shell.ps1");

        assertTrue(shell.contains("butler-trade-lab.ps1"));
        assertTrue(shell.contains(". $tradeLab"));
        assertTrue(shell.contains("href=\"/trade\""));
        assertTrue(shell.contains("$path -eq \"/trade\""));
        assertTrue(shell.contains("Invoke-TradeLabHtml"));
        assertTrue(shell.contains("$parts[0] -ne \"GET\""));
        assertTrue(shell.contains("Body \"GET only\""));
        assertFalse(shell.contains("method=\"post\""));
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
        assertTrue(trade.contains("name=\"give\""));
        assertTrue(trade.contains("name=\"receive\""));
        assertTrue(trade.contains("name=\"opponent\""));
        assertTrue(trade.contains("method=\"get\" action=\"/trade\""));
        assertTrue(trade.contains("ConvertTo-HtmlText $Evaluation.Raw"));
    }

    @Test
    void tradeLabDoesNotExposeMutationCounterOrExecutionCommands() throws Exception {
        String trade = script("scripts/butler-trade-lab.ps1");

        assertFalse(trade.contains("trade counter-proposal"));
        assertFalse(trade.contains("trade counter-authorize"));
        assertFalse(trade.contains("trade counter-finalize"));
        assertFalse(trade.contains("trade counter-handoff"));
        assertFalse(trade.contains("sleeperLiveWaiverSnapshotSync"));
        assertFalse(trade.contains("sleeperLiveWaiverMarketAttentionSync"));
        assertFalse(trade.contains("Invoke-Expression"));
        assertFalse(trade.contains("Start-Job"));
        assertFalse(trade.contains("Stop-Process"));
    }

    @Test
    void tradeLabFilesRemainAsciiOnly() throws Exception {
        assertAscii(script("scripts/butler-app-shell.ps1"));
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
