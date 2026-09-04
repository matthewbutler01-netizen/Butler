package io.butler.bet.intelligence;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class TradeStrategicMaterialLossVetoReasonIntegrityTest {
    @Test
    void acceptsConsistentMaterialLossEvidence() {
        var reason = new TradeStrategicMaterialLossVetoDetector.VetoReason(
            TradeStrategicMaterialLossVetoDetector.ReasonCode.POSITION_PRESSURE_MATERIAL_SAME_POSITION_VALUE_LOSS,
            "wr",
            100.0,
            74.0,
            0.26);

        assertEquals("WR", reason.position());
        assertEquals(0.26, reason.lossFraction(), 0.000000000001);
    }

    @Test
    void rejectsLossFractionThatDoesNotMatchProtectedValues() {
        assertThrows(IllegalArgumentException.class, () ->
            new TradeStrategicMaterialLossVetoDetector.VetoReason(
                TradeStrategicMaterialLossVetoDetector.ReasonCode.LOW_FUTURE_CAPITAL_MATERIAL_PICK_VALUE_LOSS,
                null,
                100.0,
                74.0,
                0.10));
    }

    @Test
    void rejectsNonMaterialEvidenceAtExactThreshold() {
        assertThrows(IllegalArgumentException.class, () ->
            new TradeStrategicMaterialLossVetoDetector.VetoReason(
                TradeStrategicMaterialLossVetoDetector.ReasonCode.LOW_FUTURE_CAPITAL_MATERIAL_PICK_VALUE_LOSS,
                null,
                100.0,
                75.0,
                0.25));
    }

    @Test
    void rejectsNoLossEvidence() {
        assertThrows(IllegalArgumentException.class, () ->
            new TradeStrategicMaterialLossVetoDetector.VetoReason(
                TradeStrategicMaterialLossVetoDetector.ReasonCode.POSITION_PRESSURE_MATERIAL_SAME_POSITION_VALUE_LOSS,
                "QB",
                100.0,
                125.0,
                0.0));
    }
}
