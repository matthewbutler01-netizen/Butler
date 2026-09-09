from pathlib import Path
import re


def require_count(label, count):
    if count != 1:
        raise SystemExit(f"{label}: expected 1 replacement, got {count}")


# BF-620: retain the exact BF-615 bundle used by the single governed recommendation evaluation.
path = Path('bet/bet-cli/src/main/java/io/butler/bet/sleeper/SleeperLiveWaiverFinalRecommendationBundle.java')
text = path.read_text()
start = text.index('    public RecommendationReport run(String leagueId, String sleeperOwnerId)')
end = text.index('    private MethodologyReport methodology(', start)
replacement = '''    public RecommendationReport run(String leagueId, String sleeperOwnerId)
        throws SQLException, IOException, InterruptedException {
        return execute(leagueId, sleeperOwnerId).recommendation();
    }

    /** BF-660 retains the exact BF-615 bundle used by this BF-620 recommendation for audit-time explanation capture. */
    RecommendationExecution execute(String leagueId, String sleeperOwnerId)
        throws SQLException, IOException, InterruptedException {
        String normalizedLeagueId = requireText(leagueId, "leagueId");
        String normalizedOwnerId = requireText(sleeperOwnerId, "sleeperOwnerId");

        SleeperLiveWaiverComparisonExecutionBundle.BundleReport bundle =
            bundleSource.run(normalizedLeagueId, normalizedOwnerId);
        validateBundle(bundle, normalizedLeagueId, normalizedOwnerId);

        MethodologyReport methodology = methodology(bundle);
        SelectionReport selection = select(bundle);

        SleeperLiveWaiverTargetRosterContextAudit.AuditReport freshness =
            freshnessSource.audit(normalizedLeagueId, normalizedOwnerId);
        validateFreshness(bundle, freshness, normalizedLeagueId, normalizedOwnerId);

        RecommendationReport recommendation;
        if (selection.state() != SelectionState.UNIQUE_ADD_DROP_SELECTED) {
            recommendation = new RecommendationReport(
                BF620_POLICY_ID, normalizedLeagueId, normalizedOwnerId,
                bundle.comparisons().marketSnapshotId(), bundle.comparisons().waiverSnapshotId(),
                bundle.comparisons().sleeperLeagueId(), bundle.comparisons().rosterId(),
                methodology, selection, freshness.providerSeason(), freshness.providerStatus(), freshness.providerLeg(),
                null, null, newcomerAlternatives(bundle),
                RecommendationState.NO_GOVERNED_TRANSACTION);
        } else {
            var add = Objects.requireNonNull(selection.selectedAdd());
            var drop = Objects.requireNonNull(selection.selectedDrop());
            validateSelectedPairAgainstLiveAndSnapshot(bundle, freshness, add, drop);
            recommendation = new RecommendationReport(
                BF620_POLICY_ID, normalizedLeagueId, normalizedOwnerId,
                bundle.comparisons().marketSnapshotId(), bundle.comparisons().waiverSnapshotId(),
                bundle.comparisons().sleeperLeagueId(), bundle.comparisons().rosterId(),
                methodology, selection, freshness.providerSeason(), freshness.providerStatus(), freshness.providerLeg(),
                add, drop, newcomerAlternatives(bundle),
                RecommendationState.RECOMMEND_ADD_DROP);
        }
        return new RecommendationExecution(recommendation, bundle);
    }

'''
text = text[:start] + replacement + text[end:]
marker = '    @FunctionalInterface\n    interface BundleSource {'
if marker not in text:
    raise SystemExit('BF-620 BundleSource marker missing')
text = text.replace(marker, '''    record RecommendationExecution(
        RecommendationReport recommendation,
        SleeperLiveWaiverComparisonExecutionBundle.BundleReport bundle) {
        RecommendationExecution {
            Objects.requireNonNull(recommendation, "recommendation must not be null");
            Objects.requireNonNull(bundle, "bundle must not be null");
        }
    }

''' + marker, 1)
path.write_text(text)


