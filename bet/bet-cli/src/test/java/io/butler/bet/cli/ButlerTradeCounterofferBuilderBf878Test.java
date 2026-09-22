package io.butler.bet.cli;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ButlerTradeCounterofferBuilderBf878Test {

    @Test
    void counterPipelineRunsOnlyAfterExplicitCompleteRejectRequest() throws Exception {
        String trade = source("scripts/butler-trade-lab.ps1");

        assertTrue(trade.contains("$counterRequested = (Get-TradeQueryFirst -Query $query -Name 'counter') -ceq '1'"));
        assertTrue(trade.contains("if ($counterRequested -and -not $evaluate)"));
        assertTrue(trade.contains("if ($Evaluation.EvidenceComplete -and $Evaluation.Action -ceq 'REJECT' -and $null -eq $CounterProposal)"));
        assertTrue(trade.contains("Build Counteroffer"));
        assertTrue(trade.contains("if ($counterRequested)"));
        assertTrue(trade.contains("if (-not $evaluation.EvidenceComplete -or $evaluation.Action -cne 'REJECT')"));
        assertEquals(1, count(trade, "trade counter-proposal $LeagueId"),
                "counter proposal CLI must have one explicit invocation site");
    }

    @Test
    void exactOwnershipAndBoundPerspectiveAreRevalidatedBeforeCounterRendering() throws Exception {
        String trade = source("scripts/butler-trade-lab.ps1");

        int give = trade.indexOf("$give = @(Assert-TradeAssetSelection -Team $userTeam");
        int receive = trade.indexOf("$receive = @(Assert-TradeAssetSelection -Team $opponent");
        int recommendation = trade.indexOf("trade recommendation $LeagueId");
        int counter = trade.indexOf("trade counter-proposal $LeagueId");
        int counterPerspective = trade.indexOf("$counterProposal.PerspectiveTeamId -cne $userTeam.TeamId");

        assertTrue(give >= 0 && receive > give);
        assertTrue(recommendation > receive);
        assertTrue(counter > recommendation);
        assertTrue(counterPerspective > counter);
        assertTrue(trade.contains("$v6Action = [regex]::Match"));
        assertTrue(trade.contains("V6Action = $v6Action.Groups['value'].Value.Trim()"));
        assertFalse(trade.contains("$counterProposal.V5Action"));
        assertFalse(trade.contains("legacy v5 counter engine attempted a COUNTER"));
        assertTrue(trade.contains("counter proposal requires the exact evaluated trade coordinates"));
        assertTrue(trade.contains("counter proposal perspective does not match the exact bound user team"));
    }

    @Test
    void governedCounterParserFailsClosedAcrossAllActionStates() throws Exception {
        String trade = source("scripts/butler-trade-lab.ps1");

        for (String marker : new String[]{
                "V6 team action:",
                "Counter opportunity:",
                "Counter candidate selection:",
                "Counter action:",
                "Counter action reason:",
                "Counter materialized package state:",
                "Counter negotiation message state:",
                "COUNTER:",
                "Revised Side A package:",
                "Revised Side B package:",
                "Negotiation message:",
                "Counter proposal fingerprint:"
        }) {
            assertTrue(trade.contains(marker), "missing governed counter parser marker " + marker);
        }

        assertTrue(trade.contains("'COUNTER' {"));
        assertTrue(trade.contains("$view.MaterializedState -cne 'MATERIALIZED'"));
        assertTrue(trade.contains("$view.MessageState -cne 'MESSAGE_AVAILABLE'"));
        assertTrue(trade.contains("'NO_ACTION' {"));
        assertTrue(trade.contains("$view.MaterializedState -cne 'NO_PACKAGE'"));
        assertTrue(trade.contains("$view.MessageState -cne 'NO_MESSAGE'"));
        assertTrue(trade.contains("'INCONCLUSIVE' {"));
        assertTrue(trade.contains("$view.MaterializedState -cne 'INCONCLUSIVE'"));
        assertTrue(trade.contains("$view.MessageState -cne 'INCONCLUSIVE'"));
        assertTrue(trade.contains("unsupported governed counter action"));
    }

    @Test
    void counterResultShowsManagerFacingProposalPackagesAndManualMessage() throws Exception {
        String trade = source("scripts/butler-trade-lab.ps1");

        for (String marker : new String[]{
                "Butler counteroffer",
                "Counter available",
                "Your revised package",
                "Their revised package",
                "Message you can send manually",
                "It has not been sent and the trade has not been submitted.",
                "No governed counteroffer",
                "Counteroffer unavailable",
                "Counteroffer details"
        }) {
            assertTrue(trade.contains(marker), "missing Counteroffer Builder UI marker " + marker);
        }

        assertTrue(trade.contains("ConvertTo-TradeCounterManagerText"));
        assertTrue(trade.contains("ConvertTo-TradePackageDisplay"));
        assertTrue(trade.contains("Ask $OpponentName to add $name to their side."));
        assertTrue(trade.contains("Ask $OpponentName to remove $name from their side."));
    }

    @Test
    void materializedPackageLabelsMustResolveExactlyOnceInCurrentInventory() throws Exception {
        String trade = source("scripts/butler-trade-lab.ps1");

        assertTrue(trade.contains("$Inventory.Teams | ForEach-Object { $_.Assets }"));
        assertTrue(trade.contains("$matches.Count -ne 1"));
        assertTrue(trade.contains("did not resolve exactly once in current league inventory"));
        assertTrue(trade.contains("governed materialized package has an unsupported display shape"));
    }

    @Test
    void featureRemainsProposalOnlyWithNoAuthorizationHandoffOrWritePath() throws Exception {
        String trade = source("scripts/butler-trade-lab.ps1");
        String validator = source("scripts/butler-app-bf831-trade-analyzer-transform.ps1");

        assertTrue(trade.contains("Read-only. Butler will not send or submit anything."));
        assertTrue(trade.contains("existing governed read-only counteroffer"));
        assertTrue(trade.contains("It cannot refresh evidence, authorize, hand off, send, finalize, or submit a counter"));

        for (String forbidden : new String[]{
                "counter-handoff",
                "counter-authorization",
                "Method = \"POST\"",
                "Invoke-RestMethod",
                "Invoke-WebRequest",
                "https://api.sleeper.app"
        }) {
            assertFalse(trade.contains(forbidden), "Trade Analyzer introduced forbidden execution marker " + forbidden);
        }

        assertTrue(validator.contains("'counter-handoff'"));
        assertTrue(validator.contains("'counter-authorization'"));
        assertTrue(validator.contains("'trade counter-proposal $LeagueId'"));
        assertTrue(validator.contains("'Build Counteroffer'"));
    }

    private static int count(String text, String needle) {
        int total = 0;
        int from = 0;
        while (true) {
            int index = text.indexOf(needle, from);
            if (index < 0) {
                return total;
            }
            total++;
            from = index + needle.length();
        }
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
        throw new IOException("BF-878 test could not locate " + relativePath);
    }
}
