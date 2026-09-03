package io.butler.bet.cli;

import io.butler.bet.domain.TeamSeasonPerformance;
import io.butler.bet.intelligence.LeaguePerformanceEvidenceAnalyzer;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertTrue;

class ButlerLeaguePerformanceEvidenceCliOutputTest {
    @Test
    void printsGovernedTierPolicyWithoutStrategicPosture() {
        List<LeaguePerformanceEvidenceAnalyzer.TeamPerformanceEvidence> teams = new ArrayList<>();
        for (int i = 0; i < 4; i++) {
            var performance = new TeamSeasonPerformance("l1", "t" + i, 2026,
                4 - i, i, 0, 500 - i * 50, 350 + i * 25, "sleeper", LocalDate.of(2026, 9, 3));
            teams.add(new LeaguePerformanceEvidenceAnalyzer.TeamPerformanceEvidence("t" + i, "Team " + i, performance));
        }
        var report = new LeaguePerformanceEvidenceAnalyzer.PerformanceReport("l1", 2026, "sleeper", teams);
        var previous = System.out;
        var buffer = new ByteArrayOutputStream();
        try {
            System.setOut(new PrintStream(buffer));
            ButlerLeaguePerformanceEvidenceCli.print(report);
        } finally {
            System.setOut(previous);
        }
        String output = buffer.toString();
        assertTrue(output.contains("Competitive-tier policy: league-competitive-tier-v1-relative-quartiles"));
        assertTrue(output.contains("tier=FRONT_TIER"));
        assertTrue(output.contains("tier=BACK_TIER"));
        assertTrue(output.contains("no contender/rebuilder posture or recommendation"));
    }
}