# BF-653: expose the existing reconciliation/payload/persistence logic for the exact BF-627 in-memory recommendation.
path = Path('bet/bet-cli/src/main/java/io/butler/bet/sleeper/SleeperLiveWaiverGovernedExplanationCapture.java')
text = path.read_text()
start = text.index('        SleeperLiveWaiverFinalRecommendationBundle.RecommendationReport recommendation =')
end = text.index('    private GovernedRecommendationAuditRepository.AuditRecord exactAudit', start)
replacement = '''        SleeperLiveWaiverFinalRecommendationBundle.RecommendationReport recommendation =
            recommendationSource.run(target.butlerLeagueId(), target.sleeperUserId());
        return captureCompanion(explanationRepository, audit, recommendation, evidenceSource, clock);
    }

    /** BF-660 writes a BF-653 companion from the exact recommendation already captured by BF-627. */
    static CaptureReport captureCompanion(
        GovernedRecommendationExplanationRepository explanationRepository,
        GovernedRecommendationAuditRepository.AuditRecord audit,
        SleeperLiveWaiverFinalRecommendationBundle.RecommendationReport recommendation,
        EvidenceSource evidenceSource,
        Clock clock)
        throws SQLException, IOException, InterruptedException {
        Objects.requireNonNull(explanationRepository, "explanationRepository must not be null");
        Objects.requireNonNull(audit, "audit must not be null");
        Objects.requireNonNull(recommendation, "recommendation must not be null");
        Objects.requireNonNull(evidenceSource, "evidenceSource must not be null");
        Objects.requireNonNull(clock, "clock must not be null");
        reconcileAudit(audit, recommendation);
        ExplanationPayload payload = explanationPayload(recommendation, evidenceSource);

        var desired = new GovernedRecommendationExplanationRepository.ExplanationRecord(
            null,
            audit.id(),
            POLICY_ID,
            payload.type(),
            payload.text(),
            payload.evidencePolicyId(),
            payload.evidenceTrace(),
            clock.instant());
        var persisted = explanationRepository.capture(desired);
        var readback = explanationRepository.findByAuditId(audit.id())
            .orElseThrow(() -> new IllegalStateException("BF-653 BLOCKED: explanation missing after capture"));
        if (!persisted.record().id().equals(readback.id())) {
            throw new IllegalStateException("BF-653 BLOCKED: explanation readback id does not match capture result");
        }

        return new CaptureReport(
            POLICY_ID,
            persisted.state(),
            readback.id(),
            readback.auditId(),
            readback.capturedAtUtc().toString(),
            readback.explanationType(),
            readback.explanationText(),
            readback.evidencePolicyId(),
            readback.evidenceTrace(),
            audit.marketSnapshotId(),
            audit.waiverSnapshotId(),
            audit.addSleeperPlayerId(),
            audit.dropSleeperPlayerId());
    }

'''
text = text[:start] + replacement + text[end:]
path.write_text(text)


# BF-627: capture the immutable audit and BF-653 companion from one DecisionFrame.
path = Path('bet/bet-cli/src/main/java/io/butler/bet/sleeper/SleeperLiveWaiverRecommendationAuditCapture.java')
text = path.read_text()
text, count = re.subn(
    r'import io\.butler\.bet\.data\.GovernedRecommendationAuditRepository;\n',
    'import io.butler.bet.data.GovernedRecommendationAuditRepository;\n'
    'import io.butler.bet.data.GovernedRecommendationExplanationRepository;\n'
    'import io.butler.bet.data.PlayerSeasonProductionRepository;\n',
    text, count=1)
require_count('BF-627 imports', count)

fields_start = text.index('    private final RecommendationSource recommendationSource;')
ctor_start = text.index('    public SleeperLiveWaiverRecommendationAuditCapture(Database database) {', fields_start)
fields = '''    private final DecisionSource decisionSource;
    private final GovernedRecommendationAuditRepository repository;
    private final GovernedRecommendationExplanationRepository explanationRepository;
    private final Clock clock;

'''
text = text[:fields_start] + fields + text[ctor_start:]

