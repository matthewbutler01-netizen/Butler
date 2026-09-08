package io.butler.bet.sleeper;

import io.butler.bet.data.Database;
import io.butler.bet.data.PlayerSeasonProductionRepository;
import io.butler.bet.domain.PlayerSeasonProduction;

import java.io.IOException;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;

/** BF-615 through BF-617 bundled read-only execution of the frozen live waiver comparison methodology. */
public final class SleeperLiveWaiverComparisonExecutionBundle {
    public static final String BF615_POLICY_ID =
        "sleeper-live-waiver-comparison-execution-v1-bf614-exact-position-replacement-pool-read-only";
    public static final String BF616_POLICY_ID =
        "sleeper-live-waiver-shortlist-v1-bf615-directional-and-newcomer-lanes-read-only";
    public static final String BF617_POLICY_ID =
        "sleeper-live-waiver-final-decision-readiness-v1-bf616-reconciled-no-recommendation";
    private static final int PRODUCTION_SEASON = 2025;

    private final MethodologySource methodologySource;
    private final CandidateSource candidateSource;
    private final RosterSource rosterSource;
    private final ProductionSource productionSource;

    public SleeperLiveWaiverComparisonExecutionBundle(Database database) {
        Objects.requireNonNull(database, "database must not be null");
        PlayerSeasonProductionRepository productionRepository = new PlayerSeasonProductionRepository(database);
        this.methodologySource = (leagueId, ownerId) ->
            new SleeperLiveWaiverCandidateRosterComparisonMethodology(database).audit(leagueId, ownerId);
        this.candidateSource = leagueId -> candidateFrame(
            new SleeperLiveWaiverPregameEvidenceReadinessAudit(database).audit(leagueId));
        this.rosterSource = (leagueId, ownerId) -> rosterFrame(
            new SleeperLiveWaiverTargetRosterProductionComparabilityAudit(database).audit(leagueId, ownerId));
        this.productionSource = productionRepository::findByPlayerId;
    }

    SleeperLiveWaiverComparisonExecutionBundle(
        MethodologySource methodologySource,
        CandidateSource candidateSource,
        RosterSource rosterSource,
        ProductionSource productionSource) {
        this.methodologySource = Objects.requireNonNull(methodologySource, "methodologySource must not be null");
        this.candidateSource = Objects.requireNonNull(candidateSource, "candidateSource must not be null");
        this.rosterSource = Objects.requireNonNull(rosterSource, "rosterSource must not be null");
        this.productionSource = Objects.requireNonNull(productionSource, "productionSource must not be null");
    }

    public BundleReport run(String leagueId, String sleeperOwnerId)
        throws SQLException, IOException, InterruptedException {
        String normalizedLeagueId = requireText(leagueId, "leagueId");
        String normalizedOwnerId = requireText(sleeperOwnerId, "sleeperOwnerId");

        SleeperLiveWaiverCandidateRosterComparisonMethodology.MethodologyReport methodology =
            methodologySource.audit(normalizedLeagueId, normalizedOwnerId);
        validateMethodology(methodology, normalizedLeagueId, normalizedOwnerId);

        CandidateFrame candidates = candidateSource.audit(normalizedLeagueId);
        RosterFrame roster = rosterSource.audit(normalizedLeagueId, normalizedOwnerId);
        validateLineage(methodology, candidates, roster, normalizedLeagueId, normalizedOwnerId);

        ComparisonReport comparisons = executeComparisons(methodology, candidates, roster);
        ShortlistReport shortlist = buildShortlist(comparisons);
        DecisionReadinessReport readiness = authorizeDecisionMethod(comparisons, shortlist);
        return new BundleReport(methodology, comparisons, shortlist, readiness);
    }

