package io.butler.bet.cli;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ButlerTradeHubBf910Test {

    @Test
    void evaluatedTradeBecomesDecisionFirst() throws Exception {
        String trade = source("scripts/butler-trade-lab.ps1");

        assertTrue(trade.contains("Deal snapshot"));
        assertTrue(trade.contains("$dealSnapshotHtml"));
        assertTrue(trade.contains("$builderPresentation"));
        assertTrue(trade.contains("<details class=\"edit-deal\"><summary>Edit this deal</summary>$builder</details>"));

        String order = "$dealSnapshotHtml\n$resultHtml\n$counterHtml\n$builderPresentation";
        assertTrue(trade.contains(order),
            "evaluated Trade Hub must render deal snapshot -> decision -> counter -> edit controls");
    }

    @Test
    void compactDealSnapshotUsesAlreadyValidatedCurrentAssets() throws Exception {
        String trade = source("scripts/butler-trade-lab.ps1");

        assertTrue(trade.contains("function ConvertTo-TradeSelectionDisplay"));
        assertTrue(trade.contains("[string]$_.Token -ceq $token"));
        assertTrue(trade.contains("evaluated trade asset $token did not resolve exactly once"));
        assertTrue(trade.contains("You give"));
        assertTrue(trade.contains("You receive"));
        assertTrue(trade.contains("Butler evaluated these exact currently owned assets"));
    }

    @Test
    void plainLanguageReasonIsVisibleBeforeTechnicalEvidence() throws Exception {
        String trade = source("scripts/butler-trade-lab.ps1");

        assertTrue(trade.contains("decision-reason"));
        assertTrue(trade.contains("<strong>Why</strong>"));
        assertTrue(trade.contains("Why Butler says this"));
        assertTrue(trade.contains("Raw decision record"));

        int reason = trade.indexOf("$reasonHtml = if");
        int proof = trade.indexOf("Why Butler says this", reason);
        int raw = trade.indexOf("Raw decision record", proof);
        assertTrue(reason >= 0 && proof > reason && raw > proof);
    }

    @Test
    void counterEligibilityAndV6ContractsRemainUnchanged() throws Exception {
        String trade = source("scripts/butler-trade-lab.ps1");

        assertTrue(trade.contains("$Evaluation.EvidenceComplete -and $Evaluation.Action -ceq 'REJECT'"));
        assertTrue(trade.contains("Build Counteroffer"));
        assertTrue(trade.contains("counter proposal is available only for an evidence-complete REJECT"));
        assertTrue(trade.contains("trade recommendation $LeagueId"));
        assertTrue(trade.contains("trade counter-proposal $LeagueId"));
        assertTrue(trade.contains("V6 team action:"));
    }

    @Test
    void tradeHubRemainsGetOnlyAndDoesNotAddAWritePath() throws Exception {
        String trade = source("scripts/butler-trade-lab.ps1");

        assertFalse(trade.contains("Method = \"POST\""));
        assertFalse(trade.contains("Invoke-RestMethod"));
        assertFalse(trade.contains("Invoke-WebRequest"));
        assertFalse(trade.contains("https://api.sleeper.app"));
        assertFalse(trade.contains("counter-handoff"));
        assertFalse(trade.contains("counter-authorization"));

        assertTrue(trade.contains("READ ONLY"));
        assertTrue(trade.contains("It cannot refresh evidence, authorize, hand off, send, finalize, or submit a counter"));
    }

    @Test
    void evaluatedTradeHubStillStacksForMobile() throws Exception {
        String trade = source("scripts/butler-trade-lab.ps1");

        assertTrue(trade.contains(".deal-snapshot{display:grid;grid-template-columns:repeat(2,minmax(0,1fr))"));
        assertTrue(trade.contains(".trade-setup,.trade-columns,.counter-packages,.deal-snapshot{grid-template-columns:1fr}"));
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
        throw new IOException("BF-910 test could not locate " + relativePath);
    }
}