capture_start = text.index('    public CaptureReport capture(', ctor_start)
ctors = '''    public SleeperLiveWaiverRecommendationAuditCapture(Database database) {
        this(
            productionDecisionSource(database),
            new GovernedRecommendationAuditRepository(database),
            new GovernedRecommendationExplanationRepository(database),
            Clock.systemUTC());
    }

    SleeperLiveWaiverRecommendationAuditCapture(
        RecommendationSource recommendationSource,
        GovernedRecommendationAuditRepository repository,
        GovernedRecommendationExplanationRepository explanationRepository,
        Clock clock) {
        this(
            (leagueId, ownerId) -> new DecisionFrame(recommendationSource.run(leagueId, ownerId), null),
            repository,
            explanationRepository,
            clock);
    }

    SleeperLiveWaiverRecommendationAuditCapture(
        DecisionSource decisionSource,
        GovernedRecommendationAuditRepository repository,
        GovernedRecommendationExplanationRepository explanationRepository,
        Clock clock) {
        this.decisionSource = Objects.requireNonNull(decisionSource, "decisionSource must not be null");
        this.repository = Objects.requireNonNull(repository, "repository must not be null");
        this.explanationRepository = Objects.requireNonNull(explanationRepository, "explanationRepository must not be null");
        this.clock = Objects.requireNonNull(clock, "clock must not be null");
    }

    private static DecisionSource productionDecisionSource(Database database) {
        Objects.requireNonNull(database, "database must not be null");
        return (leagueId, ownerId) -> {
            SleeperLiveWaiverFinalRecommendationBundle finalBundle =
                new SleeperLiveWaiverFinalRecommendationBundle(database);
            var execution = finalBundle.execute(leagueId, ownerId);
            var recommendation = execution.recommendation();
            SleeperLiveWaiverCrossPositionTransactionEvidence.EvidenceReport evidence = null;
            if (recommendation.state()
                    == SleeperLiveWaiverFinalRecommendationBundle.RecommendationState.RECOMMEND_ADD_DROP
                && recommendation.methodology().historicalFinalistPositions().size() > 1) {
                PlayerSeasonProductionRepository productionRepository =
                    new PlayerSeasonProductionRepository(database);
                evidence = new SleeperLiveWaiverCrossPositionTransactionEvidence(
                    (ignoredLeague, ignoredOwner) -> execution.bundle(),
                    productionRepository::findByPlayerId)
                    .explain(recommendation);
            }
            return new DecisionFrame(recommendation, evidence);
        };
    }

'''
text = text[:ctor_start] + ctors + text[capture_start:]

text, count = re.subn(
    r'        var recommendation = recommendationSource\.run\(target\.butlerLeagueId\(\), target\.sleeperUserId\(\)\);\n'
    r'        reconcile\(target, recommendation\);',
    '        DecisionFrame decision = decisionSource.run(target.butlerLeagueId(), target.sleeperUserId());\n'
    '        var recommendation = decision.recommendation();\n'
    '        reconcile(target, recommendation);',
    text, count=1)
require_count('BF-627 decision evaluation', count)

return_start = text.index('        return new CaptureReport(', text.index('public CaptureReport capture'))
return_end = text.index('    private static void validateVerifiedTarget', return_start)
return_block = '''        SleeperLiveWaiverGovernedExplanationCapture.EvidenceSource evidenceSource = ignored -> {
            if (decision.crossPositionEvidence() == null) {
                throw new IllegalStateException(
                    "BF-660 BLOCKED: exact cross-position BF-625 evidence is missing from BF-627 decision frame");
            }
            return decision.crossPositionEvidence();
        };
        var explanation = SleeperLiveWaiverGovernedExplanationCapture.captureCompanion(
            explanationRepository, readback, recommendation, evidenceSource, clock);

        return new CaptureReport(
            POLICY_ID,
            persisted.state(),
            readback.id(),
            readback.capturedAtUtc().toString(),
            readback.leagueId(),
            readback.sleeperOwnerId(),
            readback.sleeperLeagueId(),
            readback.rosterId(),
            readback.season(),
            readback.providerStatus(),
            readback.providerLeg(),
            readback.marketSnapshotId(),
            readback.waiverSnapshotId(),
            readback.selectionState(),
            readback.recommendationState(),
            readback.addSleeperPlayerId(),
            readback.dropSleeperPlayerId(),
            repository.countForLeague(readback.leagueId()),
            explanation.captureState(),
            explanation.explanationId(),
            explanation.explanationType(),
            explanation.explanationText(),
            explanation.evidencePolicyId(),
            explanation.evidenceTrace());
    }

'''
text = text[:return_start] + return_block + text[return_end:]

