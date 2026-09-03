package io.butler.bet.intelligence;

import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TradeSupportingEvidenceAnalyzerTest {
    @Test
    void attachesFlagsAndFairnessWithoutChangingTradeValueSemantics() {
        var sideAPlayer = new TradeValueAnalyzer.TradePlayer(
            "p1", "Player One", "WR", "CHI", "t1", "Alpha", 102.5, LocalDate.of(2026, 9, 1));
        var sideBPlayer = new TradeValueAnalyzer.TradePlayer(
            "p2", "Player Two", "WR", "MIN", "t2", "Beta", 97.5, LocalDate.of(2026, 9, 1));
        var sideA = new TradeValueAnalyzer.TradeSide(List.of(sideAPlayer), 102.5, 1, 0);
        var sideB = new TradeValueAnalyzer.TradeSide(List.of(sideBPlayer), 97.5, 1, 0);
        var trade = new TradeValueAnalyzer.TradeReport("l1", "dynastyprocess-1qb", sideA, sideB);

        var flag = new DecisionSupportingEvidenceFlag(
            "p1", "AGE_OUTLOOK", "RECEIVING_YARDS_PER_GAME",
            DecisionSupportingEvidenceFlag.Signal.UNFAVORABLE,
            "Validated historical aging evidence is unfavorable for RECEIVING_YARDS_PER_GAME.",
            "aging-outlook-v1-iqr-direction", "profiles+production");
        var supportingPlayer = new LeagueAgeOutlookSupportingEvidenceAnalyzer.PlayerSupportingEvidence(
            "t1", "Alpha", "p1", "Player One", "WR", 38, List.of(flag));
        var supporting = new LeagueAgeOutlookSupportingEvidenceAnalyzer.SupportingEvidenceReport(
            "l1", 2026, LocalDate.of(2026, 9, 1), "aging-support-v1-min-transitions-5",
            "aging-outlook-v1-iqr-direction", "profiles", "production", List.of(supportingPlayer));

        var report = TradeSupportingEvidenceAnalyzer.compose(trade, supporting);

        assertTrue(report.complete());
        assertEquals(5.0, report.valueDifference());
        assertEquals(102.5, report.sideA().value().totalValue());
        assertEquals(97.5, report.sideB().value().totalValue());
        assertEquals(TradeFairnessMeasurementPolicy.POLICY_ID, report.fairnessMeasurementPolicyId());
        assertEquals(TradeFairnessPolicy.POLICY_ID, report.fairnessPolicyId());
        assertEquals(5.0, report.fairnessGapPercent());
        assertEquals(TradeFairnessPolicy.Classification.MARKET_FAIR, report.fairnessClassification());
        assertEquals(1, report.supportingFlags());
        assertEquals(1, report.directionalSupportingFlags());
        assertEquals(1, report.sideA().players().getFirst().unfavorableFlags());
        assertEquals(0, report.sideB().players().getFirst().supportingFlags().size());
    }

    @Test
    void supportingFlagsDoNotChangeOutsideBandClassification() {
        var sideAPlayer = new TradeValueAnalyzer.TradePlayer(
            "p1", "Player One", "WR", "CHI", "t1", "Alpha", 110.0, LocalDate.of(2026, 9, 1));
        var sideBPlayer = new TradeValueAnalyzer.TradePlayer(
            "p2", "Player Two", "WR", "MIN", "t2", "Beta", 90.0, LocalDate.of(2026, 9, 1));
        var trade = new TradeValueAnalyzer.TradeReport(
            "l1", "dynastyprocess-1qb",
            new TradeValueAnalyzer.TradeSide(List.of(sideAPlayer), 110.0, 1, 0),
            new TradeValueAnalyzer.TradeSide(List.of(sideBPlayer), 90.0, 1, 0));
        var favorable = new DecisionSupportingEvidenceFlag(
            "p2", "AGE_OUTLOOK", "RECEIVING_YARDS_PER_GAME",
            DecisionSupportingEvidenceFlag.Signal.FAVORABLE, "summary", "outlook", "profiles+production");
        var supporting = new LeagueAgeOutlookSupportingEvidenceAnalyzer.SupportingEvidenceReport(
            "l1", 2026, LocalDate.of(2026, 9, 1), "support", "outlook", "profiles", "production",
            List.of(new LeagueAgeOutlookSupportingEvidenceAnalyzer.PlayerSupportingEvidence(
                "t2", "Beta", "p2", "Player Two", "WR", 28, List.of(favorable))));

        var report = TradeSupportingEvidenceAnalyzer.compose(trade, supporting);

        assertEquals(20.0, report.fairnessGapPercent());
        assertEquals(TradeFairnessPolicy.Classification.OUTSIDE_FAIRNESS_BAND, report.fairnessClassification());
        assertEquals(1, report.supportingFlags());
    }

    @Test
    void missingMarketValueMakesFairnessUnavailableEvenWhenFlagsExist() {
        var sideAPlayer = new TradeValueAnalyzer.TradePlayer(
            "p1", "Player One", "RB", "CHI", "t1", "Alpha", 100.0, LocalDate.of(2026, 9, 1));
        var sideBPlayer = new TradeValueAnalyzer.TradePlayer(
            "p2", "Player Two", "RB", "MIN", "t2", "Beta", null, null);
        var sideA = new TradeValueAnalyzer.TradeSide(List.of(sideAPlayer), 100.0, 1, 0);
        var sideB = new TradeValueAnalyzer.TradeSide(List.of(sideBPlayer), 0.0, 0, 1);
        var trade = new TradeValueAnalyzer.TradeReport("l1", "dynastyprocess-1qb", sideA, sideB);
        var flag = new DecisionSupportingEvidenceFlag(
            "p2", "AGE_OUTLOOK", "RUSHING_YARDS_PER_GAME",
            DecisionSupportingEvidenceFlag.Signal.INCONCLUSIVE,
            "Validated historical aging evidence is inconclusive for RUSHING_YARDS_PER_GAME.",
            "aging-outlook-v1-iqr-direction", "profiles+production");
        var supporting = new LeagueAgeOutlookSupportingEvidenceAnalyzer.SupportingEvidenceReport(
            "l1", 2026, LocalDate.of(2026, 9, 1), "aging-support-v1-min-transitions-5",
            "aging-outlook-v1-iqr-direction", "profiles", "production",
            List.of(new LeagueAgeOutlookSupportingEvidenceAnalyzer.PlayerSupportingEvidence(
                "t2", "Beta", "p2", "Player Two", "RB", 28, List.of(flag))));

        var report = TradeSupportingEvidenceAnalyzer.compose(trade, supporting);

        assertFalse(report.complete());
        assertNull(report.valueDifference());
        assertNull(report.fairnessGapPercent());
        assertEquals(TradeFairnessPolicy.Classification.UNAVAILABLE, report.fairnessClassification());
        assertEquals(1, report.supportingFlags());
    }

    @Test
    void rejectsLeagueDriftAndFlagSubjectDrift() {
        var sideAPlayer = new TradeValueAnalyzer.TradePlayer(
            "p1", "Player One", "QB", "CHI", "t1", "Alpha", 100.0, LocalDate.of(2026, 9, 1));
        var sideBPlayer = new TradeValueAnalyzer.TradePlayer(
            "p2", "Player Two", "WR", "MIN", "t2", "Beta", 90.0, LocalDate.of(2026, 9, 1));
        var sideA = new TradeValueAnalyzer.TradeSide(List.of(sideAPlayer), 100.0, 1, 0);
        var sideB = new TradeValueAnalyzer.TradeSide(List.of(sideBPlayer), 90.0, 1, 0);
        var trade = new TradeValueAnalyzer.TradeReport("l1", "dynastyprocess-1qb", sideA, sideB);
        var supporting = new LeagueAgeOutlookSupportingEvidenceAnalyzer.SupportingEvidenceReport(
            "other", 2026, LocalDate.of(2026, 9, 1), "support", "outlook", "profiles", "production", List.of());

        assertThrows(IllegalStateException.class, () -> TradeSupportingEvidenceAnalyzer.compose(trade, supporting));

        var wrongFlag = new DecisionSupportingEvidenceFlag(
            "other-player", "AGE_OUTLOOK", "PASSING_YARDS_PER_GAME",
            DecisionSupportingEvidenceFlag.Signal.INCONCLUSIVE, "summary", "policy", "source");
        assertThrows(IllegalArgumentException.class, () -> new TradeSupportingEvidenceAnalyzer.TradePlayerEvidence(
            sideAPlayer, List.of(wrongFlag)));
    }
}
