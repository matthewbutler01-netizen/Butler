package io.butler.bet.sleeper;

import io.butler.bet.data.Database;
import io.butler.bet.data.LeagueScoringSettingsRepository;
import io.butler.bet.domain.PlayerSeasonProduction;

import java.io.IOException;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;

/** BF-614 frozen governed methodology for the first candidate-vs-roster waiver comparison lane. */
public final class SleeperLiveWaiverCandidateRosterComparisonMethodology {
    public static final String POLICY_ID =
        "sleeper-live-waiver-candidate-roster-comparison-methodology-v1-bf613-same-position-common-source-supported-scoring-no-selection";

    private static final Set<String> CORE_SCORING_KEYS = Set.of(
        "pass_yd", "pass_td", "rush_yd", "rush_td", "rec", "rec_yd", "rec_td");
    private static final Set<String> REPLACEMENT_SLOTS = Set.of("BENCH", "RESERVE");
    private static final Map<String, ScoringDimension> SUPPORTED_DIMENSIONS = supportedDimensions();

    private final ReadinessSource readinessSource;
    private final ScoringSettingsSource scoringSettingsSource;

    public SleeperLiveWaiverCandidateRosterComparisonMethodology(Database database) {
        Objects.requireNonNull(database, "database must not be null");
        this.readinessSource = (leagueId, ownerId) -> readinessFrame(
            new SleeperLiveWaiverCandidateRosterComparisonReadinessAudit(database).audit(leagueId, ownerId));
        this.scoringSettingsSource = leagueId -> new LeagueScoringSettingsRepository(database).findByLeagueId(leagueId);
    }

    SleeperLiveWaiverCandidateRosterComparisonMethodology(
        ReadinessSource readinessSource,
        ScoringSettingsSource scoringSettingsSource) {
        this.readinessSource = Objects.requireNonNull(readinessSource, "readinessSource must not be null");
        this.scoringSettingsSource = Objects.requireNonNull(scoringSettingsSource, "scoringSettingsSource must not be null");
    }

    public MethodologyReport audit(String leagueId, String sleeperOwnerId)
        throws SQLException, IOException, InterruptedException {
        String normalizedLeagueId = requireText(leagueId, "leagueId");
        String normalizedOwnerId = requireText(sleeperOwnerId, "sleeperOwnerId");
        ReadinessFrame readiness = readinessSource.audit(normalizedLeagueId, normalizedOwnerId);
        validateReadiness(readiness, normalizedLeagueId, normalizedOwnerId);

        Map<String, Double> scoring = scoringSettingsSource.load(normalizedLeagueId);
        if (scoring == null || scoring.isEmpty()) {
            throw new IllegalStateException("BF-614 BLOCKED: exact persisted league scoring settings are missing");
        }
        Map<String, Double> normalizedScoring = sortedFiniteScoring(scoring);
        Set<String> missingCore = new TreeSet<>(CORE_SCORING_KEYS);
        missingCore.removeAll(normalizedScoring.keySet());
        if (!missingCore.isEmpty()) {
            throw new IllegalStateException("BF-614 BLOCKED: core supported league scoring keys are missing: " + missingCore);
        }

        Map<String, SupportedScoringRule> supported = new LinkedHashMap<>();
        Map<String, Double> unsupported = new LinkedHashMap<>();
        for (var entry : normalizedScoring.entrySet()) {
            ScoringDimension dimension = SUPPORTED_DIMENSIONS.get(entry.getKey());
            if (dimension == null) {
                unsupported.put(entry.getKey(), entry.getValue());
            } else {
                supported.put(entry.getKey(), new SupportedScoringRule(
                    entry.getKey(), dimension.label, entry.getValue(), dimension.minimumRawSchemaVersion));
            }
        }

        if (readiness.benchCount() + readiness.reserveCount() <= 0) {
            throw new IllegalStateException("BF-614 BLOCKED: target roster has no BENCH/RESERVE replacement comparators");
        }

        return new MethodologyReport(
            POLICY_ID,
            normalizedLeagueId,
            normalizedOwnerId,
            readiness.marketSnapshotId(),
            readiness.waiverSnapshotId(),
            readiness.sleeperLeagueId(),
            readiness.providerSeason(),
            readiness.providerStatus(),
            readiness.providerLeg(),
            readiness.rosterId(),
            readiness.candidateCount(),
            readiness.reviewableCandidateCount(),
            readiness.reviewableWithPriorProduction(),
            readiness.reviewableWithoutPriorProduction(),
            readiness.targetPlayerCount(),
            readiness.starterCount(),
            readiness.benchCount(),
            readiness.reserveCount(),
            readiness.taxiCount(),
            readiness.targetPriorProductionPresent(),
            readiness.targetPriorProductionMissing(),
            List.copyOf(readiness.protectedMissingProduction()),
            Collections.unmodifiableMap(new LinkedHashMap<>(normalizedScoring)),
            Collections.unmodifiableMap(supported),
            Collections.unmodifiableMap(unsupported),
            List.copyOf(new TreeSet<>(CORE_SCORING_KEYS)),
            List.copyOf(new TreeSet<>(REPLACEMENT_SLOTS)),
            "EXACT_POSITION_ONLY",
            "COMMON_2025_SOURCE_ONLY_ALL_COMMON_SOURCES_MUST_AGREE",
            "SUPPORTED_LEAGUE_SCORING_SUBTOTAL_PER_GAME_NOT_FULL_FANTASY_POINTS",
            "NEWCOMER_REVIEW_NONNUMERIC",
            "MISSING_TARGET_PRIOR_PRODUCTION_PROTECTED_UNRESOLVED",
            "MARKET_TEAM_STATUS_INJURY_DEPTH_DESCRIPTIVE_ONLY_NOT_ARITHMETIC",
            MethodologyState.METHODOLOGY_FROZEN_NO_SELECTION);
    }

