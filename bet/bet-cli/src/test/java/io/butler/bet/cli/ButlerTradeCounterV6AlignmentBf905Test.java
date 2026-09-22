package io.butler.bet.cli;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ButlerTradeCounterV6AlignmentBf905Test {

    @Test
    void counterProposalConsumesTheSameV6DecisionContractAsTradeAnalyzer() throws Exception {
        String counter = source(
            "bet/bet-cli/src/main/java/io/butler/bet/cli/ButlerTradeCounterProposalCli.java");
        String opportunity = source(
            "bet/bet-cli/src/main/java/io/butler/bet/intelligence/TradeCounterOpportunityPolicy.java");

        assertTrue(counter.contains("var v6 = ButlerTradeRecommendationV6Cli.recommend("));
        assertTrue(counter.contains("v6.packageRecommendation()"));
        assertTrue(counter.contains("v6.action()"));
        assertTrue(counter.contains("v6.evidenceStatus().complete()"));
        assertTrue(counter.contains("V6 team action: " + "\" + " + v6.action()"));
        assertTrue(counter.contains("complete v6 REJECT"));

        assertFalse(counter.contains("ButlerTradeRecommendationV5Cli.recommend("));
        assertFalse(counter.contains("V5 team action:"));
        assertFalse(counter.contains("v5.evidenceStatus()"));

        assertTrue(opportunity.contains(
            "trade-counter-opportunity-v2-v6-reject-plus-strategic-eligibility"));
        assertTrue(opportunity.contains("TradeRecommendationAdvisoryPosturePolicy.POLICY_ID"));
        assertTrue(opportunity.contains("V6_EVIDENCE_INCOMPLETE"));
        assertTrue(opportunity.contains("V6_ACTION_NOT_REJECT"));
        assertFalse(opportunity.contains("V5_EVIDENCE_INCOMPLETE"));
        assertFalse(opportunity.contains("V5_ACTION_NOT_REJECT"));
    }

    @Test
    void tradeLabParsesV6CounterActionWithoutLegacyMismatchShim() throws Exception {
        String trade = source("scripts/butler-trade-lab.ps1");

        assertTrue(trade.contains("$v6Action = [regex]::Match"));
        assertTrue(trade.contains("V6 team action:"));
        assertTrue(trade.contains("V6Action = $v6Action.Groups['value'].Value.Trim()"));
        assertTrue(trade.contains(
            "trade counter-proposal $LeagueId $($roster.Season) $sideA $sideB side-a"));
        assertTrue(trade.contains(
            "counter proposal perspective does not match the exact bound user team"));

        assertFalse(trade.contains("$counterProposal.V5Action"));
        assertFalse(trade.contains("legacy v5 counter engine"));
        assertFalse(trade.contains("governed counter proposal v5 action"));
    }

    @Test
    void bf905KeepsCounterofferReadOnly() throws Exception {
        String trade = source("scripts/butler-trade-lab.ps1");
        String counter = source(
            "bet/bet-cli/src/main/java/io/butler/bet/cli/ButlerTradeCounterProposalCli.java");

        assertTrue(counter.contains("Read-only governed COUNTER"));
        assertTrue(counter.contains("Butler does not submit, send, or mutate the trade"));
        assertTrue(trade.contains("Read-only. Butler will not send or submit anything."));

        assertFalse(trade.contains("Method = \"POST\""));
        assertFalse(trade.contains("Invoke-RestMethod"));
        assertFalse(trade.contains("Invoke-WebRequest"));
        assertFalse(trade.contains("https://api.sleeper.app"));
    }

    private static String source(String relativePath) throws IOException {
        Path current = Path.of(System.getProperty("user.dir")).toAbsolutePath().normalize();
        for (int depth = 0; depth < 7 && current != null; depth++) {
            Path candidate = current.resolve(relativePath);
            if (Files.isRegularFile(candidate)) {
                return Files.readString(candidate, StandardCharsets.UTF_8)
                    .replace("\r\n", "\n");
            }
            current = current.getParent();
        }
        throw new IOException("BF-905 test could not locate " + relativePath);
    }
}