    private ComparisonReport executeComparisons(
        SleeperLiveWaiverCandidateRosterComparisonMethodology.MethodologyReport methodology,
        CandidateFrame candidates,
        RosterFrame roster) throws SQLException {
        List<RosterEntry> replacementPool = roster.entries().stream()
            .filter(value -> SleeperLiveWaiverCandidateRosterComparisonMethodology
                .eligibleReplacementSlot(value.rosterSlot()))
            .sorted(Comparator.comparing(RosterEntry::position)
                .thenComparing(RosterEntry::sleeperPlayerId))
            .toList();
        if (replacementPool.size() != methodology.benchCount() + methodology.reserveCount()) {
            throw new IllegalStateException("BF-615 BLOCKED: BENCH/RESERVE replacement pool does not reconcile with BF-614");
        }

        List<CandidateComparison> candidateComparisons = new ArrayList<>();
        List<PairComparison> allPairs = new ArrayList<>();
        MutablePairCounts aggregate = new MutablePairCounts();

        for (CandidateEntry candidate : candidates.entries()) {
            List<RosterEntry> exactComparators = replacementPool.stream()
                .filter(value -> SleeperLiveWaiverCandidateRosterComparisonMethodology
                    .samePosition(candidate.position(), value.position()))
                .toList();
            List<PairComparison> pairs = new ArrayList<>();

            if (!exactComparators.isEmpty()) {
                if (!candidate.priorProductionPresent()) {
                    for (RosterEntry comparator : exactComparators) {
                        PairComparison pair = nonNumericPair(candidate, comparator, PairState.NEWCOMER_NONNUMERIC);
                        pairs.add(pair);
                        allPairs.add(pair);
                        aggregate.observe(pair.state());
                    }
                } else {
                    Map<String, PlayerSeasonProduction> candidateProduction = latest2025BySource(
                        productionSource.load(candidate.butlerPlayerId()));
                    if (candidateProduction.isEmpty()) {
                        throw new IllegalStateException("BF-615 BLOCKED: reviewable prior-production candidate has no exact 2025 row: "
                            + candidate.sleeperPlayerId());
                    }
                    for (RosterEntry comparator : exactComparators) {
                        PairComparison pair;
                        if (!comparator.priorProductionPresent()) {
                            pair = nonNumericPair(candidate, comparator, PairState.TARGET_PRIOR_PRODUCTION_PROTECTED);
                        } else {
                            Map<String, PlayerSeasonProduction> rosterProduction = latest2025BySource(
                                productionSource.load(comparator.butlerPlayerId()));
                            if (rosterProduction.isEmpty()) {
                                throw new IllegalStateException("BF-615 BLOCKED: production-present target comparator has no exact 2025 row: "
                                    + comparator.sleeperPlayerId());
                            }
                            pair = numericPair(
                                candidate, comparator, candidateProduction, rosterProduction,
                                methodology.exactLeagueScoringSettings());
                        }
                        pairs.add(pair);
                        allPairs.add(pair);
                        aggregate.observe(pair.state());
                    }
                }
            }

            CandidateExecutionState executionState;
            if (exactComparators.isEmpty()) executionState = CandidateExecutionState.NO_ELIGIBLE_REPLACEMENT_COMPARATOR;
            else if (!candidate.priorProductionPresent()) executionState = CandidateExecutionState.NEWCOMER_REVIEW_EXECUTED_NONNUMERIC;
            else executionState = CandidateExecutionState.HISTORICAL_PAIRWISE_COMPARISON_EXECUTED;

            candidateComparisons.add(new CandidateComparison(
                candidate,
                executionState,
                exactComparators.stream().map(RosterEntry::sleeperPlayerId).toList(),
                List.copyOf(pairs)));
        }

        if (candidateComparisons.size() != candidates.reviewableCandidateCount()) {
            throw new IllegalStateException("BF-615 BLOCKED: executed candidate count does not reconcile with reviewable frame");
        }
        int pairReconciled = candidateComparisons.stream().mapToInt(value -> value.pairs().size()).sum();
        if (pairReconciled != allPairs.size()) {
            throw new IllegalStateException("BF-615 BLOCKED: pair comparison count does not reconcile");
        }

        return new ComparisonReport(
            BF615_POLICY_ID,
            methodology.marketSnapshotId(),
            methodology.waiverSnapshotId(),
            methodology.leagueId(),
            methodology.sleeperOwnerId(),
            methodology.sleeperLeagueId(),
            methodology.rosterId(),
            candidates.candidateCount(),
            candidates.reviewableCandidateCount(),
            replacementPool.size(),
            allPairs.size(),
            aggregate.freeze(),
            List.copyOf(candidateComparisons),
            List.copyOf(allPairs),
            ComparisonState.COMPARISONS_EXECUTED_EVIDENCE_ONLY);
    }