    /** Calculates only scoring dimensions represented by this raw row; schema-ineligible dimensions are explicit exclusions. */
    public static SupportedSubtotal supportedSubtotal(
        PlayerSeasonProduction production,
        Map<String, Double> leagueScoringSettings) {
        Objects.requireNonNull(production, "production must not be null");
        Map<String, Double> scoring = sortedFiniteScoring(leagueScoringSettings);
        if (production.gamesPlayed() <= 0) {
            return new SupportedSubtotal(null, null, List.of(), List.of(), "GAMES_PLAYED_NONPOSITIVE");
        }

        double total = 0.0d;
        List<String> included = new ArrayList<>();
        List<String> schemaExcluded = new ArrayList<>();
        for (var entry : scoring.entrySet()) {
            ScoringDimension dimension = SUPPORTED_DIMENSIONS.get(entry.getKey());
            if (dimension == null) continue;
            if (production.rawScoringSchemaVersion() < dimension.minimumRawSchemaVersion) {
                schemaExcluded.add(entry.getKey());
                continue;
            }
            total += dimension.value(production) * entry.getValue();
            included.add(entry.getKey());
        }
        return new SupportedSubtotal(
            total,
            total / production.gamesPlayed(),
            List.copyOf(included),
            List.copyOf(schemaExcluded),
            "COMPARABLE_SUPPORTED_SUBTOTAL");
    }

    /** First live waiver replacement pool: BENCH and RESERVE only. */
    public static boolean eligibleReplacementSlot(String rosterSlot) {
        return rosterSlot != null && REPLACEMENT_SLOTS.contains(rosterSlot.trim().toUpperCase());
    }

    /** First live waiver method permits exact-position comparisons only. */
    public static boolean samePosition(String candidatePosition, String rosterPosition) {
        if (candidatePosition == null || rosterPosition == null) return false;
        return candidatePosition.trim().equalsIgnoreCase(rosterPosition.trim());
    }

