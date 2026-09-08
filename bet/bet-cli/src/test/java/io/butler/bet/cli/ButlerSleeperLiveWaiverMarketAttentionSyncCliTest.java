package io.butler.bet.cli;

import io.butler.bet.sleeper.SleeperLiveWaiverMarketAttentionSync;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.time.Duration;
import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertTrue;

class ButlerSleeperLiveWaiverMarketAttentionSyncCliTest {
    @Test
    void rendersVerifiedMarketEvidenceAndNonRecommendationBoundary() {
        var report = new SleeperLiveWaiverMarketAttentionSync.SyncReport(
            SleeperLiveWaiverMarketAttentionSync.POLICY_ID,
            "M", "W", Instant.parse("2026-09-08T00:30:00Z"), Duration.ofMinutes(15),
            "L", "S", 2026, "in_season", 1,
            24, 200, 4, 3, 2, 1, 1, 1, 1,
            List.of("p3 | Add Only | pos=WR | team=CHI | status=Active | add=10 | drop=0 | net=10 | frame=ADD_ONLY"),
            List.of("p5 | Drop Only | pos=RB | team=CHI | status=Active | add=0 | drop=7 | net=-7 | frame=DROP_ONLY"),
            List.of("p3 | Add Only | pos=WR | team=CHI | status=Active | add=10 | drop=0 | net=10 | frame=ADD_ONLY"),
            Instant.parse("2026-09-08T00:45:00Z"), 1);

        PrintStream original = System.out;
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try {
            System.setOut(new PrintStream(bytes));
            ButlerSleeperLiveWaiverMarketAttentionSyncCli.print(report);
        } finally {
            System.setOut(original);
        }
        String output = bytes.toString();
        assertTrue(output.contains("Market-attention state: PERSISTED_VERIFIED"));
        assertTrue(output.contains("League-eligible candidates retained: 4"));
        assertTrue(output.contains("ADD_ONLY/DROP_ONLY/BOTH/NEITHER: 1/1/1/1"));
        assertTrue(output.contains("add=10"));
        assertTrue(output.contains("descriptive evidence only"));
        assertTrue(output.contains("not player values"));
    }
}