    private PairComparison numericPair(
        CandidateEntry candidate,
        RosterEntry comparator,
        Map<String, PlayerSeasonProduction> candidateProduction,
        Map<String, PlayerSeasonProduction> rosterProduction,
        Map<String, Double> scoring) {
        Set<String> common = new TreeSet<>(candidateProduction.keySet());
        common.retainAll(rosterProduction.keySet());
        if (common.isEmpty()) {
            return new PairComparison(candidate, comparator, PairState.NO_COMMON_SOURCE,
                List.of(), List.of(), List.of());
        }

        Map<String, Double> candidatePerGame = new LinkedHashMap<>();
        Map<String, Double> rosterPerGame = new LinkedHashMap<>();
        List<SourceComparison> sources = new ArrayList<>();
        boolean invalidCommonSource = false;

        for (String source : common) {
            PlayerSeasonProduction candidateRow = candidateProduction.get(source);
            PlayerSeasonProduction rosterRow = rosterProduction.get(source);
            var candidateSubtotal = SleeperLiveWaiverCandidateRosterComparisonMethodology
                .supportedSubtotal(candidateRow, scoring);
            var rosterSubtotal = SleeperLiveWaiverCandidateRosterComparisonMethodology
                .supportedSubtotal(rosterRow, scoring);

            String sourceState;
            String direction = "UNRESOLVED";
            if (candidateSubtotal.supportedSubtotalPerGame() == null
                || rosterSubtotal.supportedSubtotalPerGame() == null) {
                invalidCommonSource = true;
                sourceState = "NONCOMPARABLE_GAMES_PLAYED";
            } else if (!candidateSubtotal.includedScoringKeys().equals(rosterSubtotal.includedScoringKeys())) {
                invalidCommonSource = true;
                sourceState = "SCHEMA_SUPPORT_MISMATCH";
            } else {
                sourceState = "COMPARABLE_COMMON_SOURCE";
                double candidateValue = candidateSubtotal.supportedSubtotalPerGame();
                double rosterValue = rosterSubtotal.supportedSubtotalPerGame();
                candidatePerGame.put(source, candidateValue);
                rosterPerGame.put(source, rosterValue);
                int cmp = Double.compare(candidateValue, rosterValue);
                direction = cmp > 0 ? "CANDIDATE_HIGHER" : cmp < 0 ? "ROSTER_HIGHER" : "TIED";
            }

            sources.add(new SourceComparison(
                source,
                candidateRow.asOfDate(),
                rosterRow.asOfDate(),
                candidateSubtotal.supportedSubtotalPerGame(),
                rosterSubtotal.supportedSubtotalPerGame(),
                candidateSubtotal.includedScoringKeys(),
                rosterSubtotal.includedScoringKeys(),
                candidateSubtotal.schemaExcludedScoringKeys(),
                rosterSubtotal.schemaExcludedScoringKeys(),
                sourceState,
                direction));
        }

        PairState state;
        if (invalidCommonSource) {
            state = PairState.SOURCE_DIRECTION_UNRESOLVED;
        } else {
            state = fromDirection(SleeperLiveWaiverCandidateRosterComparisonMethodology
                .directionAcrossCommonSources(candidatePerGame, rosterPerGame));
        }
        return new PairComparison(candidate, comparator, state,
            List.copyOf(common), List.copyOf(sources), List.of());
    }

    private static PairComparison nonNumericPair(
        CandidateEntry candidate,
        RosterEntry comparator,
        PairState state) {
        return new PairComparison(candidate, comparator, state, List.of(), List.of(), List.of());
    }

    private static PairState fromDirection(
        SleeperLiveWaiverCandidateRosterComparisonMethodology.PairDirection direction) {
        return switch (direction) {
            case CANDIDATE_DIRECTIONALLY_SUPPORTED -> PairState.CANDIDATE_DIRECTIONALLY_SUPPORTED;
            case ROSTER_DIRECTIONALLY_SUPPORTED -> PairState.ROSTER_DIRECTIONALLY_SUPPORTED;
            case TIED_ALL_COMMON_SOURCES -> PairState.TIED_ALL_COMMON_SOURCES;
            case SOURCE_DIRECTION_UNRESOLVED -> PairState.SOURCE_DIRECTION_UNRESOLVED;
            case NO_COMMON_SOURCE -> PairState.NO_COMMON_SOURCE;
        };
    }