iface_marker = '    @FunctionalInterface\n    interface RecommendationSource {'
iface_pos = text.index(iface_marker)
report_pos = text.index('    public record CaptureReport(', iface_pos)
interfaces = '''    @FunctionalInterface
    interface RecommendationSource {
        SleeperLiveWaiverFinalRecommendationBundle.RecommendationReport run(String leagueId, String ownerId)
            throws SQLException, IOException, InterruptedException;
    }

    @FunctionalInterface
    interface DecisionSource {
        DecisionFrame run(String leagueId, String ownerId)
            throws SQLException, IOException, InterruptedException;
    }

    record DecisionFrame(
        SleeperLiveWaiverFinalRecommendationBundle.RecommendationReport recommendation,
        SleeperLiveWaiverCrossPositionTransactionEvidence.EvidenceReport crossPositionEvidence) {
        DecisionFrame {
            Objects.requireNonNull(recommendation, "recommendation must not be null");
        }
    }

'''
text = text[:iface_pos] + interfaces + text[report_pos:]

record_start = text.index('    public record CaptureReport(')
class_close = text.rfind('\n}')
record = '''    public record CaptureReport(
        String policyId,
        GovernedRecommendationAuditRepository.CaptureState captureState,
        String auditId,
        String capturedAtUtc,
        String leagueId,
        String sleeperOwnerId,
        String sleeperLeagueId,
        int rosterId,
        int providerSeason,
        String providerStatus,
        Integer providerLeg,
        String marketSnapshotId,
        String waiverSnapshotId,
        String selectionState,
        String recommendationState,
        String addSleeperPlayerId,
        String dropSleeperPlayerId,
        int retainedAuditRecordsForLeague,
        GovernedRecommendationExplanationRepository.CaptureState explanationCaptureState,
        String explanationId,
        String explanationType,
        String explanationText,
        String explanationEvidencePolicyId,
        String explanationEvidenceTrace) {
        public CaptureReport {
            if (!POLICY_ID.equals(policyId)) throw new IllegalArgumentException("unexpected BF-627 policyId");
            Objects.requireNonNull(captureState, "captureState must not be null");
            Objects.requireNonNull(explanationCaptureState, "explanationCaptureState must not be null");
            Objects.requireNonNull(explanationId, "explanationId must not be null");
            Objects.requireNonNull(explanationType, "explanationType must not be null");
            Objects.requireNonNull(explanationText, "explanationText must not be null");
        }
    }
'''
text = text[:record_start] + record + text[class_close:]
path.write_text(text)


# BF-627 CLI: show the complete governed package.
path = Path('bet/bet-cli/src/main/java/io/butler/bet/cli/ButlerSleeperLiveWaiverRecommendationAuditCaptureCli.java')
text = path.read_text()
marker = '        System.out.println("Immutable audit records retained for league: " + report.retainedAuditRecordsForLeague());\n'
if marker not in text:
    raise SystemExit('BF-627 CLI retained-count marker missing')
text = text.replace(marker, marker + '''        System.out.println("BF-653 explanation capture state: " + report.explanationCaptureState());
        System.out.println("Explanation id / type: " + report.explanationId() + " / " + report.explanationType());
        System.out.println("Explanation: " + report.explanationText());
        System.out.println("Explanation evidence policy: " + value(report.explanationEvidencePolicyId()));
        System.out.println("Explanation evidence trace: " + value(report.explanationEvidenceTrace()));
''', 1)
text = text.replace(
    'Boundary: BF-627 writes only an immutable Butler audit record after BF-623 live identity verification and BF-620 lineage reconciliation. It does not submit a Sleeper transaction, set FAAB, mutate the Sleeper league, or change the governed recommendation methodology.',
    'Boundary: BF-627 writes an immutable Butler audit plus its BF-653 explanation companion from the same exact governed recommendation frame after BF-623 live identity verification and BF-620 lineage reconciliation. It does not submit a Sleeper transaction, set FAAB, mutate the Sleeper league, refresh evidence, rerank players, or change the governed recommendation methodology.')
