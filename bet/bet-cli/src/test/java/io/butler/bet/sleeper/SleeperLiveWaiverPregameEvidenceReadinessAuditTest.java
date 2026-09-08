package io.butler.bet.sleeper;

import io.butler.bet.data.LiveWaiverAvailabilityRepository;
import io.butler.bet.data.LiveWaiverCurrentWeekStatRepository;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SleeperLiveWaiverPregameEvidenceReadinessAuditTest {
    @Test
    void partitionsPregameCandidatesWithoutPenalizingMissingPriorProduction() {
        var veteran = candidate("p1", "Veteran", true, true, true, true, false, "ADD_ONLY", false);
        var newcomer = candidate("p2", "Newcomer", true, true, false, true, false, "BOTH", false);
        var depthMissing = candidate("p3", "Depth Gap", true, false, true, true, true, "DROP_ONLY", false);
        var teamUnknown = candidate("p4", "Team Gap", false, false, false, true, false, "ADD_ONLY", false);

        var report = SleeperLiveWaiverPregameEvidenceReadinessAudit.classify(
            dossier(List.of(veteran, newcomer, depthMissing, teamUnknown)));

        assertEquals(4, report.candidateCount());
        assertEquals(1, report.summaries().get(
            SleeperLiveWaiverPregameEvidenceReadinessAudit.PrimaryStratum.REVIEWABLE_WITH_PRIOR_PRODUCTION).total());
        assertEquals(1, report.summaries().get(
            SleeperLiveWaiverPregameEvidenceReadinessAudit.PrimaryStratum.REVIEWABLE_WITHOUT_PRIOR_PRODUCTION).total());
        assertEquals(1, report.summaries().get(
            SleeperLiveWaiverPregameEvidenceReadinessAudit.PrimaryStratum.DEPTH_EVIDENCE_MISSING).total());
        assertEquals(1, report.summaries().get(
            SleeperLiveWaiverPregameEvidenceReadinessAudit.PrimaryStratum.CURRENT_TEAM_UNKNOWN).total());

        var newcomerResult = report.candidates().stream()
            .filter(value -> value.dossier().market().sleeperPlayerId().equals("p2"))
            .findFirst().orElseThrow();
        assertEquals(
            SleeperLiveWaiverPregameEvidenceReadinessAudit.PrimaryStratum.REVIEWABLE_WITHOUT_PRIOR_PRODUCTION,
            newcomerResult.primaryStratum());
        assertEquals("CURRENT_WEEK_UNOBSERVED", newcomerResult.dossier().currentWeekEvidenceState());

        var teamUnknownResult = report.candidates().stream()
            .filter(value -> value.dossier().market().sleeperPlayerId().equals("p4"))
            .findFirst().orElseThrow();
        assertEquals(
            SleeperLiveWaiverPregameEvidenceReadinessAudit.PrimaryStratum.CURRENT_TEAM_UNKNOWN,
            teamUnknownResult.primaryStratum());

        var depthSummary = report.summaries().get(
            SleeperLiveWaiverPregameEvidenceReadinessAudit.PrimaryStratum.DEPTH_EVIDENCE_MISSING);
        assertEquals(1, depthSummary.injuryFlagPresent());
        assertEquals(1, depthSummary.dropOnly());
        assertEquals(0, depthSummary.currentWeekObserved());
    }

    @Test
    void teamUnknownTakesPrecedenceOverDepthAndPriorProductionGaps() {
        var result = SleeperLiveWaiverPregameEvidenceReadinessAudit.classify(
            dossier(List.of(candidate("p1", "Team Gap", false, false, false, true, false, "BOTH", false))));
        assertEquals(
            SleeperLiveWaiverPregameEvidenceReadinessAudit.PrimaryStratum.CURRENT_TEAM_UNKNOWN,
            result.candidates().get(0).primaryStratum());
    }

    @Test
    void depthMissingTakesPrecedenceOverPriorProductionState() {
        var result = SleeperLiveWaiverPregameEvidenceReadinessAudit.classify(
            dossier(List.of(candidate("p1", "Depth Gap", true, false, false, true, false, "BOTH", false))));
        assertEquals(
            SleeperLiveWaiverPregameEvidenceReadinessAudit.PrimaryStratum.DEPTH_EVIDENCE_MISSING,
            result.candidates().get(0).primaryStratum());
    }

    @Test
    void currentWeekUnobservedDoesNotBlockReviewablePregameCandidate() {
        var result = SleeperLiveWaiverPregameEvidenceReadinessAudit.classify(
            dossier(List.of(candidate("p1", "Pregame", true, true, true, true, false, "ADD_ONLY", false))));
        assertEquals(
            SleeperLiveWaiverPregameEvidenceReadinessAudit.PrimaryStratum.REVIEWABLE_WITH_PRIOR_PRODUCTION,
            result.candidates().get(0).primaryStratum());
        assertEquals(0, result.summaries().get(
            SleeperLiveWaiverPregameEvidenceReadinessAudit.PrimaryStratum.REVIEWABLE_WITH_PRIOR_PRODUCTION)
            .currentWeekObserved());
    }

    @Test
    void unknownProviderStatusFailsClosed() {
        var bad = candidate("p1", "Status Gap", true, true, true, false, false, "ADD_ONLY", false);
        IllegalStateException error = assertThrows(IllegalStateException.class,
            () -> SleeperLiveWaiverPregameEvidenceReadinessAudit.classify(dossier(List.of(bad))));
        assertTrue(error.getMessage().contains("provider status is unknown"));
    }

    private static SleeperLiveWaiverPregameEvidenceDossier.DossierReport dossier(
        List<SleeperLiveWaiverPregameEvidenceDossier.CandidateDossier> candidates) {
        return new SleeperLiveWaiverPregameEvidenceDossier.DossierReport(
            SleeperLiveWaiverPregameEvidenceDossier.POLICY_ID,
            "L", "M", "2026-09-08T01:00:00Z",
            "A", Instant.parse("2026-09-08T02:00:00Z"),
            "C", Instant.parse("2026-09-08T03:00:00Z"),
            "CURRENT_WEEK_FINALITY_UNPROVEN", 2026, 1, "regular",
            candidates.size(), 0, 0, 0, 0, 0, 0, candidates);
    }

    private static SleeperLiveWaiverPregameEvidenceDossier.CandidateDossier candidate(
        String id,
        String name,
        boolean teamKnown,
        boolean depthPresent,
        boolean priorPresent,
        boolean statusKnown,
        boolean injuryPresent,
        String frame,
        boolean weekObserved) {
        int adds = "DROP_ONLY".equals(frame) ? 0 : 10;
        int drops = "ADD_ONLY".equals(frame) ? 0 : 3;
        var market = new SleeperLiveWaiverProductionCoverageAudit.MarketCandidate(
            id, name, "WR", teamKnown ? "CHI" : null, statusKnown ? "Active" : null,
            adds, drops, adds - drops, frame);
        var availability = new LiveWaiverAvailabilityRepository.Entry(
            id, name, "WR", adds, drops, adds - drops, frame, "SOURCE_PRESENT",
            teamKnown ? "CHI" : null, statusKnown ? "Active" : null,
            injuryPresent ? "Questionable" : null, injuryPresent ? "2026-09-07" : null,
            null, depthPresent ? "WR" : null, depthPresent ? 2 : null);
        var week = new LiveWaiverCurrentWeekStatRepository.Entry(
            id, name, "WR", adds, drops, adds - drops, frame,
            weekObserved ? "SOURCE_PRESENT" : "SOURCE_ABSENT",
            weekObserved ? "{\"rec_tgt\":1}" : null,
            null, null, null, null, null, null, null, null,
            weekObserved ? 1.0 : null, null, null, null, null);
        var production = priorPresent
            ? List.of(new SleeperLiveWaiverProductionCoverageAudit.ProductionObservation(
                "nflverse", LocalDate.parse("2026-09-07"), 17, 0, 0, 0, 0, 30, 400, 2))
            : List.<SleeperLiveWaiverProductionCoverageAudit.ProductionObservation>of();
        return new SleeperLiveWaiverPregameEvidenceDossier.CandidateDossier(
            market, "b-" + id, production, availability, week,
            teamKnown ? "CURRENT_TEAM_KNOWN" : "CURRENT_TEAM_UNKNOWN",
            statusKnown ? "PROVIDER_STATUS_KNOWN" : "PROVIDER_STATUS_UNKNOWN",
            injuryPresent ? "INJURY_FLAG_PRESENT" : "INJURY_FLAG_ABSENT_OR_UNKNOWN",
            depthPresent ? "DEPTH_EVIDENCE_PRESENT" : "DEPTH_EVIDENCE_MISSING",
            priorPresent ? "PRIOR_SEASON_PRODUCTION_PRESENT" : "PRIOR_SEASON_PRODUCTION_MISSING",
            weekObserved ? "CURRENT_WEEK_OBSERVED" : "CURRENT_WEEK_UNOBSERVED");
    }
}
