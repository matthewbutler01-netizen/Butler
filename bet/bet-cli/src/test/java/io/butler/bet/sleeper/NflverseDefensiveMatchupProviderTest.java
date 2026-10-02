package io.butler.bet.sleeper;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import java.time.Instant;
import java.util.List;

class NflverseDefensiveMatchupProviderTest {
    private static final String SCHEDULE = "game_id,season,game_type,week,home_team,away_team,home_score,away_score\n"
        + "g1,2026,REG,3,DEN,NYJ,21,14\n" + "g2,2026,REG,4,KC,DEN,,\n";
    private static final String HEADER = "player_id,season,season_type,game_id,team,opponent_team,position,passing_yards,rushing_yards,receiving_yards,passing_tds,rushing_tds,receiving_tds\n";
    private static final String STATS = HEADER + "qb,2026,REG,g1,NYJ,DEN,QB,250,10,0,2,0,0\n"
        + "wr1,2026,REG,g1,NYJ,DEN,WR,0,0,80,0,0,1\n"
        + "wr2,2026,REG,g1,NYJ,DEN,WR,0,5,20,0,0,0\n";
    private static final List<SleeperLiveWaiverTargetRosterContextAudit.TargetPlayer> PLAYERS = List.of(
        new SleeperLiveWaiverTargetRosterContextAudit.TargetPlayer("1", "BENCH", null, null, "p", "Player", "WR", "KC", "EXACT"));
    private String parse(String schedule, String stats) {
        return NflverseDefensiveMatchupProvider.parse(schedule, stats, 2026, 4, PLAYERS, Instant.EPOCH).get("1");
    }
    @Test void aggregatesOnlyScheduledOpponentAndPosition() {
        String text = parse(SCHEDULE, STATS);
        assertTrue(text.contains("week 4 vs DEN"));
        assertTrue(text.contains("1 completed games"));
        assertTrue(text.contains("0 passing yards, 5 rushing yards, 100 receiving yards, 1 offensive TDs"));
        assertTrue(text.contains("Saved roster team KC"));
    }
    @Test void missingStatsAreNotZero() {
        assertTrue(parse(SCHEDULE, HEADER).contains("coverage incomplete"));
        assertFalse(parse(SCHEDULE, HEADER).contains("0 receiving yards"));
    }
    @Test void duplicatePlayerGameFailsClosed() {
        assertThrows(IllegalStateException.class, () -> parse(SCHEDULE, STATS + "wr1,2026,REG,g1,NYJ,DEN,WR,0,0,80,0,0,1\n"));
    }
    @Test void byeOrAmbiguousScheduleIsUnverified() {
        assertTrue(parse(SCHEDULE.replace("4,KC,DEN", "5,KC,DEN"), STATS).contains("no unique"));
        assertTrue(parse(SCHEDULE + "g3,2026,REG,4,KC,BUF,,\n", STATS).contains("no unique"));
    }
    @Test void unplayedAndFutureGamesDoNotEnterSample() {
        assertTrue(parse(SCHEDULE.replace("21,14", ","), STATS).contains("coverage incomplete"));
    }
    @Test void missingStatFailsClosed() {
        assertThrows(IllegalStateException.class, () -> parse(SCHEDULE, STATS.replace("0,0,80", "0,0,")));
    }
}