path.write_text(text)


# Existing BF-627 regressions: provide the real explanation repository and assert same/no-transaction companions.
path = Path('bet/bet-cli/src/test/java/io/butler/bet/sleeper/SleeperLiveWaiverRecommendationAuditCaptureTest.java')
text = path.read_text()
text = text.replace(
    'import io.butler.bet.data.GovernedRecommendationAuditRepository;\n',
    'import io.butler.bet.data.GovernedRecommendationAuditRepository;\nimport io.butler.bet.data.GovernedRecommendationExplanationRepository;\n', 1)
text = text.replace('import java.util.List;\n', 'import java.util.List;\nimport java.util.Map;\nimport java.util.concurrent.atomic.AtomicInteger;\n', 1)
text = text.replace('service(repository, ', 'service(database, repository, ')

old_direct = '''        var service = new SleeperLiveWaiverRecommendationAuditCapture(
            (leagueId, ownerId) -> current.get(),
            repository,
            Clock.fixed(Instant.parse("2026-09-08T09:40:00Z"), ZoneOffset.UTC));
'''
new_direct = '''        var service = new SleeperLiveWaiverRecommendationAuditCapture(
            (leagueId, ownerId) -> current.get(),
            repository,
            new GovernedRecommendationExplanationRepository(database),
            Clock.fixed(Instant.parse("2026-09-08T09:40:00Z"), ZoneOffset.UTC));
'''
if old_direct not in text:
    raise SystemExit('BF-627 direct test constructor marker missing')
text = text.replace(old_direct, new_direct, 1)

first_marker = '        assertEquals(1, repository.countForLeague("butler-hardcore"));\n'
first_pos = text.index(first_marker)
first_end = first_pos + len(first_marker)
text = text[:first_end] + '''        assertEquals(GovernedRecommendationExplanationRepository.CaptureState.CAPTURED_VERIFIED, first.explanationCaptureState());
        assertEquals(GovernedRecommendationExplanationRepository.CaptureState.ALREADY_CAPTURED_EXACT, second.explanationCaptureState());
        assertEquals(first.explanationId(), second.explanationId());
        assertEquals(SleeperLiveWaiverGovernedExplanationCapture.TYPE_SAME_POSITION, first.explanationType());
        assertEquals(SleeperLiveWaiverGovernedExplanationCapture.SAME_POSITION_REASON, first.explanationText());
''' + text[first_end:]

no_tx_method = text.index('void noGovernedTransactionIsPersistedWithoutInventingAddDrop')
no_tx_marker_pos = text.index(first_marker, no_tx_method)
no_tx_end = no_tx_marker_pos + len(first_marker)
text = text[:no_tx_end] + '''        assertEquals(SleeperLiveWaiverGovernedExplanationCapture.TYPE_NO_TRANSACTION, result.explanationType());
        assertEquals(SleeperLiveWaiverGovernedExplanationCapture.NO_TRANSACTION_REASON, result.explanationText());
''' + text[no_tx_end:]

helper_start = text.index('    private SleeperLiveWaiverRecommendationAuditCapture service(')
helper_end = text.index('    private Database database()', helper_start)
helper = '''    private SleeperLiveWaiverRecommendationAuditCapture service(
        Database database,
        GovernedRecommendationAuditRepository repository,
        SleeperLiveWaiverFinalRecommendationBundle.RecommendationReport recommendation) {
        return new SleeperLiveWaiverRecommendationAuditCapture(
            (leagueId, ownerId) -> recommendation,
            repository,
            new GovernedRecommendationExplanationRepository(database),
            Clock.fixed(Instant.parse("2026-09-08T09:40:00Z"), ZoneOffset.UTC));
    }

'''
text = text[:helper_start] + helper + text[helper_end:]

