package io.butler.bet.sleeper;

import io.butler.bet.data.Database;
import io.butler.bet.data.GovernedRecommendationAuditRepository;
import io.butler.bet.data.GovernedRecommendationExplanationRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SleeperLiveWaiverGovernedExplanationCaptureTest {
    @TempDir Path tempDir;

    @Test
    void samePositionCaptureIsIdempotentLeavesBf627AuditImmutableAndLookupReadsPersistedOnly() throws Exception {
        Database database = database();
        var audits = new GovernedRecommendationAuditRepository(database);
        var explanations = new GovernedRecommendationExplanationRepository(database);
        var persistedAudit = persistAudit(audits, "audit-1", "market-1", "waiver-1", "3214", "12493");
        var before = audits.findAllForLeague("butler-hardcore").get(0);
        var recommendation = recommendation("market-1", "waiver-1", "3214", "12493", List.of("TE"));
        var capture = new SleeperLiveWaiverGovernedExplanationCapture(
            audits,
            explanations,
            (leagueId, ownerId) -> recommendation,
            ignored -> { throw new AssertionError("BF-625 must not run for same-position explanation"); },
            Clock.fixed(Instant.parse("2026-09-09T06:00:00Z"), ZoneOffset.UTC));

        var first = capture.capture(target(), persistedAudit.id());
        var second = capture.capture(target(), persistedAudit.id());
        var after = audits.findAllForLeague("butler-hardcore").get(0);

        assertEquals(GovernedRecommendationExplanationRepository.CaptureState.CAPTURED_VERIFIED, first.captureState());
        assertEquals(GovernedRecommendationExplanationRepository.CaptureState.ALREADY_CAPTURED_EXACT, second.captureState());
        assertEquals(first.explanationId(), second.explanationId());
        assertEquals(SleeperLiveWaiverGovernedExplanationCapture.TYPE_SAME_POSITION, first.explanationType());
        assertNull(first.evidencePolicyId());
        assertNull(first.evidenceTrace());
        assertEquals(before, after, "BF-627 audit record must remain exactly immutable");

        var lookup = new SleeperLiveWaiverGovernedExplanationLookup(audits, explanations).lookup(target(), persistedAudit.id());
        assertEquals(SleeperLiveWaiverGovernedExplanationLookup.LookupState.EXPLANATION_READY, lookup.state());
        assertEquals(first.explanationId(), lookup.explanationId());
        assertEquals(first.explanationText(), lookup.explanationText());
        assertEquals("3214", lookup.addSleeperPlayerId());
        assertEquals("12493", lookup.dropSleeperPlayerId());
    }

    @Test
    void postTransactionBf610RosterDriftUsesGuardedReplayAndCapturesExactAuditCompanion() throws Exception {
        Database database = database();
        var audits = new GovernedRecommendationAuditRepository(database);
        var explanations = new GovernedRecommendationExplanationRepository(database);
        var audit = persistAudit(audits, "audit-post-tx", "market-post-tx", "waiver-post-tx", "3214", "12493");
        var replayRecommendation = recommendation("market-post-tx", "waiver-post-tx", "3214", "12493", List.of("TE"));
        int[] replayCalls = {0};
        var capture = new SleeperLiveWaiverGovernedExplanationCapture(
            audits,
            explanations,
            (leagueId, ownerId) -> { throw new IllegalStateException(
                "BF-610 BLOCKED: current roster membership drifted from BF-603/BF-602 frame; added=[3214] removed=[12493]; refresh BF-602/BF-603 and downstream live evidence before target-roster review"); },
            ignored -> { throw new AssertionError("normal BF-625 source must not run after BF-659 replay"); },
            Clock.fixed(Instant.parse("2026-09-09T11:30:00Z"), ZoneOffset.UTC),
            (verifiedTarget, exactAudit) -> {
                replayCalls[0]++;
                assertEquals(audit, exactAudit);
                return new SleeperLiveWaiverGovernedExplanationCapture.PostTransactionReplay(replayRecommendation, null);
            });

        var result = capture.capture(target(), audit.id());

        assertEquals(1, replayCalls[0]);
        assertEquals(GovernedRecommendationExplanationRepository.CaptureState.CAPTURED_VERIFIED, result.captureState());
        assertEquals(SleeperLiveWaiverGovernedExplanationCapture.TYPE_SAME_POSITION, result.explanationType());
        assertEquals(SleeperLiveWaiverGovernedExplanationCapture.SAME_POSITION_REASON, result.explanationText());
        assertEquals("3214", result.addSleeperPlayerId());
        assertEquals("12493", result.dropSleeperPlayerId());
        assertEquals(audit, audits.findAllForLeague("butler-hardcore").stream()
            .filter(value -> value.id().equals(audit.id())).findFirst().orElseThrow());
    }

    @Test
    void unrelatedLiveRecommendationFailureNeverTriggersPostTransactionReplay() throws Exception {
        Database database = database();
        var audits = new GovernedRecommendationAuditRepository(database);
        var explanations = new GovernedRecommendationExplanationRepository(database);
        var audit = persistAudit(audits, "audit-no-replay", "market-no-replay", "waiver-no-replay", "3214", "12493");
        int[] replayCalls = {0};
        var capture = new SleeperLiveWaiverGovernedExplanationCapture(
            audits,
            explanations,
            (leagueId, ownerId) -> { throw new IllegalStateException("BF-620 BLOCKED: unrelated freshness failure"); },
            ignored -> { throw new AssertionError("BF-625 must not run"); },
            Clock.fixed(Instant.parse("2026-09-09T11:30:00Z"), ZoneOffset.UTC),
            (verifiedTarget, exactAudit) -> {
                replayCalls[0]++;
                return new SleeperLiveWaiverGovernedExplanationCapture.PostTransactionReplay(
                    recommendation("market-no-replay", "waiver-no-replay", "3214", "12493", List.of("TE")), null);
            });

        IllegalStateException error = assertThrows(IllegalStateException.class,
            () -> capture.capture(target(), audit.id()));

        assertEquals("BF-620 BLOCKED: unrelated freshness failure", error.getMessage());
        assertEquals(0, replayCalls[0]);
        assertTrue(explanations.findByAuditId(audit.id()).isEmpty());
    }

    @Test
    void conflictingCompanionPayloadForSameAuditIsRejected() throws Exception {
        Database database = database();
        var audits = new GovernedRecommendationAuditRepository(database);
        var explanations = new GovernedRecommendationExplanationRepository(database);
        var audit = persistAudit(audits, "audit-2", "market-2", "waiver-2", "3214", "12493");
        Instant time = Instant.parse("2026-09-09T06:00:00Z");
        explanations.capture(new GovernedRecommendationExplanationRepository.ExplanationRecord(
            null, audit.id(), SleeperLiveWaiverGovernedExplanationCapture.POLICY_ID,
            "TYPE-A", "reason-a", null, null, time));

        IllegalStateException error = assertThrows(IllegalStateException.class, () -> explanations.capture(
            new GovernedRecommendationExplanationRepository.ExplanationRecord(
                null, audit.id(), SleeperLiveWaiverGovernedExplanationCapture.POLICY_ID,
                "TYPE-A", "reason-b", null, null, time.plusSeconds(60))));

        assertTrue(error.getMessage().contains("immutable explanation companion"));
        assertEquals("reason-a", explanations.findByAuditId(audit.id()).orElseThrow().explanationText());
    }

    @Test
    void changedAddDropFromRecomputedRecommendationBlocksBeforeExplanationWrite() throws Exception {
        Database database = database();
        var audits = new GovernedRecommendationAuditRepository(database);
        var explanations = new GovernedRecommendationExplanationRepository(database);
        var audit = persistAudit(audits, "audit-3", "market-3", "waiver-3", "3214", "12493");
        var changed = recommendation("market-3", "waiver-3", "8698", "12493", List.of("TE"));
        var capture = new SleeperLiveWaiverGovernedExplanationCapture(
            audits, explanations, (leagueId, ownerId) -> changed,
            ignored -> { throw new AssertionError("BF-625 must not run after audit mismatch"); },
            Clock.fixed(Instant.parse("2026-09-09T06:00:00Z"), ZoneOffset.UTC));

        IllegalStateException error = assertThrows(IllegalStateException.class,
            () -> capture.capture(target(), audit.id()));

        assertTrue(error.getMessage().contains("does not exactly reproduce immutable BF-627 audit"));
        assertTrue(explanations.findByAuditId(audit.id()).isEmpty());
    }

    @Test
    void crossPositionRequiresExactlyOneReconciledSelectedBf625Option() throws Exception {
        Database database = database();
        var audits = new GovernedRecommendationAuditRepository(database);
        var explanations = new GovernedRecommendationExplanationRepository(database);
        var audit = persistAudit(audits, "audit-4", "market-4", "waiver-4", "3214", "12493");
        var recommendation = recommendation("market-4", "waiver-4", "3214", "12493", List.of("TE", "WR"));
        var noSelectedEvidence = new SleeperLiveWaiverCrossPositionTransactionEvidence.EvidenceReport(
            SleeperLiveWaiverCrossPositionTransactionEvidence.POLICY_ID,
            recommendation.leagueId(), recommendation.sleeperOwnerId(),
            recommendation.marketSnapshotId(), recommendation.waiverSnapshotId(),
            recommendation.selection().state(), List.of(),
            SleeperLiveWaiverCrossPositionTransactionEvidence.EvidenceState.RECONCILED);
        var capture = new SleeperLiveWaiverGovernedExplanationCapture(
            audits, explanations, (leagueId, ownerId) -> recommendation,
            ignored -> noSelectedEvidence,
            Clock.fixed(Instant.parse("2026-09-09T06:00:00Z"), ZoneOffset.UTC));

        IllegalStateException error = assertThrows(IllegalStateException.class,
            () -> capture.capture(target(), audit.id()));

        assertTrue(error.getMessage().contains("exactly one BF-625 selected option"));
        assertTrue(explanations.findByAuditId(audit.id()).isEmpty());
    }

    @Test
    void crossPositionEvidenceTraceIsCanonicalAndDeterministic() {
        var add = player("3214", "Hunter Henry", "TE", "WAIVER_CANDIDATE");
        var drop = player("12493", "Oronde Gadsden", "TE", "BENCH");
        Map<String, Double> improvements = new LinkedHashMap<>();
        improvements.put("z-source", 2.5);
        improvements.put("a-source", 1.25);
        Map<String, List<String>> keys = new LinkedHashMap<>();
        keys.put("z-source", List.of("rec_yd", "rec"));
        keys.put("a-source", List.of("rec_td", "rec"));
        var option = new SleeperLiveWaiverCrossPositionTransactionEvidence.TransactionEvidence(
            add, drop, improvements, keys, true);

        String trace = SleeperLiveWaiverGovernedExplanationCapture.canonicalEvidenceTrace(option);

        assertEquals(
            "ADD=3214 | DROP=12493 | source=a-source,improvement=1.2500,scoringKeys=[rec, rec_td]"
                + " | source=z-source,improvement=2.5000,scoringKeys=[rec, rec_yd]",
            trace);
    }

    @Test
    void lookupReturnsNotCapturedWithoutCreatingExplanation() throws Exception {
        Database database = database();
        var audits = new GovernedRecommendationAuditRepository(database);
        var explanations = new GovernedRecommendationExplanationRepository(database);
        var audit = persistAudit(audits, "audit-5", "market-5", "waiver-5", "3214", "12493");

        var result = new SleeperLiveWaiverGovernedExplanationLookup(audits, explanations)
            .lookup(target(), audit.id());

        assertEquals(SleeperLiveWaiverGovernedExplanationLookup.LookupState.EXPLANATION_NOT_CAPTURED, result.state());
        assertNull(result.explanationId());
        assertEquals("3214", result.addSleeperPlayerId());
        assertEquals("12493", result.dropSleeperPlayerId());
    }

    private Database database() throws Exception {
        Database database = new Database(tempDir.resolve("test.db"));
        database.initialize();
        try (var connection = database.openConnection();
             var statement = connection.prepareStatement(
                 "INSERT INTO leagues(id, external_id, name, season) VALUES (?, ?, ?, ?)")) {
            statement.setString(1, "butler-hardcore");
            statement.setString(2, "1312110516008677376");
            statement.setString(3, "Hard(CORE)-Dynasty");
            statement.setInt(4, 2026);
            statement.executeUpdate();
        }
        return database;
    }

    private static GovernedRecommendationAuditRepository.AuditRecord persistAudit(
        GovernedRecommendationAuditRepository repository,
        String id, String market, String waiver, String addId, String dropId) throws Exception {
        var desired = new GovernedRecommendationAuditRepository.AuditRecord(
            id,
            "lineage-" + id,
            SleeperLiveWaiverRecommendationAuditCapture.POLICY_ID,
            "butler-hardcore",
            "1051699472830525440",
            "1312110516008677376",
            6,
            2026,
            "in_season",
            1,
            market,
            waiver,
            SleeperLiveWaiverFinalRecommendationBundle.BF618_POLICY_ID,
            SleeperLiveWaiverFinalRecommendationBundle.BF619_POLICY_ID,
            SleeperLiveWaiverFinalRecommendationBundle.BF620_POLICY_ID,
            SleeperLiveWaiverFinalRecommendationBundle.BF624_POLICY_ID,
            SleeperLiveWaiverFinalRecommendationBundle.SelectionState.UNIQUE_ADD_DROP_SELECTED.name(),
            SleeperLiveWaiverFinalRecommendationBundle.RecommendationState.RECOMMEND_ADD_DROP.name(),
            addId,
            dropId,
            Instant.parse("2026-09-09T05:00:00Z"));
        return repository.capture(desired).record();
    }

    private static SleeperPersonalizedTargetService.VerifiedTarget target() {
        return new SleeperPersonalizedTargetService.VerifiedTarget(
            SleeperPersonalizedTargetService.BF623_POLICY_ID,
            "butler-hardcore",
            "mbutler0624",
            "1051699472830525440",
            "1312110516008677376",
            "Hard(CORE)-Dynasty",
            "in_season",
            6,
            SleeperPersonalizedTargetService.MembershipRole.OWNER,
            "mbutler0624",
            "nuke the whales",
            SleeperPersonalizedTargetService.VerificationState.BOUND_TARGET_LIVE_VERIFIED);
    }

    private static SleeperLiveWaiverFinalRecommendationBundle.RecommendationReport recommendation(
        String market, String waiver, String addId, String dropId, List<String> positions) {
        var add = player(addId, "Add Player", positions.get(0), "WAIVER_CANDIDATE");
        var drop = player(dropId, "Drop Player", positions.get(0), "BENCH");
        var methodology = new SleeperLiveWaiverFinalRecommendationBundle.MethodologyReport(
            SleeperLiveWaiverFinalRecommendationBundle.BF618_POLICY_ID,
            market,
            positions.size(),
            0,
            positions,
            "HISTORICAL_FINALISTS_DIRECT_ALL_OPPONENTS_DOMINANCE_WITHIN_POSITION",
            "LATEST_2025_COMMON_SOURCE_SUPPORTED_SUBTOTAL_PER_GAME_SCHEMA_EQUALITY",
            "BF624_COMPLETE_TRANSACTION_DELTA_STRICT_ALL_COMPATIBLE_COMMON_SOURCE_DOMINANCE",
            "NEWCOMERS_NONNUMERIC_NOT_ELIGIBLE_FOR_FINAL_WINNER",
            "DROP_ONLY_FROM_SELECTED_ADD_BF615_CANDIDATE_SUPPORTED_BENCH_RESERVE_COMPARATORS",
            "PROTECTED_MISSING_PRODUCTION_TARGET_NEVER_DROPPABLE",
            SleeperLiveWaiverFinalRecommendationBundle.MethodologyState.FINAL_SELECTION_METHOD_FROZEN);
        var selection = new SleeperLiveWaiverFinalRecommendationBundle.SelectionReport(
            SleeperLiveWaiverFinalRecommendationBundle.BF619_POLICY_ID,
            market,
            List.of(addId, dropId),
            List.of(),
            add,
            drop,
            SleeperLiveWaiverFinalRecommendationBundle.SelectionState.UNIQUE_ADD_DROP_SELECTED);
        return new SleeperLiveWaiverFinalRecommendationBundle.RecommendationReport(
            SleeperLiveWaiverFinalRecommendationBundle.BF620_POLICY_ID,
            "butler-hardcore",
            "1051699472830525440",
            market,
            waiver,
            "1312110516008677376",
            6,
            methodology,
            selection,
            2026,
            "in_season",
            1,
            add,
            drop,
            List.of(),
            SleeperLiveWaiverFinalRecommendationBundle.RecommendationState.RECOMMEND_ADD_DROP);
    }

    private static SleeperLiveWaiverFinalRecommendationBundle.SelectedPlayer player(
        String id, String name, String position, String role) {
        return new SleeperLiveWaiverFinalRecommendationBundle.SelectedPlayer(
            id, name, position, role, null, null, null, null, null);
    }
}
