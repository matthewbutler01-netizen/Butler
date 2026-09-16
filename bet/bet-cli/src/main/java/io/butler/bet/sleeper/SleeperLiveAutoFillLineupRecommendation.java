package io.butler.bet.sleeper;

import io.butler.bet.data.Database;
import io.butler.bet.data.LeagueScoringSettingsRepository;
import io.butler.bet.data.PlayerFantasyPositionRepository;
import io.butler.bet.integration.SleeperWeeklyProjectionProvider;
import io.butler.bet.intelligence.AutoFillLineupOptimizer;

import java.io.IOException;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** BF-822 read-only composition of BF-610 live roster evidence and independent weekly projections. */
public final class SleeperLiveAutoFillLineupRecommendation {
    public static final String POLICY_ID =
        "sleeper-live-autofill-v2-bf610-provider-independent-weekly-projection-preview-only";

    private final Database database;
    private final ProjectionSource projectionSource;

    public SleeperLiveAutoFillLineupRecommendation(Database database) {
        this(database, new SleeperWeeklyProjectionProvider()::load);
    }

    SleeperLiveAutoFillLineupRecommendation(Database database, ProjectionSource projectionSource) {
        this.database = Objects.requireNonNull(database, "database must not be null");
        this.projectionSource = Objects.requireNonNull(projectionSource, "projectionSource must not be null");
    }