    private ShortlistReport buildShortlist(ComparisonReport comparisons) {
        List<CandidateShortlistDecision> decisions = new ArrayList<>();
        List<ShortlistEntry> shortlist = new ArrayList<>();
        int historical = 0;
        int newcomer = 0;

        for (CandidateComparison candidate : comparisons.candidates()) {
            MutablePairCounts counts = new MutablePairCounts();
            for (PairComparison pair : candidate.pairs()) counts.observe(pair.state());
            PairCounts frozen = counts.freeze();
            CandidateShortlistState state;
            List<String> supportedComparators = candidate.pairs().stream()
                .filter(value -> value.state() == PairState.CANDIDATE_DIRECTIONALLY_SUPPORTED)
                .map(value -> value.roster().sleeperPlayerId())
                .distinct().sorted().toList();

            if (candidate.eligibleComparatorSleeperIds().isEmpty()) {
                state = CandidateShortlistState.NO_ELIGIBLE_REPLACEMENT_COMPARATOR;
            } else if (!candidate.candidate().priorProductionPresent()) {
                state = CandidateShortlistState.NEWCOMER_REVIEW_SHORTLIST;
                newcomer++;
            } else if (frozen.rosterDirectionallySupported() > 0) {
                state = CandidateShortlistState.HISTORICAL_ROSTER_DIRECTION_CONFLICT;
            } else if (frozen.sourceDirectionUnresolved() > 0) {
                state = CandidateShortlistState.HISTORICAL_SOURCE_DIRECTION_UNRESOLVED;
            } else if (frozen.candidateDirectionallySupported() > 0) {
                state = CandidateShortlistState.HISTORICAL_DIRECTIONAL_SHORTLIST;
                historical++;
            } else {
                state = CandidateShortlistState.HISTORICAL_NO_DIRECTIONAL_SUPPORT;
            }

            CandidateShortlistDecision decision = new CandidateShortlistDecision(
                candidate.candidate(), state, frozen,
                candidate.eligibleComparatorSleeperIds(), supportedComparators);
            decisions.add(decision);
            if (state == CandidateShortlistState.HISTORICAL_DIRECTIONAL_SHORTLIST
                || state == CandidateShortlistState.NEWCOMER_REVIEW_SHORTLIST) {
                shortlist.add(new ShortlistEntry(
                    candidate.candidate(),
                    state == CandidateShortlistState.HISTORICAL_DIRECTIONAL_SHORTLIST
                        ? ShortlistLane.HISTORICAL_DIRECTIONAL : ShortlistLane.NEWCOMER_REVIEW,
                    supportedComparators,
                    candidate.eligibleComparatorSleeperIds(),
                    frozen));
            }
        }

        shortlist.sort(Comparator
            .comparing((ShortlistEntry value) -> value.lane().ordinal())
            .thenComparing(value -> value.candidate().position())
            .thenComparing(value -> value.candidate().sleeperPlayerId()));
        decisions.sort(Comparator.comparing(value -> value.candidate().sleeperPlayerId()));

        if (historical + newcomer != shortlist.size()) {
            throw new IllegalStateException("BF-616 BLOCKED: shortlist lane counts do not reconcile");
        }
        for (ShortlistEntry entry : shortlist) {
            if (entry.lane() == ShortlistLane.HISTORICAL_DIRECTIONAL
                && entry.candidateSupportedComparatorSleeperIds().isEmpty()) {
                throw new IllegalStateException("BF-616 BLOCKED: historical shortlist entry has no candidate-supported comparator");
            }
            if (entry.lane() == ShortlistLane.NEWCOMER_REVIEW
                && entry.candidate().priorProductionPresent()) {
                throw new IllegalStateException("BF-616 BLOCKED: production-present candidate entered newcomer lane");
            }
        }

        return new ShortlistReport(
            BF616_POLICY_ID,
            comparisons.marketSnapshotId(),
            comparisons.leagueId(),
            comparisons.sleeperOwnerId(),
            comparisons.reviewableCandidateCount(),
            historical,
            newcomer,
            List.copyOf(decisions),
            List.copyOf(shortlist),
            ShortlistState.SHORTLIST_BUILT_EVIDENCE_ONLY);
    }

    private static DecisionReadinessReport authorizeDecisionMethod(
        ComparisonReport comparisons,
        ShortlistReport shortlist) {
        if (!comparisons.marketSnapshotId().equals(shortlist.marketSnapshotId())
            || !comparisons.leagueId().equals(shortlist.leagueId())
            || !comparisons.sleeperOwnerId().equals(shortlist.sleeperOwnerId())) {
            throw new IllegalStateException("BF-617 BLOCKED: BF-615/BF-616 lineage does not reconcile");
        }
        if (shortlist.decisions().size() != comparisons.reviewableCandidateCount()) {
            throw new IllegalStateException("BF-617 BLOCKED: BF-616 decision frame does not reconcile to BF-615 candidates");
        }

        Map<String, PairComparison> candidateSupportedPairs = new LinkedHashMap<>();
        for (PairComparison pair : comparisons.pairs()) {
            if (pair.state() == PairState.CANDIDATE_DIRECTIONALLY_SUPPORTED) {
                candidateSupportedPairs.put(pairKey(pair.candidate().sleeperPlayerId(), pair.roster().sleeperPlayerId()), pair);
            }
        }
        for (ShortlistEntry entry : shortlist.shortlist()) {
            if (entry.lane() == ShortlistLane.HISTORICAL_DIRECTIONAL) {
                for (String comparatorId : entry.candidateSupportedComparatorSleeperIds()) {
                    if (!candidateSupportedPairs.containsKey(pairKey(entry.candidate().sleeperPlayerId(), comparatorId))) {
                        throw new IllegalStateException("BF-617 BLOCKED: BF-616 historical shortlist comparator is not backed by BF-615 pair evidence");
                    }
                }
            } else if (entry.eligibleComparatorSleeperIds().isEmpty()) {
                throw new IllegalStateException("BF-617 BLOCKED: newcomer shortlist entry has no exact eligible replacement comparator");
            }
        }

        FinalDecisionAuthorizationState state = shortlist.shortlist().isEmpty()
            ? FinalDecisionAuthorizationState.NO_SHORTLIST_EVIDENCE_FOR_FINAL_DECISION_METHOD
            : FinalDecisionAuthorizationState.READY_FOR_FINAL_WAIVER_DECISION_METHOD;
        return new DecisionReadinessReport(
            BF617_POLICY_ID,
            comparisons.marketSnapshotId(),
            comparisons.leagueId(),
            comparisons.sleeperOwnerId(),
            shortlist.shortlist().size(),
            shortlist.historicalShortlistCount(),
            shortlist.newcomerShortlistCount(),
            shortlist.shortlist(),
            state);
    }

