package io.butler.bet.intelligence;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class TradeStrategicMaterialLossCorePositionContractTest {
    @Test
    void normalizesAndAcceptsEachCorePosition() {
        assertEquals("QB", reason("qb").position());
        assertEquals("RB", reason(" rb ").position());
        assertEquals("WR", reason("Wr").position());
        assertEquals("TE", reason("te").position());
    }

    @Test
    void rejectsNonCoreAndCompositePositions() {
        assertThrows(IllegalArgumentException.class, () -> reason("K"));
        assertThrows(IllegalArgumentException.class, () -> reason("DST"));
        assertThrows(IllegalArgumentException.class, () -> reason("FLEX"));
        assertThrows(IllegalArgumentException.class, () -> reason("SUPERFLEX"));
    }

    private static TradeStrategicMaterialLossVetoDetector.VetoReason reason(String position) {
        return new TradeStrategicMaterialLossVetoDetector.VetoReason(
            TradeStrategicMaterialLossVetoDetector.ReasonCode.POSITION_PRESSURE_MATERIAL_SAME_POSITION_VALUE_LOSS,
            position,
            100.0,
            50.0,
            0.50);
    }
}
