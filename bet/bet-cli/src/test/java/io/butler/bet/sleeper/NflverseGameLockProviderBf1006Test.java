package io.butler.bet.sleeper;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class NflverseGameLockProviderBf1006Test {
    private static final String SCHEDULE =
        "game_id,season,game_type,week,gameday,gametime,home_team,away_team,home_score,away_score\n"
            + "thu,2026,REG,4,2026-10-01,20:15,CLE,PIT,24,17\n"
            + "sun,2026,REG,4,2026-10-04,13:00,KC,LV,,\n";

    private static SleeperLiveWaiverTargetRosterContextAudit.TargetPlayer player(String id, String team) {
        return new SleeperLiveWaiverTargetRosterContextAudit.TargetPlayer(
            id, "BENCH", null, null, "butler-" + id, "Player " + id, "WR", team, "EXACT_CANONICAL");
    }

    @Test
    void thursdayPlayerIsLockedBySaturdayWhileSundayPlayerIsNot() {
        var locks = NflverseGameLockProvider.parse(
            SCHEDULE,
            2026,
            4,
            List.of(player("cle", "CLE"), player("kc", "KC")),
            Instant.parse("2026-10-03T07:00:00Z"));

        assertTrue(locks.get("cle").verified());
        assertTrue(locks.get("cle").locked());
        assertTrue(locks.get("cle").detail().contains("America/New_York"));

        assertTrue(locks.get("kc").verified());
        assertFalse(locks.get("kc").locked());
    }

    @Test
    void playerLocksAtExactKickoff() {
        var locks = NflverseGameLockProvider.parse(
            SCHEDULE,
            2026,
            4,
            List.of(player("cle", "CLE")),
            Instant.parse("2026-10-02T00:15:00Z"));

        assertTrue(locks.get("cle").locked());
    }

    @Test
    void missingOrAmbiguousScheduleFailsClosedAsUnverifiedEvidence() {
        String ambiguous = SCHEDULE + "sun2,2026,REG,4,2026-10-04,16:25,KC,DEN,,\n";
        var locks = NflverseGameLockProvider.parse(
            ambiguous,
            2026,
            4,
            List.of(player("kc", "KC"), player("missing", null)),
            Instant.parse("2026-10-03T07:00:00Z"));

        assertFalse(locks.get("kc").verified());
        assertFalse(locks.get("kc").locked());
        assertFalse(locks.get("missing").verified());
        assertTrue(locks.get("kc").detail().contains("expected one"));
    }

    @Test
    void scheduleWithoutKickoffFieldsDoesNotGuess() {
        String missingTime =
            "game_id,season,game_type,week,gameday,gametime,home_team,away_team\n"
                + "g,2026,REG,4,2026-10-04,,KC,LV\n";
        var locks = NflverseGameLockProvider.parse(
            missingTime,
            2026,
            4,
            List.of(player("kc", "KC")),
            Instant.parse("2026-10-03T07:00:00Z"));

        assertFalse(locks.get("kc").verified());
        assertTrue(locks.get("kc").detail().contains("missing gameday/gametime"));
    }
}