    public RecommendationReport recommend(SleeperLiveWaiverTargetRosterContextAudit.AuditReport roster)
        throws java.sql.SQLException {
        Objects.requireNonNull(roster, "roster must not be null");
        if (!SleeperLiveWaiverTargetRosterContextAudit.POLICY_ID.equals(roster.policyId())) {
            throw new IllegalArgumentException("AutoFill requires an exact BF-610 roster report");
        }
        if (roster.providerLeg() == null || roster.providerLeg() <= 0) {
            return RecommendationReport.unavailable(
                roster.providerSeason(), roster.providerLeg(), null,
                "Sleeper did not provide a current scoring week for AutoFill.");
        }

        Map<String, Double> scoringSettings = new LeagueScoringSettingsRepository(database)
            .findByLeagueId(roster.leagueId());
        Double receptionPoints = scoringSettings.get("rec");
        final SleeperWeeklyProjectionProvider.ScoringBasis scoring;
        try {
            scoring = SleeperWeeklyProjectionProvider.ScoringBasis.fromReceptionPoints(receptionPoints);
        } catch (IllegalStateException e) {
            return RecommendationReport.unavailable(
                roster.providerSeason(), roster.providerLeg(), null, e.getMessage());
        }

        final SleeperWeeklyProjectionProvider.ProjectionSnapshot snapshot;
        try {
            snapshot = projectionSource.load(roster.providerSeason(), roster.providerLeg(), scoring);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return RecommendationReport.unavailable(
                roster.providerSeason(), roster.providerLeg(), scoring,
                "Current weekly projection evidence request was interrupted.");
        } catch (IOException | IllegalStateException e) {
            return RecommendationReport.unavailable(
                roster.providerSeason(), roster.providerLeg(), scoring,
                "Current weekly projection evidence is unavailable: " + safeMessage(e));
        }

        if (snapshot.season() != roster.providerSeason() || snapshot.week() != roster.providerLeg()
            || snapshot.scoring() != scoring) {
            return RecommendationReport.unavailable(
                roster.providerSeason(), roster.providerLeg(), scoring,
                "Current weekly projection evidence does not match the live Sleeper season/week/scoring frame.");
        }

        PlayerFantasyPositionRepository eligibilityRepository = new PlayerFantasyPositionRepository(database);
        List<AutoFillLineupOptimizer.RosterPlayer> optimizerRoster = new ArrayList<>();
        Map<String, BigDecimal> projectionsBySleeperId = new LinkedHashMap<>();
        int mappedActivePlayers = 0;

        Map<String, SleeperWeeklyProjectionProvider.Projection> projectionBySleeperId = new LinkedHashMap<>();
        for (var projection : snapshot.projections()) {
            if (projectionBySleeperId.putIfAbsent(projection.sleeperPlayerId(), projection) != null) {
                return RecommendationReport.unavailable(
                    roster.providerSeason(), roster.providerLeg(), scoring,
                    "Weekly projection evidence contains a duplicate Sleeper player id; Butler will not guess.");
            }
        }

        for (var target : roster.targetPlayers()) {
            AutoFillLineupOptimizer.RosterSlot rosterSlot = rosterSlot(target.rosterSlot());
            if (rosterSlot == AutoFillLineupOptimizer.RosterSlot.RESERVE
                || rosterSlot == AutoFillLineupOptimizer.RosterSlot.TAXI) {
                continue;
            }
            if (target.butlerPlayerId() == null || !"EXACT_CANONICAL".equals(target.mappingState())) {
                return RecommendationReport.unavailable(
                    roster.providerSeason(), roster.providerLeg(), scoring,
                    "AutoFill requires exact Butler identity mapping for every active roster player; missing for Sleeper "
                        + target.sleeperPlayerId() + ".");
            }
            List<String> fantasyPositions = eligibilityRepository.findByPlayerId(target.butlerPlayerId());
            if (fantasyPositions.isEmpty()) {
                return RecommendationReport.unavailable(
                    roster.providerSeason(), roster.providerLeg(), scoring,
                    "AutoFill requires current Sleeper fantasy-position eligibility for " + display(target) + ".");
            }

            SleeperWeeklyProjectionProvider.Projection projection = projectionBySleeperId.get(target.sleeperPlayerId());
            if (projection == null) {
                return RecommendationReport.unavailable(
                    roster.providerSeason(), roster.providerLeg(), scoring,
                    "Current weekly projection evidence has no exact Sleeper player-id match for " + display(target)
                        + "; Butler will not guess.");
            }

            optimizerRoster.add(new AutoFillLineupOptimizer.RosterPlayer(
                target.sleeperPlayerId(),
                display(target),
                fantasyPositions,
                rosterSlot,
                target.starterOrdinal(),
                target.lineupSlot()));
            projectionsBySleeperId.put(target.sleeperPlayerId(), projection.projectedPoints());
            mappedActivePlayers++;
        }

        if (optimizerRoster.isEmpty()) {
            return RecommendationReport.unavailable(
                roster.providerSeason(), roster.providerLeg(), scoring,
                "AutoFill found no active starter/bench players in the exact live roster.");
        }

        AutoFillLineupOptimizer.Recommendation recommendation = new AutoFillLineupOptimizer()
            .optimize(roster.lineupSlots(), optimizerRoster, projectionsBySleeperId);
        if (!recommendation.ready()) {
            return RecommendationReport.unavailable(
                roster.providerSeason(), roster.providerLeg(), scoring, recommendation.reason());
        }

        BigDecimal currentProjectedTotal = BigDecimal.ZERO;
        for (var player : optimizerRoster) {
            if (player.rosterSlot() == AutoFillLineupOptimizer.RosterSlot.STARTER) {
                currentProjectedTotal = currentProjectedTotal.add(projectionsBySleeperId.get(player.playerId()));
            }
        }
        BigDecimal projectedGain = recommendation.projectedTotal().subtract(currentProjectedTotal);
        return RecommendationReport.ready(
            roster.providerSeason(), roster.providerLeg(), scoring,
            snapshot.sourceName(), snapshot.sourceSurface(), mappedActivePlayers,
            currentProjectedTotal, projectedGain, recommendation);
    }

