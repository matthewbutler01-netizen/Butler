package io.butler.bet.cli;

import io.butler.bet.intelligence.DecisionSupportingEvidenceFlag;
import io.butler.bet.intelligence.LeagueAgeContextAnalyzer;
import io.butler.bet.intelligence.LeagueAgeProductionContextAnalyzer;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ButlerPlayerDetailCliTest {

    @Test
    void parsesExactPlayerCoordinatesAndOptionalSeason() {
        var implicit = ButlerPlayerDetailCli.parse(new String[]{
            "league", "player-detail", "l1", "p1"
        });
        assertEquals("l1", implicit.leagueId());
        assertEquals("p1", implicit.playerId());
        assertNull(implicit.season());

        var explicit = ButlerPlayerDetailCli.parse(new String[]{
            "league", "player-detail", "l1", "p1", "2026"
        });
        assertEquals(2026, explicit.season());
    }

    @Test
    void rejectsMissingExtraOrMalformedCoordinates() {
        assertThrows(IllegalArgumentException.class, () ->
            ButlerPlayerDetailCli.parse(new String[]{"league", "player-detail", "l1"}));
        assertThrows(IllegalArgumentException.class, () ->
            ButlerPlayerDetailCli.parse(new String[]{"league", "player-detail", "l1", "p1", "2026", "extra"}));
        assertThrows(IllegalArgumentException.class, () ->
            ButlerPlayerDetailCli.parse(new String[]{"league", "player-detail", "l1", "p1", "bad"}));
    }

    @Test
    void printsNeutralPlayerEvidenceAndSupportingFlagsWithoutScore() {
        var player = new LeagueAgeProductionContextAnalyzer.PlayerAgeProductionContext(
            "p1", "Player One", "WR", "STARTER",
            26, LeagueAgeContextAnalyzer.AgeProvenance.EXACT_BIRTH_DATE,
            true, 10,
            null, null, null,
            2.5, 0.2, 5.0, 71.4, 0.6, 0.1);
        var flag = new DecisionSupportingEvidenceFlag(
            "p1", "AGE_OUTLOOK", "RECEIVING_YARDS",
            DecisionSupportingEvidenceFlag.Signal.FAVORABLE,
            "Validated historical aging evidence is favorable for RECEIVING_YARDS.",
            "policy-1", "profiles+production");
        var report = report(player, 26, List.of(flag));

        String output = capture(report);

        assertTrue(output.contains("Player detail (read-only neutral evidence)"));
        assertTrue(output.contains("Team: Alpha [t1]"));
        assertTrue(output.contains("Player: Player One [p1]"));
        assertTrue(output.contains("Position: WR"));
        assertTrue(output.contains("Roster slot: STARTER"));
        assertTrue(output.contains("Age: 26"));
        assertTrue(output.contains("Age provenance: EXACT_BIRTH_DATE"));
        assertTrue(output.contains("Production snapshot: AVAILABLE"));
        assertTrue(output.contains("Games played: 10"));
        assertTrue(output.contains("Receiving yards/game: 71.40"));
        assertTrue(output.contains("Supporting flags: 1"));
        assertTrue(output.contains("signal=FAVORABLE"));
        assertTrue(output.contains("no blended score, grade, rank, dynasty adjustment, or recommendation is produced"));
        assertFalse(output.contains("Player score:"));
        assertFalse(output.contains("Grade:"));
    }

    @Test
    void zeroGameSnapshotRemainsAvailableWithoutInventedRates() {
        var player = new LeagueAgeProductionContextAnalyzer.PlayerAgeProductionContext(
            "p1", "Player One", "WR", "BENCH",
            26, LeagueAgeContextAnalyzer.AgeProvenance.EXACT_BIRTH_DATE,
            true, 0,
            null, null, null, null, null, null, null, null, null);

        String output = capture(report(player, null, List.of()));

        assertTrue(output.contains("Production snapshot: AVAILABLE"));
        assertTrue(output.contains("Games played: 0"));
        assertTrue(output.contains("Receiving yards/game: -"));
        assertFalse(output.contains("Receiving yards/game: 0.00"));
    }

    @Test
    void missingProductionRemainsMissingWithoutBecomingZeroGameEvidence() {
        var player = new LeagueAgeProductionContextAnalyzer.PlayerAgeProductionContext(
            "p1", "Player One", "WR", "BENCH",
            null, LeagueAgeContextAnalyzer.AgeProvenance.MISSING,
            false, 0,
            null, null, null, null, null, null, null, null, null);

        String output = capture(report(player, null, List.of()));

        assertTrue(output.contains("Age: -"));
        assertTrue(output.contains("Production snapshot: MISSING"));
        assertTrue(output.contains("Games played: 0"));
        assertTrue(output.contains("Receiving yards/game: -"));
        assertFalse(output.contains("Receiving yards/game: 0.00"));
    }

    private static ButlerPlayerDetailCli.PlayerDetailReport report(
        LeagueAgeProductionContextAnalyzer.PlayerAgeProductionContext player,
        Integer modelAge,
        List<DecisionSupportingEvidenceFlag> flags) {
        return new ButlerPlayerDetailCli.PlayerDetailReport(
            "l1", 2026, "t1", "Alpha", player,
            LocalDate.of(2026, 9, 20), "sleeper", null, "nflverse",
            LocalDate.of(2026, 9, 1), modelAge,
            "support-policy", "outlook-policy",
            "model-profile", "model-production", flags);
    }

    private static String capture(ButlerPlayerDetailCli.PlayerDetailReport report) {
        PrintStream original = System.out;
        var bytes = new ByteArrayOutputStream();
        try {
            System.setOut(new PrintStream(bytes));
            ButlerPlayerDetailCli.print(report);
        } finally {
            System.setOut(original);
        }
        return bytes.toString();
    }
}
