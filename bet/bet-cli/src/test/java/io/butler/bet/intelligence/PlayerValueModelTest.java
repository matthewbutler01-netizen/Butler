package io.butler.bet.intelligence;

import io.butler.bet.domain.Player;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PlayerValueModelTest {
    private final PlayerValueModel model = new PlayerValueModel();

    @Test
    void superflexBaselineValuesQuarterbackHighest() {
        double qb = value("QB");
        double wr = value("WR");
        double rb = value("RB");
        double te = value("TE");

        assertTrue(qb > wr);
        assertTrue(wr > rb);
        assertTrue(rb > te);
    }

    @Test
    void normalizesPositionCaseAndWhitespace() {
        PlayerValueModel.PlayerValue value = model.value(player("  wr  "));
        assertEquals("WR", value.position());
        assertEquals(90.0, value.score());
    }

    @Test
    void defenseAliasesUseLowBaseline() {
        assertEquals(20.0, value("DEF"));
        assertEquals(20.0, value("DST"));
    }

    @Test
    void unknownPositionGetsReplacementLevelValue() {
        assertEquals(35.0, value("UNKNOWN"));
    }

    @Test
    void rejectsNullPlayer() {
        assertThrows(NullPointerException.class, () -> model.value(null));
    }

    private double value(String position) {
        return model.value(player(position)).score();
    }

    private static Player player(String position) {
        return new Player("p1", "ext-p1", "Test Player", position, "NFL");
    }
}