insert_marker = '    private SleeperLiveWaiverRecommendationAuditCapture service(\n'
cross_test = '''    @Test
    void bf660UsesOneExactDecisionEvaluationAndPersistsCrossPositionEvidence() throws Exception {
        Database database = database();
        var repository = new GovernedRecommendationAuditRepository(database);
        var explanations = new GovernedRecommendationExplanationRepository(database);
        var recommendation = crossPositionRecommendation("market-x", "waiver-x", "7049", "12503");
        var evidence = crossPositionEvidence(recommendation);
        AtomicInteger evaluations = new AtomicInteger();
        var service = new SleeperLiveWaiverRecommendationAuditCapture(
            (SleeperLiveWaiverRecommendationAuditCapture.DecisionSource) (leagueId, ownerId) -> {
                evaluations.incrementAndGet();
                return new SleeperLiveWaiverRecommendationAuditCapture.DecisionFrame(recommendation, evidence);
            },
            repository,
            explanations,
            Clock.fixed(Instant.parse("2026-09-08T09:40:00Z"), ZoneOffset.UTC));

        var result = service.capture(target());

        assertEquals(1, evaluations.get());
        assertEquals(SleeperLiveWaiverGovernedExplanationCapture.TYPE_CROSS_POSITION, result.explanationType());
        assertEquals(SleeperLiveWaiverCrossPositionTransactionEvidence.POLICY_ID, result.explanationEvidencePolicyId());
        assertTrue(result.explanationEvidenceTrace().contains("ADD=7049"));
        assertTrue(result.explanationEvidenceTrace().contains("DROP=12503"));
        assertTrue(explanations.findByAuditId(result.auditId()).isPresent());
    }

'''
if insert_marker not in text:
    raise SystemExit('BF-627 test helper insertion marker missing')
text = text.replace(insert_marker, cross_test + insert_marker, 1)

no_tx_helper = '    private static SleeperLiveWaiverFinalRecommendationBundle.RecommendationReport noTransaction(\n'
cross_helpers = '''    private static SleeperLiveWaiverFinalRecommendationBundle.RecommendationReport crossPositionRecommendation(
        String market, String waiver, String addId, String dropId) {
        var base = recommendation(market, waiver, addId, dropId);
        var methodology = new SleeperLiveWaiverFinalRecommendationBundle.MethodologyReport(
            SleeperLiveWaiverFinalRecommendationBundle.BF618_POLICY_ID,
            market,
            2,
            0,
            List.of("RB", "WR"),
            base.methodology().addWinnerRule(),
            base.methodology().evidenceRule(),
            base.methodology().crossPositionRule(),
            base.methodology().newcomerRule(),
            base.methodology().dropRule(),
            base.methodology().protectedTargetRule(),
            base.methodology().state());
        return new SleeperLiveWaiverFinalRecommendationBundle.RecommendationReport(
            base.policyId(), base.leagueId(), base.sleeperOwnerId(), base.marketSnapshotId(), base.waiverSnapshotId(),
            base.sleeperLeagueId(), base.rosterId(), methodology, base.selection(), base.providerSeason(),
            base.providerStatus(), base.providerLeg(), base.recommendedAdd(), base.recommendedDrop(),
            base.newcomerReviewAlternatives(), base.state());
    }

    private static SleeperLiveWaiverCrossPositionTransactionEvidence.EvidenceReport crossPositionEvidence(
        SleeperLiveWaiverFinalRecommendationBundle.RecommendationReport recommendation) {
        var option = new SleeperLiveWaiverCrossPositionTransactionEvidence.TransactionEvidence(
            recommendation.recommendedAdd(),
            recommendation.recommendedDrop(),
            Map.of("nflverse", 1.25),
            Map.of("nflverse", List.of("rec", "rec_yd")),
            true);
        return new SleeperLiveWaiverCrossPositionTransactionEvidence.EvidenceReport(
            SleeperLiveWaiverCrossPositionTransactionEvidence.POLICY_ID,
            recommendation.leagueId(),
            recommendation.sleeperOwnerId(),
            recommendation.marketSnapshotId(),
            recommendation.waiverSnapshotId(),
            recommendation.selection().state(),
            List.of(option),
            SleeperLiveWaiverCrossPositionTransactionEvidence.EvidenceState.RECONCILED);
    }

'''
if no_tx_helper not in text:
    raise SystemExit('BF-627 cross-position helper marker missing')
text = text.replace(no_tx_helper, cross_helpers + no_tx_helper, 1)
path.write_text(text)
