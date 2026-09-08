package io.butler.bet.cli;

import io.butler.bet.data.LiveWaiverAvailabilityRepository;
import io.butler.bet.data.LiveWaiverCurrentWeekStatRepository;
import io.butler.bet.sleeper.SleeperLiveWaiverPregameEvidenceDossier;
import io.butler.bet.sleeper.SleeperLiveWaiverPregameEvidenceReadinessAudit;
import io.butler.bet.sleeper.SleeperLiveWaiverProductionCoverageAudit;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertTrue;

class ButlerSleeperLiveWaiverPregameEvidenceReadinessAuditCliTest {
    @Test
    void rendersEvidenceReadinessWithoutValueOrRecommendationSemantics() {
        var market = new SleeperLiveWaiverProductionCoverageAudit.MarketCandidate(
            "p1", "Newcomer", "WR", "CHI", "Active", 20, 0, 20, "ADD_ONLY");
        var availability = new LiveWaiverAvailabilityRepository.Entry(
            "p1", "Newcomer", "WR", 20, 0, 20, "ADD_ONLY", "SOURCE_PRESENT",
            "CHI", "Active", null, null, null, "WR", 2);
        var week = new LiveWaiverCurrentWeekStatRepository.Entry(
            "p1", "Newcomer", "WR", 20, 0, 20, "ADD_ONLY", "SOURCE_ABSENT", null,
            null, null, null, null, null, null, null, null, null, null, null, null, null);
        var candidate = new SleeperLiveWaiverPregameEvidenceDossier.CandidateDossier(
            market, "b1", List.of(), availability, week,
            "CURRENT_TEAM_KNOWN", "PROVIDER_STATUS_KNOWN", "INJURY_FLAG_ABSENT_OR_UNKNOWN",
            "DEPTH_EVIDENCE_PRESENT", "PRIOR_SEASON_PRODUCTION_MISSING", "CURRENT_WEEK_UNOBSERVED");
        var dossier = new SleeperLiveWaiverPregameEvidenceDossier.DossierReport(
            SleeperLiveWaiverPregameEvidenceDossier.POLICY_ID,
            "L", "M", "2026-09-08T01:00:00Z", "A", Instant.parse("2026-09-08T02:00:00Z"),
            "C", Instant.parse("2026-09-08T03:00:00Z"), "CURRENT_WEEK_FINALITY_UNPROVEN",
            2026, 1, "regular", 1, 1, 1, 0, 1, 0, 0, List.of(candidate));
        var report = SleeperLiveWaiverPregameEvidenceReadinessAudit.classify(dossier);

        PrintStream original = System.out;
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try {
            System.setOut(new PrintStream(bytes));
            ButlerSleeperLiveWaiverPregameEvidenceReadinessAuditCli.print(report);
        } finally {
            System.setOut(original);
        }

        String output = bytes.toString();
        assertTrue(output.contains("REVIEWABLE_WITHOUT_PRIOR_PRODUCTION | total=1"));
        assertTrue(output.contains("week=CURRENT_WEEK_UNOBSERVED"));
        assertTrue(output.contains("Pregame evidence-readiness state: CLASSIFIED_EVIDENCE_ONLY"));
        assertTrue(output.contains("REVIEWABLE means the pregame dossier has the named evidence lanes"));
        assertTrue(output.contains("No player score, waiver rank, shortlist winner, FAAB guidance"));
    }
}