    private static AutoFillLineupOptimizer.RosterSlot rosterSlot(String value) {
        if (value == null) throw new IllegalArgumentException("rosterSlot must not be null");
        return switch (value) {
            case "STARTER" -> AutoFillLineupOptimizer.RosterSlot.STARTER;
            case "BENCH" -> AutoFillLineupOptimizer.RosterSlot.BENCH;
            case "RESERVE" -> AutoFillLineupOptimizer.RosterSlot.RESERVE;
            case "TAXI" -> AutoFillLineupOptimizer.RosterSlot.TAXI;
            default -> throw new IllegalStateException("unsupported live roster slot: " + value);
        };
    }

    private static String display(SleeperLiveWaiverTargetRosterContextAudit.TargetPlayer target) {
        return target.displayName() == null || target.displayName().isBlank()
            ? "Sleeper " + target.sleeperPlayerId()
            : target.displayName().trim();
    }

    private static String safeMessage(Exception e) {
        String message = e.getMessage();
        return message == null || message.isBlank() ? e.getClass().getSimpleName() : message;
    }

    @FunctionalInterface
    interface ProjectionSource {
        SleeperWeeklyProjectionProvider.ProjectionSnapshot load(
            int season,
            int week,
            SleeperWeeklyProjectionProvider.ScoringBasis scoring)
            throws IOException, InterruptedException;
    }

    public record RecommendationReport(
        String policyId,
        boolean ready,
        String reason,
        int season,
        Integer week,
        SleeperWeeklyProjectionProvider.ScoringBasis scoringBasis,
        String sourceName,
        String sourceSurface,
        int mappedActivePlayers,
        BigDecimal currentProjectedTotal,
        BigDecimal projectedGain,
        AutoFillLineupOptimizer.Recommendation recommendation) {
        public RecommendationReport {
            if (!POLICY_ID.equals(policyId)) throw new IllegalArgumentException("unexpected policyId");
            if (season <= 0) throw new IllegalArgumentException("season must be positive");
            if (ready) {
                if (reason != null) throw new IllegalArgumentException("ready report cannot have a reason");
                if (week == null || week <= 0) throw new IllegalArgumentException("ready report requires week");
                Objects.requireNonNull(scoringBasis, "ready report requires scoringBasis");
                sourceName = requireText(sourceName, "sourceName");
                sourceSurface = requireText(sourceSurface, "sourceSurface");
                if (mappedActivePlayers <= 0) throw new IllegalArgumentException("mappedActivePlayers must be positive");
                Objects.requireNonNull(currentProjectedTotal, "currentProjectedTotal must not be null");
                Objects.requireNonNull(projectedGain, "projectedGain must not be null");
                Objects.requireNonNull(recommendation, "recommendation must not be null");
                if (!recommendation.ready()) throw new IllegalArgumentException("ready report requires ready recommendation");
            } else {
                reason = requireText(reason, "reason");
                if (sourceName != null || sourceSurface != null || mappedActivePlayers != 0
                    || currentProjectedTotal != null || projectedGain != null || recommendation != null) {
                    throw new IllegalArgumentException("unavailable report cannot contain recommendation output");
                }
            }
        }

        public static RecommendationReport unavailable(
            int season,
            Integer week,
            SleeperWeeklyProjectionProvider.ScoringBasis scoringBasis,
            String reason) {
            return new RecommendationReport(
                POLICY_ID, false, reason, season, week, scoringBasis,
                null, null, 0, null, null, null);
        }

        public static RecommendationReport ready(
            int season,
            int week,
            SleeperWeeklyProjectionProvider.ScoringBasis scoringBasis,
            String sourceName,
            String sourceSurface,
            int mappedActivePlayers,
            BigDecimal currentProjectedTotal,
            BigDecimal projectedGain,
            AutoFillLineupOptimizer.Recommendation recommendation) {
            return new RecommendationReport(
                POLICY_ID, true, null, season, week, scoringBasis,
                sourceName, sourceSurface, mappedActivePlayers,
                currentProjectedTotal, projectedGain, recommendation);
        }
    }

    private static String requireText(String value, String field) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(field + " must not be blank");
        return value.trim();
    }
}