    private static Map<String, PlayerSeasonProduction> latest2025BySource(List<PlayerSeasonProduction> rows) {
        Objects.requireNonNull(rows, "production rows must not be null");
        Map<String, PlayerSeasonProduction> latest = new LinkedHashMap<>();
        for (PlayerSeasonProduction row : rows) {
            if (row.season() != PRODUCTION_SEASON) continue;
            PlayerSeasonProduction existing = latest.get(row.source());
            if (existing == null
                || row.asOfDate().isAfter(existing.asOfDate())
                || (row.asOfDate().equals(existing.asOfDate()) && row.id().compareTo(existing.id()) < 0)) {
                latest.put(row.source(), row);
            }
        }
        Map<String, PlayerSeasonProduction> sorted = new LinkedHashMap<>();
        latest.entrySet().stream().sorted(Map.Entry.comparingByKey()).forEach(entry -> sorted.put(entry.getKey(), entry.getValue()));
        return Collections.unmodifiableMap(sorted);
    }

    private static void validateMethodology(
        SleeperLiveWaiverCandidateRosterComparisonMethodology.MethodologyReport report,
        String leagueId,
        String ownerId) {
        Objects.requireNonNull(report, "BF-614 methodology report must not be null");
        if (!SleeperLiveWaiverCandidateRosterComparisonMethodology.POLICY_ID.equals(report.policyId())
            || report.state() != SleeperLiveWaiverCandidateRosterComparisonMethodology.MethodologyState.METHODOLOGY_FROZEN_NO_SELECTION) {
            throw new IllegalStateException("BF-615 BLOCKED: BF-614 methodology is not frozen");
        }
        if (!leagueId.equals(report.leagueId()) || !ownerId.equals(report.sleeperOwnerId())) {
            throw new IllegalStateException("BF-615 BLOCKED: BF-614 league/owner lineage differs from request");
        }
        if (!"EXACT_POSITION_ONLY".equals(report.positionRule())
            || !report.replacementSlots().equals(List.of("BENCH", "RESERVE"))) {
            throw new IllegalStateException("BF-615 BLOCKED: BF-614 position/replacement rules changed");
        }
    }

    private static void validateLineage(
        SleeperLiveWaiverCandidateRosterComparisonMethodology.MethodologyReport methodology,
        CandidateFrame candidates,
        RosterFrame roster,
        String leagueId,
        String ownerId) {
        if (!leagueId.equals(candidates.leagueId()) || !leagueId.equals(roster.leagueId())
            || !ownerId.equals(roster.sleeperOwnerId())) {
            throw new IllegalStateException("BF-615 BLOCKED: candidate/roster lineage differs from requested league/owner");
        }
        if (!methodology.marketSnapshotId().equals(candidates.marketSnapshotId())
            || !methodology.marketSnapshotId().equals(roster.marketSnapshotId())) {
            throw new IllegalStateException("BF-615 BLOCKED: BF-614/BF-609/BF-611 market snapshots differ");
        }
        if (methodology.rosterId() != roster.rosterId()
            || !methodology.sleeperLeagueId().equals(roster.sleeperLeagueId())) {
            throw new IllegalStateException("BF-615 BLOCKED: BF-614/BF-611 target roster lineage differs");
        }
        if (methodology.candidateCount() != candidates.candidateCount()
            || methodology.reviewableCandidateCount() != candidates.reviewableCandidateCount()
            || candidates.entries().size() != candidates.reviewableCandidateCount()) {
            throw new IllegalStateException("BF-615 BLOCKED: reviewable candidate counts do not reconcile");
        }
        if (methodology.targetPlayerCount() != roster.targetPlayerCount()
            || methodology.starterCount() != roster.starterCount()
            || methodology.benchCount() != roster.benchCount()
            || methodology.reserveCount() != roster.reserveCount()
            || methodology.taxiCount() != roster.taxiCount()
            || roster.entries().size() != roster.targetPlayerCount()) {
            throw new IllegalStateException("BF-615 BLOCKED: target roster counts do not reconcile");
        }
    }

