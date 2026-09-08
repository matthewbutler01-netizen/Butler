package io.butler.bet.cli;

import io.butler.bet.data.LiveWaiverAvailabilityRepository;
import io.butler.bet.data.LiveWaiverCurrentWeekStatRepository;
import io.butler.bet.sleeper.SleeperLiveWaiverPregameEvidenceDossier;
import io.butler.bet.sleeper.SleeperLiveWaiverProductionCoverageAudit;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertTrue;

class ButlerSleeperLiveWaiverPregameEvidenceDossierCliTest {
    @Test
    void rendersPregameEvidenceWithoutRecommendationSemantics() {
        var market = new SleeperLiveWaiverProductionCoverageAudit.MarketCandidate(
            "p1", "Example", "WR", "CHI", "Active", 20, 3, 17, "BOTH");
        var production = new SleeperLiveWaiverProductionCoverageAudit.ProductionObservation(
            "nflverse", LocalDate.parse("2026-09-01"), 17, 0, 0, 25, 1, 60, 800, 6);
        var availability = new LiveWaiverAvailabilityRepository.Entry(
            "p1", "Example", "WR", 20, 3, 17, "BOTH", "SOURCE_PRESENT",
            "CHI", "Active", "Questionable", "2026-09-06", null, "WR", 2);
        var week = new LiveWaiverCurrentWeekStatRepository.Entry(
            "p1", "Example", "WR", 20, 3, 17, "BOTH", "SOURCE_ABSENT",
            null,
            null, null, null, null, null,
            null, null, null,
            null, null, null, null, null);
        var candidate = new SleeperLiveWaiverPregameEvidenceDossier.CandidateDossier(
            market, "b1", List.of(production), availability, week,
            "CURRENT_TEAM_KNOWN", "PROVIDER_STATUS_KNOWN", "INJURY_FLAG_PRESENT",
            "DEPTH_EVIDENCE_PRESENT", "PRIOR_SEASON_PRODUCTION_PRESENT", "CURRENT_WEEK_UNOBSERVED");
        var report = new SleeperLiveWaiverPregameEvidenceDossier.DossierReport(
            SleeperLiveWaiverPregameEvidenceDossier.POLICY_ID,
            "L", "M", "2026-09-08T01:00:00Z", "A", Instant.parse("2026-09-08T02:00:00Z"),
            "C", Instant.parse("2026-09-08T03:00:00Z"), "CURRENT_WEEK_FINALITY_UNPROVEN",
            2026, 1, "regular", 1, 1, 1, 1, 1, 1, 0, List.of(candidate));

        PrintStream original = System.out;
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try {
            System.setOut(new PrintStream(bytes));
            ButlerSleeperLiveWaiverPregameEvidenceDossierCli.print(report);
        } finally {
            System.setOut(original);
        }

        String output = bytes.toString();
        assertTrue(output.contains("Pregame dossier state: READY_EVIDENCE_ONLY"));
        assertTrue(output.contains("CURRENT_WEEK_UNOBSERVED"));
        assertTrue(output.contains("UNOBSERVED (no zero/DNP/finality inference)"));
        assertTrue(output.contains("2025 production nflverse@2026-09-01"));
        assertTrue(output.contains("not a player ranking"));
        assertTrue(output.contains("No waiver ranking"));
        assertTrue(output.contains("No waiver ranking, FAAB guidance, add/drop recommendation"));
    }
}
