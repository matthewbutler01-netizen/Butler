package io.butler.bet.cli;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ButlerTradeDecisionFirstBf874Test {

    @Test
    void firstScanKeepsExactRecommendationAheadOfSupportingProof() throws Exception {
        String trade = source("scripts/butler-trade-lab.ps1");

        int resultStart = trade.indexOf("<section class=\"panel trade-result\">");
        int proofStart = trade.indexOf("<details class=\"trade-proof\"><summary>Why Butler says this</summary>", resultStart);
        int resultEnd = trade.indexOf("</section>", resultStart);

        assertTrue(resultStart >= 0, "Trade recommendation result must remain present");
        assertTrue(proofStart > resultStart, "supporting proof must follow the recommendation");
        assertTrue(resultEnd > proofStart, "Trade result section must contain the proof disclosure");

        String firstScan = trade.substring(resultStart, proofStart);
        assertTrue(firstScan.contains("$Evaluation.Action"));
        assertTrue(firstScan.contains("$Evaluation.PackageRecommendation"));
        assertTrue(firstScan.contains("$evidenceState EVIDENCE"));
        assertFalse(firstScan.contains("Strategic veto"));
        assertFalse(firstScan.contains("Flexible pressure"));
        assertFalse(firstScan.contains("Pressure transition"));
        assertFalse(firstScan.contains("Market direction"));
        assertFalse(firstScan.contains("Material-loss veto evidence"));
    }

    @Test
    void allExistingTradeProofRemainsAvailableUnderProgressiveDisclosure() throws Exception {
        String trade = source("scripts/butler-trade-lab.ps1");

        for (String marker : new String[]{
                "Why Butler says this",
                "Strategic veto",
                "Flexible pressure",
                "Pressure transition",
                "Market direction",
                "Posture",
                "Future capital",
                "Position pressure",
                "Material-loss veto evidence",
                "Raw decision record",
                "$Evaluation.Raw"
        }) {
            assertTrue(trade.contains(marker), "missing preserved Trade proof marker " + marker);
        }
    }

    @Test
    void managerCopyHidesImplementationVersionWithoutChangingInternalRoute() throws Exception {
        String trade = source("scripts/butler-trade-lab.ps1");

        assertTrue(trade.contains("trade recommendation $LeagueId"));
        assertTrue(trade.contains("Build an exact deal and review Butler's governed recommendation."));
        assertTrue(trade.contains("Butler can evaluate exact currently owned assets and, after an explicit request, build an existing governed read-only counteroffer."));

        assertFalse(trade.contains("existing governed v5 trade recommendation"));
        assertFalse(trade.contains("existing routed v5 recommendation"));
    }

    @Test
    void builderAndReadOnlyBoundariesRemainUntouched() throws Exception {
        String trade = source("scripts/butler-trade-lab.ps1");

        assertTrue(trade.contains("Choose a league opponent"));
        assertTrue(trade.contains("Build the deal"));
        assertTrue(trade.contains("Get Butler recommendation"));
        assertTrue(trade.contains("Assert-TradeAssetSelection"));
        assertTrue(trade.contains("PerspectiveTeamId"));
        assertTrue(trade.contains("READ ONLY."));

        assertFalse(trade.contains("Method = \"POST\""));
        assertFalse(trade.contains("https://api.sleeper.app"));
        assertFalse(trade.contains("submitTransaction"));
        assertFalse(trade.contains("setFaab"));
    }

    @Test
    void canonicalValidatorAndLiveAcceptanceGuardTheNewHierarchy() throws Exception {
        String validator = source("scripts/butler-app-bf831-trade-analyzer-transform.ps1");
        String acceptance = source("scripts/butler-trade-analyzer-acceptance.ps1");

        for (String marker : new String[]{
                "Why Butler says this",
                "Raw decision record",
                ".trade-proof"
        }) {
            assertTrue(validator.contains(marker), "BF-831 validator missing " + marker);
        }

        for (String marker : new String[]{
                "Package recommendation:",
                "Why Butler says this",
                "Raw decision record",
                "governed v5"
        }) {
            assertTrue(acceptance.contains(marker), "BF-839 live acceptance missing " + marker);
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
        throw new IOException("BF-874 test could not locate " + relativePath);
    }
}