    /**
     * Resolves pair direction only when all common sources agree. Values are supported-subtotal-per-game,
     * not full fantasy points and not a blended player score.
     */
    public static PairDirection directionAcrossCommonSources(
        Map<String, Double> candidatePerGameBySource,
        Map<String, Double> rosterPerGameBySource) {
        Objects.requireNonNull(candidatePerGameBySource, "candidate source map must not be null");
        Objects.requireNonNull(rosterPerGameBySource, "roster source map must not be null");
        Set<String> common = new TreeSet<>(candidatePerGameBySource.keySet());
        common.retainAll(rosterPerGameBySource.keySet());
        if (common.isEmpty()) return PairDirection.NO_COMMON_SOURCE;

        boolean candidateHigher = false;
        boolean rosterHigher = false;
        boolean tied = false;
        for (String source : common) {
            Double candidate = requireFinite(candidatePerGameBySource.get(source), "candidate value for " + source);
            Double roster = requireFinite(rosterPerGameBySource.get(source), "roster value for " + source);
            int comparison = Double.compare(candidate, roster);
            if (comparison > 0) candidateHigher = true;
            else if (comparison < 0) rosterHigher = true;
            else tied = true;
        }
        if (candidateHigher && !rosterHigher && !tied) return PairDirection.CANDIDATE_DIRECTIONALLY_SUPPORTED;
        if (rosterHigher && !candidateHigher && !tied) return PairDirection.ROSTER_DIRECTIONALLY_SUPPORTED;
        if (tied && !candidateHigher && !rosterHigher) return PairDirection.TIED_ALL_COMMON_SOURCES;
        return PairDirection.SOURCE_DIRECTION_UNRESOLVED;
    }

    private static void validateReadiness(ReadinessFrame frame, String leagueId, String ownerId) {
        Objects.requireNonNull(frame, "BF-613 readiness frame must not be null");
        if (frame.state() != SleeperLiveWaiverCandidateRosterComparisonReadinessAudit.AuthorizationState.READY_FOR_COMPARISON_METHODOLOGY) {
            throw new IllegalStateException("BF-614 BLOCKED: BF-613 did not authorize comparison methodology");
        }
        if (!leagueId.equals(frame.leagueId()) || !ownerId.equals(frame.sleeperOwnerId())) {
            throw new IllegalStateException("BF-614 BLOCKED: BF-613 league/owner lineage differs from requested target");
        }
        if (frame.providerSeason() != 2026 || !"in_season".equals(frame.providerStatus())) {
            throw new IllegalStateException("BF-614 BLOCKED: BF-613 target frame is not in-season 2026");
        }
        if (frame.reviewableCandidateCount() <= 0
            || frame.reviewableWithPriorProduction() + frame.reviewableWithoutPriorProduction() != frame.reviewableCandidateCount()) {
            throw new IllegalStateException("BF-614 BLOCKED: BF-613 reviewable candidate partition does not reconcile");
        }
        if (frame.starterCount() + frame.benchCount() + frame.reserveCount() + frame.taxiCount() != frame.targetPlayerCount()
            || frame.targetPriorProductionPresent() + frame.targetPriorProductionMissing() != frame.targetPlayerCount()) {
            throw new IllegalStateException("BF-614 BLOCKED: BF-613 target-roster partition does not reconcile");
        }
        if (frame.protectedMissingProduction().size() != frame.targetPriorProductionMissing()) {
            throw new IllegalStateException("BF-614 BLOCKED: BF-613 protected missing-production identities do not reconcile");
        }
    }

    private static ReadinessFrame readinessFrame(
        SleeperLiveWaiverCandidateRosterComparisonReadinessAudit.AuditReport report) {
        Objects.requireNonNull(report, "BF-613 report must not be null");
        List<ProtectedMissingProduction> missing = report.missingRosterProduction().stream()
            .map(value -> new ProtectedMissingProduction(
                value.sleeperPlayerId(), value.displayName(), value.position(), value.rosterSlot()))
            .toList();
        return new ReadinessFrame(
            report.leagueId(), report.sleeperOwnerId(), report.marketSnapshotId(), report.waiverSnapshotId(),
            report.sleeperLeagueId(), report.providerSeason(), report.providerStatus(), report.providerLeg(), report.rosterId(),
            report.candidateCount(), report.reviewableCandidateCount(),
            report.reviewableWithPriorProduction(), report.reviewableWithoutPriorProduction(),
            report.targetPlayerCount(), report.starterCount(), report.benchCount(), report.reserveCount(), report.taxiCount(),
            report.targetPriorProductionPresent(), report.targetPriorProductionMissing(), missing, report.state());
    }

