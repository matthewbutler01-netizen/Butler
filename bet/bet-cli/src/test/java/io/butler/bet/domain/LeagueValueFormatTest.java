package io.butler.bet.domain;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class LeagueValueFormatTest {
    @Test
    void detectsSleeperSuperflexAndTwoQuarterbackFormats() {
        assertEquals(LeagueValueFormat.TWO_QB,
            LeagueValueFormat.fromRosterPositions(List.of("QB", "RB", "WR", "SUPER_FLEX")));
        assertEquals(LeagueValueFormat.TWO_QB,
            LeagueValueFormat.fromRosterPositions(List.of("QB", "QB", "RB", "WR", "FLEX")));
    }

    @Test
    void detectsOneQuarterbackAndUnknownFormats() {
        assertEquals(LeagueValueFormat.ONE_QB,
            LeagueValueFormat.fromRosterPositions(List.of("QB", "RB", "WR", "FLEX")));
        assertEquals(LeagueValueFormat.UNKNOWN, LeagueValueFormat.fromRosterPositions(List.of()));
    }
}