    private static CandidateFrame candidateFrame(
        SleeperLiveWaiverPregameEvidenceReadinessAudit.ReadinessReport report) {
        List<CandidateEntry> reviewable = new ArrayList<>();
        for (var value : report.candidates()) {
            boolean withPrior = value.primaryStratum()
                == SleeperLiveWaiverPregameEvidenceReadinessAudit.PrimaryStratum.REVIEWABLE_WITH_PRIOR_PRODUCTION;
            boolean withoutPrior = value.primaryStratum()
                == SleeperLiveWaiverPregameEvidenceReadinessAudit.PrimaryStratum.REVIEWABLE_WITHOUT_PRIOR_PRODUCTION;
            if (!withPrior && !withoutPrior) continue;
            var dossier = value.dossier();
            var market = dossier.market();
            var availability = dossier.availability();
            reviewable.add(new CandidateEntry(
                market.sleeperPlayerId(), market.displayName(), market.position(), dossier.butlerPlayerId(), withPrior,
                market.addCount(), market.dropCount(), market.netAddAttention(), market.frameMembership(),
                availability.currentTeam(), availability.currentStatus(), availability.injuryStatus(),
                availability.depthChartPosition(), availability.depthChartOrder()));
        }
        reviewable.sort(Comparator.comparing(CandidateEntry::sleeperPlayerId));
        return new CandidateFrame(
            report.leagueId(), report.marketSnapshotId(), report.candidateCount(),
            reviewable.size(), List.copyOf(reviewable));
    }

    private static RosterFrame rosterFrame(
        SleeperLiveWaiverTargetRosterProductionComparabilityAudit.AuditReport report) {
        List<RosterEntry> entries = report.players().stream()
            .map(value -> new RosterEntry(
                value.target().sleeperPlayerId(), value.target().displayName(), value.target().position(),
                value.target().rosterSlot(), value.target().butlerPlayerId(),
                value.state() == SleeperLiveWaiverTargetRosterProductionComparabilityAudit.CoverageState.PRIOR_PRODUCTION_PRESENT))
            .sorted(Comparator.comparing(RosterEntry::sleeperPlayerId))
            .toList();
        return new RosterFrame(
            report.leagueId(), report.marketSnapshotId(), report.waiverSnapshotId(), report.sleeperLeagueId(),
            report.sleeperOwnerId(), report.rosterId(), report.targetPlayerCount(),
            report.starterCount(), report.benchCount(), report.reserveCount(), report.taxiCount(), entries);
    }

    private static String pairKey(String candidateId, String rosterId) {
        return candidateId + "\u0000" + rosterId;
    }