    private static Map<String, Double> sortedFiniteScoring(Map<String, Double> settings) {
        Objects.requireNonNull(settings, "league scoring settings must not be null");
        Map<String, Double> result = new LinkedHashMap<>();
        settings.entrySet().stream().sorted(Map.Entry.comparingByKey()).forEach(entry -> {
            String key = requireText(entry.getKey(), "scoring key");
            Double value = requireFinite(entry.getValue(), "scoring value for " + key);
            result.put(key, value);
        });
        return Collections.unmodifiableMap(result);
    }

    private static Double requireFinite(Double value, String field) {
        if (value == null || !Double.isFinite(value)) throw new IllegalArgumentException(field + " must be finite");
        return value;
    }

    private static Map<String, ScoringDimension> supportedDimensions() {
        Map<String, ScoringDimension> result = new LinkedHashMap<>();
        result.put("pass_yd", new ScoringDimension("passing_yards", 1, PlayerSeasonProduction::passingYards));
        result.put("pass_td", new ScoringDimension("passing_touchdowns", 1, PlayerSeasonProduction::passingTouchdowns));
        result.put("pass_int", new ScoringDimension("interceptions", 1, PlayerSeasonProduction::interceptions));
        result.put("rush_yd", new ScoringDimension("rushing_yards", 1, PlayerSeasonProduction::rushingYards));
        result.put("rush_td", new ScoringDimension("rushing_touchdowns", 1, PlayerSeasonProduction::rushingTouchdowns));
        result.put("rec", new ScoringDimension("receptions", 1, PlayerSeasonProduction::receptions));
        result.put("rec_yd", new ScoringDimension("receiving_yards", 1, PlayerSeasonProduction::receivingYards));
        result.put("rec_td", new ScoringDimension("receiving_touchdowns", 1, PlayerSeasonProduction::receivingTouchdowns));
        result.put("fum_lost", new ScoringDimension("fumbles_lost", 1, PlayerSeasonProduction::fumblesLost));
        result.put("pass_2pt", new ScoringDimension("passing_two_point_conversions", 2, PlayerSeasonProduction::passingTwoPointConversions));
        result.put("rush_2pt", new ScoringDimension("rushing_two_point_conversions", 2, PlayerSeasonProduction::rushingTwoPointConversions));
        result.put("rec_2pt", new ScoringDimension("receiving_two_point_conversions", 2, PlayerSeasonProduction::receivingTwoPointConversions));
        result.put("fum_rec_td", new ScoringDimension("fumble_recovery_touchdowns", 2, PlayerSeasonProduction::fumbleRecoveryTouchdowns));
        result.put("st_td", new ScoringDimension("special_teams_touchdowns", 2, PlayerSeasonProduction::specialTeamsTouchdowns));
        return Collections.unmodifiableMap(result);
    }

