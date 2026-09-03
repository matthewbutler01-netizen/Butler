package io.butler.bet.cli;

import org.junit.jupiter.api.Test;

import java.time.LocalDate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ButlerLeagueFutureCapitalCliTest {
    @Test
    void parsesDefaultAndExplicitSourceForms() {
        var basic = ButlerLeagueFutureCapitalCli.parse(new String[]{"league", "future-capital", "l1"});
        assertEquals("l1", basic.leagueId());
        assertNull(basic.source());
        assertNull(basic.minimumAsOfDate());

        var sourced = ButlerLeagueFutureCapitalCli.parse(
            new String[]{"league", "future-capital", "l1", "dynastyprocess"});
        assertEquals("dynastyprocess", sourced.source());
    }

    @Test
    void parsesMinimumAsOfWithAndWithoutSource() {
        var unsourced = ButlerLeagueFutureCapitalCli.parse(
            new String[]{"league", "future-capital", "l1", "--minimum-as-of", "2026-09-01"});
        assertEquals(LocalDate.of(2026, 9, 1), unsourced.minimumAsOfDate());
        assertNull(unsourced.source());

        var sourced = ButlerLeagueFutureCapitalCli.parse(
            new String[]{"league", "future-capital", "l1", "dynastyprocess", "--minimum-as-of", "2026-09-01"});
        assertEquals("dynastyprocess", sourced.source());
        assertEquals(LocalDate.of(2026, 9, 1), sourced.minimumAsOfDate());
    }

    @Test
    void rejectsMalformedArguments() {
        assertThrows(IllegalArgumentException.class, () -> ButlerLeagueFutureCapitalCli.parse(
            new String[]{"league", "future-capital", "l1", "--minimum-as-of", "bad"}));
        assertThrows(IllegalArgumentException.class, () -> ButlerLeagueFutureCapitalCli.parse(
            new String[]{"league", "future-capital"}));
        assertThrows(IllegalArgumentException.class, () -> ButlerLeagueFutureCapitalCli.parse(
            new String[]{"league", "future-capital", "l1", "source", "extra"}));
    }

    @Test
    void recognizesOnlyFutureCapitalCommand() {
        assertTrue(ButlerLeagueFutureCapitalCli.isCommand(new String[]{"league", "future-capital", "l1"}));
    }
}