    private static String requireText(String value, String field) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(field + " must not be blank");
        return value.trim();
    }

    @FunctionalInterface
    interface MethodologySource {
        SleeperLiveWaiverCandidateRosterComparisonMethodology.MethodologyReport audit(String leagueId, String ownerId)
            throws SQLException, IOException, InterruptedException;
    }

    @FunctionalInterface
    interface CandidateSource {
        CandidateFrame audit(String leagueId) throws SQLException;
    }

    @FunctionalInterface
    interface RosterSource {
        RosterFrame audit(String leagueId, String ownerId) throws SQLException, IOException, InterruptedException;
    }

    @FunctionalInterface
    interface ProductionSource {
        List<PlayerSeasonProduction> load(String butlerPlayerId) throws SQLException;
    }

    record CandidateFrame(String leagueId, String marketSnapshotId, int candidateCount,
                          int reviewableCandidateCount, List<CandidateEntry> entries) {
        CandidateFrame { entries = List.copyOf(Objects.requireNonNull(entries)); }
    }

    record RosterFrame(String leagueId, String marketSnapshotId, String waiverSnapshotId, String sleeperLeagueId,
                       String sleeperOwnerId, int rosterId, int targetPlayerCount, int starterCount,
                       int benchCount, int reserveCount, int taxiCount, List<RosterEntry> entries) {
        RosterFrame { entries = List.copyOf(Objects.requireNonNull(entries)); }
    }

    public record CandidateEntry(
        String sleeperPlayerId, String displayName, String position, String butlerPlayerId,
        boolean priorProductionPresent, int addCount, int dropCount, int netAddAttention,
        String frameMembership, String currentTeam, String currentStatus, String injuryStatus,
        String depthChartPosition, Integer depthChartOrder) {}

    public record RosterEntry(
        String sleeperPlayerId, String displayName, String position, String rosterSlot,
        String butlerPlayerId, boolean priorProductionPresent) {}

    public record SourceComparison(
        String source,
        LocalDate candidateAsOf,
        LocalDate rosterAsOf,
        Double candidateSupportedSubtotalPerGame,
        Double rosterSupportedSubtotalPerGame,
        List<String> candidateIncludedScoringKeys,
        List<String> rosterIncludedScoringKeys,
        List<String> candidateSchemaExcludedScoringKeys,
        List<String> rosterSchemaExcludedScoringKeys,
        String sourceState,
        String direction) {
        public SourceComparison {
            candidateIncludedScoringKeys = List.copyOf(candidateIncludedScoringKeys);
            rosterIncludedScoringKeys = List.copyOf(rosterIncludedScoringKeys);
            candidateSchemaExcludedScoringKeys = List.copyOf(candidateSchemaExcludedScoringKeys);
            rosterSchemaExcludedScoringKeys = List.copyOf(rosterSchemaExcludedScoringKeys);
        }
    }

    public enum PairState {
        CANDIDATE_DIRECTIONALLY_SUPPORTED,
        ROSTER_DIRECTIONALLY_SUPPORTED,
        TIED_ALL_COMMON_SOURCES,
        SOURCE_DIRECTION_UNRESOLVED,
        NO_COMMON_SOURCE,
        TARGET_PRIOR_PRODUCTION_PROTECTED,
        NEWCOMER_NONNUMERIC
    }

    public record PairComparison(
        CandidateEntry candidate,
        RosterEntry roster,
        PairState state,
        List<String> commonSources,
        List<SourceComparison> sourceComparisons,
        List<String> notes) {
        public PairComparison {
            commonSources = List.copyOf(commonSources);
            sourceComparisons = List.copyOf(sourceComparisons);
            notes = List.copyOf(notes);
        }
    }

    public enum CandidateExecutionState {
        HISTORICAL_PAIRWISE_COMPARISON_EXECUTED,
        NEWCOMER_REVIEW_EXECUTED_NONNUMERIC,
        NO_ELIGIBLE_REPLACEMENT_COMPARATOR
    }

    public record CandidateComparison(
        CandidateEntry candidate,
        CandidateExecutionState executionState,
        List<String> eligibleComparatorSleeperIds,
        List<PairComparison> pairs) {
        public CandidateComparison {
            eligibleComparatorSleeperIds = List.copyOf(eligibleComparatorSleeperIds);
            pairs = List.copyOf(pairs);
        }
    }

    public record PairCounts(
        int candidateDirectionallySupported,
        int rosterDirectionallySupported,
        int tiedAllCommonSources,
        int sourceDirectionUnresolved,
        int noCommonSource,
        int targetPriorProductionProtected,
        int newcomerNonnumeric) {
        public int total() {
            return candidateDirectionallySupported + rosterDirectionallySupported + tiedAllCommonSources
                + sourceDirectionUnresolved + noCommonSource + targetPriorProductionProtected + newcomerNonnumeric;
        }
    }

    public enum ComparisonState { COMPARISONS_EXECUTED_EVIDENCE_ONLY }

    public record ComparisonReport(
        String policyId,
        String marketSnapshotId,
        String waiverSnapshotId,
        String leagueId,
        String sleeperOwnerId,
        String sleeperLeagueId,
        int rosterId,
        int candidateCount,
        int reviewableCandidateCount,
        int replacementPoolCount,
        int pairCount,
        PairCounts pairCounts,
        List<CandidateComparison> candidates,
        List<PairComparison> pairs,
        ComparisonState state) {
        public ComparisonReport {
            if (!BF615_POLICY_ID.equals(policyId)) throw new IllegalArgumentException("unexpected BF-615 policy");
            candidates = List.copyOf(candidates);
            pairs = List.copyOf(pairs);
            if (candidateCount < reviewableCandidateCount || reviewableCandidateCount != candidates.size()) {
                throw new IllegalArgumentException("BF-615 candidate counts must reconcile");
            }
            if (pairCount != pairs.size() || pairCounts.total() != pairCount) {
                throw new IllegalArgumentException("BF-615 pair counts must reconcile");
            }
        }
    }

    public enum CandidateShortlistState {
        HISTORICAL_DIRECTIONAL_SHORTLIST,
        NEWCOMER_REVIEW_SHORTLIST,
        NO_ELIGIBLE_REPLACEMENT_COMPARATOR,
        HISTORICAL_ROSTER_DIRECTION_CONFLICT,
        HISTORICAL_SOURCE_DIRECTION_UNRESOLVED,
        HISTORICAL_NO_DIRECTIONAL_SUPPORT
    }

    public enum ShortlistLane { HISTORICAL_DIRECTIONAL, NEWCOMER_REVIEW }

    public record CandidateShortlistDecision(
        CandidateEntry candidate,
        CandidateShortlistState state,
        PairCounts pairCounts,
        List<String> eligibleComparatorSleeperIds,
        List<String> candidateSupportedComparatorSleeperIds) {
        public CandidateShortlistDecision {
            eligibleComparatorSleeperIds = List.copyOf(eligibleComparatorSleeperIds);
            candidateSupportedComparatorSleeperIds = List.copyOf(candidateSupportedComparatorSleeperIds);
        }
    }

    public record ShortlistEntry(
        CandidateEntry candidate,
        ShortlistLane lane,
        List<String> candidateSupportedComparatorSleeperIds,
        List<String> eligibleComparatorSleeperIds,
        PairCounts pairCounts) {
        public ShortlistEntry {
            candidateSupportedComparatorSleeperIds = List.copyOf(candidateSupportedComparatorSleeperIds);
            eligibleComparatorSleeperIds = List.copyOf(eligibleComparatorSleeperIds);
        }
    }

    public enum ShortlistState { SHORTLIST_BUILT_EVIDENCE_ONLY }

    public record ShortlistReport(
        String policyId,
        String marketSnapshotId,
        String leagueId,
        String sleeperOwnerId,
        int reviewableCandidateCount,
        int historicalShortlistCount,
        int newcomerShortlistCount,
        List<CandidateShortlistDecision> decisions,
        List<ShortlistEntry> shortlist,
        ShortlistState state) {
        public ShortlistReport {
            if (!BF616_POLICY_ID.equals(policyId)) throw new IllegalArgumentException("unexpected BF-616 policy");
            decisions = List.copyOf(decisions);
            shortlist = List.copyOf(shortlist);
            if (decisions.size() != reviewableCandidateCount) throw new IllegalArgumentException("BF-616 decisions must reconcile");
            if (historicalShortlistCount + newcomerShortlistCount != shortlist.size()) {
                throw new IllegalArgumentException("BF-616 shortlist counts must reconcile");
            }
        }
    }

    public enum FinalDecisionAuthorizationState {
        READY_FOR_FINAL_WAIVER_DECISION_METHOD,
        NO_SHORTLIST_EVIDENCE_FOR_FINAL_DECISION_METHOD
    }

    public record DecisionReadinessReport(
        String policyId,
        String marketSnapshotId,
        String leagueId,
        String sleeperOwnerId,
        int shortlistCount,
        int historicalShortlistCount,
        int newcomerShortlistCount,
        List<ShortlistEntry> shortlist,
        FinalDecisionAuthorizationState state) {
        public DecisionReadinessReport {
            if (!BF617_POLICY_ID.equals(policyId)) throw new IllegalArgumentException("unexpected BF-617 policy");
            shortlist = List.copyOf(shortlist);
            if (shortlistCount != shortlist.size()
                || historicalShortlistCount + newcomerShortlistCount != shortlistCount) {
                throw new IllegalArgumentException("BF-617 shortlist counts must reconcile");
            }
        }
    }

    public record BundleReport(
        SleeperLiveWaiverCandidateRosterComparisonMethodology.MethodologyReport methodology,
        ComparisonReport comparisons,
        ShortlistReport shortlist,
        DecisionReadinessReport decisionReadiness) {
        public BundleReport {
            Objects.requireNonNull(methodology);
            Objects.requireNonNull(comparisons);
            Objects.requireNonNull(shortlist);
            Objects.requireNonNull(decisionReadiness);
        }
    }

    private static final class MutablePairCounts {
        private int candidate;
        private int roster;
        private int tied;
        private int unresolved;
        private int noCommon;
        private int protectedTarget;
        private int newcomer;

        private void observe(PairState state) {
            switch (state) {
                case CANDIDATE_DIRECTIONALLY_SUPPORTED -> candidate++;
                case ROSTER_DIRECTIONALLY_SUPPORTED -> roster++;
                case TIED_ALL_COMMON_SOURCES -> tied++;
                case SOURCE_DIRECTION_UNRESOLVED -> unresolved++;
                case NO_COMMON_SOURCE -> noCommon++;
                case TARGET_PRIOR_PRODUCTION_PROTECTED -> protectedTarget++;
                case NEWCOMER_NONNUMERIC -> newcomer++;
            }
        }

        private PairCounts freeze() {
            return new PairCounts(candidate, roster, tied, unresolved, noCommon, protectedTarget, newcomer);
        }
    }
}