    private static String requireText(String value, String field) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(field + " must not be blank");
        return value.trim();
    }

    @FunctionalInterface
    interface ReadinessSource {
        ReadinessFrame audit(String leagueId, String sleeperOwnerId) throws SQLException, IOException, InterruptedException;
    }

    @FunctionalInterface
    interface ScoringSettingsSource {
        Map<String, Double> load(String leagueId) throws SQLException;
    }

    record ReadinessFrame(
        String leagueId,
        String sleeperOwnerId,
        String marketSnapshotId,
        String waiverSnapshotId,
        String sleeperLeagueId,
        int providerSeason,
        String providerStatus,
        Integer providerLeg,
        int rosterId,
        int candidateCount,
        int reviewableCandidateCount,
        int reviewableWithPriorProduction,
        int reviewableWithoutPriorProduction,
        int targetPlayerCount,
        int starterCount,
        int benchCount,
        int reserveCount,
        int taxiCount,
        int targetPriorProductionPresent,
        int targetPriorProductionMissing,
        List<ProtectedMissingProduction> protectedMissingProduction,
        SleeperLiveWaiverCandidateRosterComparisonReadinessAudit.AuthorizationState state) {
        ReadinessFrame {
            protectedMissingProduction = List.copyOf(Objects.requireNonNull(protectedMissingProduction));
        }
    }

    public record ProtectedMissingProduction(
        String sleeperPlayerId,
        String displayName,
        String position,
        String rosterSlot) {}

    public record SupportedScoringRule(
        String scoringKey,
        String rawDimension,
        double pointsPerUnit,
        int minimumRawSchemaVersion) {}

    public record SupportedSubtotal(
        Double supportedSubtotal,
        Double supportedSubtotalPerGame,
        List<String> includedScoringKeys,
        List<String> schemaExcludedScoringKeys,
        String state) {
        public SupportedSubtotal {
            includedScoringKeys = List.copyOf(Objects.requireNonNull(includedScoringKeys));
            schemaExcludedScoringKeys = List.copyOf(Objects.requireNonNull(schemaExcludedScoringKeys));
        }
    }

    public enum PairDirection {
        CANDIDATE_DIRECTIONALLY_SUPPORTED,
        ROSTER_DIRECTIONALLY_SUPPORTED,
        TIED_ALL_COMMON_SOURCES,
        SOURCE_DIRECTION_UNRESOLVED,
        NO_COMMON_SOURCE
    }

    public enum MethodologyState {
        METHODOLOGY_FROZEN_NO_SELECTION
    }

    public record MethodologyReport(
        String policyId,
        String leagueId,
        String sleeperOwnerId,
        String marketSnapshotId,
        String waiverSnapshotId,
        String sleeperLeagueId,
        int providerSeason,
        String providerStatus,
        Integer providerLeg,
        int rosterId,
        int candidateCount,
        int reviewableCandidateCount,
        int reviewableWithPriorProduction,
        int reviewableWithoutPriorProduction,
        int targetPlayerCount,
        int starterCount,
        int benchCount,
        int reserveCount,
        int taxiCount,
        int targetPriorProductionPresent,
        int targetPriorProductionMissing,
        List<ProtectedMissingProduction> protectedMissingProduction,
        Map<String, Double> exactLeagueScoringSettings,
        Map<String, SupportedScoringRule> supportedScoringRules,
        Map<String, Double> unsupportedScoringSettings,
        List<String> requiredCoreScoringKeys,
        List<String> replacementSlots,
        String positionRule,
        String sourceRule,
        String numericRule,
        String newcomerRule,
        String missingRosterProductionRule,
        String descriptiveContextRule,
        MethodologyState state) {
        public MethodologyReport {
            if (!POLICY_ID.equals(policyId)) throw new IllegalArgumentException("unexpected BF-614 policyId");
            protectedMissingProduction = List.copyOf(Objects.requireNonNull(protectedMissingProduction));
            exactLeagueScoringSettings = Collections.unmodifiableMap(new LinkedHashMap<>(Objects.requireNonNull(exactLeagueScoringSettings)));
            supportedScoringRules = Collections.unmodifiableMap(new LinkedHashMap<>(Objects.requireNonNull(supportedScoringRules)));
            unsupportedScoringSettings = Collections.unmodifiableMap(new LinkedHashMap<>(Objects.requireNonNull(unsupportedScoringSettings)));
            requiredCoreScoringKeys = List.copyOf(Objects.requireNonNull(requiredCoreScoringKeys));
            replacementSlots = List.copyOf(Objects.requireNonNull(replacementSlots));
            Objects.requireNonNull(state, "state must not be null");
        }
    }

    private record ScoringDimension(String label, int minimumRawSchemaVersion, IntExtractor extractor) {
        int value(PlayerSeasonProduction production) { return extractor.value(production); }
    }

    @FunctionalInterface
    private interface IntExtractor {
        int value(PlayerSeasonProduction production);
    }
}
