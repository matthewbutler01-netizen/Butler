package io.butler.bet.sleeper;

import io.butler.bet.data.Database;
import io.butler.bet.data.LeagueScoringSettingsRepository;
import io.butler.bet.data.PlayerFantasyPositionRepository;
import io.butler.bet.integration.SleeperWeeklyProjectionProvider;
import io.butler.bet.intelligence.AutoFillLineupOptimizer;

import java.io.IOException;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** BF-822/BF-825/BF-826 read-only composition of exact live roster, projection, and availability evidence. */
public final class SleeperLiveAutoFillLineupRecommendation {
    public static final String POLICY_ID =
        "sleeper-live-autofill-v2-bf610-provider-independent-weekly-projection-preview-only";

    private static final SleeperPlayerAvailabilityProvider SHARED_AVAILABILITY_PROVIDER =
        new SleeperPlayerAvailabilityProvider();

    private final Database database;
    private final ProjectionSource projectionSource;
    private final AvailabilitySource availabilitySource;

    public SleeperLiveAutoFillLineupRecommendation(Database database) {
        this(database, productionProjectionSource(), SHARED_AVAILABILITY_PROVIDER::load);
    }

    SleeperLiveAutoFillLineupRecommendation(Database database, ProjectionSource projectionSource) {
        this(database, projectionSource, sleeperPlayerIds -> Map.of());
    }

    SleeperLiveAutoFillLineupRecommendation(
        Database database,
        ProjectionSource projectionSource,
        AvailabilitySource availabilitySource) {
        this.database = Objects.requireNonNull(database, "database must not be null");
        this.projectionSource = Objects.requireNonNull(projectionSource, "projectionSource must not be null");
        this.availabilitySource = Objects.requireNonNull(availabilitySource, "availabilitySource must not be null");
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
            snapshot = projectionSource.load(
                roster.providerSeason(), roster.providerLeg(), scoring, scoringSettings);
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
        List<SleeperLiveWaiverTargetRosterContextAudit.TargetPlayer> missingProjectionTargets = new ArrayList<>();
        int mappedActivePlayers = 0;

        Map<String, SleeperWeeklyProjectionProvider.Projection> projectionBySleeperId = new LinkedHashMap<>();
        for (var projection : snapshot.projections()) {
            if (projectionBySleeperId.putIfAbsent(projection.sleeperPlayerId(), projection) != null) {
                return RecommendationReport.unavailable(
                    roster.providerSeason(), roster.providerLeg(), scoring,
                    "Weekly projection evidence contains a duplicate Sleeper player id; Butler will not guess.");
            }
        }

        Map<String, SleeperWeeklyProjectionProvider.ProjectionGap> gapBySleeperId = new LinkedHashMap<>();
        for (var gap : snapshot.gaps()) {
            if (gapBySleeperId.putIfAbsent(gap.sleeperPlayerId(), gap) != null) {
                return RecommendationReport.unavailable(
                    roster.providerSeason(), roster.providerLeg(), scoring,
                    "Weekly projection evidence contains duplicate coverage gaps for one Sleeper player id; Butler will not guess.");
            }
            if (projectionBySleeperId.containsKey(gap.sleeperPlayerId())) {
                return RecommendationReport.unavailable(
                    roster.providerSeason(), roster.providerLeg(), scoring,
                    "Weekly projection evidence is contradictory for one Sleeper player id; Butler will not guess.");
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

            optimizerRoster.add(new AutoFillLineupOptimizer.RosterPlayer(
                target.sleeperPlayerId(),
                display(target),
                fantasyPositions,
                rosterSlot,
                target.starterOrdinal(),
                target.lineupSlot()));
            mappedActivePlayers++;

            SleeperWeeklyProjectionProvider.Projection projection = projectionBySleeperId.get(target.sleeperPlayerId());
            if (projection == null) {
                missingProjectionTargets.add(target);
            } else {
                projectionsBySleeperId.put(target.sleeperPlayerId(), projection.projectedPoints());
            }
        }

        if (optimizerRoster.isEmpty()) {
            return RecommendationReport.unavailable(
                roster.providerSeason(), roster.providerLeg(), scoring,
                "AutoFill found no active starter/bench players in the exact live roster.");
        }

        Set<String> explicitlyUnavailablePlayerIds = new LinkedHashSet<>();
        List<UnavailablePlayerExclusion> availabilityExclusions = new ArrayList<>();
        if (!missingProjectionTargets.isEmpty()) {
            Set<String> missingIds = new LinkedHashSet<>();
            for (var target : missingProjectionTargets) missingIds.add(target.sleeperPlayerId());

            final Map<String, SleeperPlayerAvailabilityProvider.PlayerAvailability> availabilityBySleeperId;
            try {
                availabilityBySleeperId = Objects.requireNonNull(
                    availabilitySource.load(Set.copyOf(missingIds)),
                    "availabilitySource returned null");
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return RecommendationReport.unavailable(
                    roster.providerSeason(), roster.providerLeg(), scoring,
                    "Current Sleeper player availability evidence request was interrupted; Butler will not guess around missing projections.");
            } catch (IOException | IllegalStateException | NullPointerException e) {
                return RecommendationReport.unavailable(
                    roster.providerSeason(), roster.providerLeg(), scoring,
                    "Current Sleeper player availability evidence is unavailable: " + safeMessage(e)
                        + "; Butler will not guess around missing projections.");
            }

            for (var target : missingProjectionTargets) {
                SleeperWeeklyProjectionProvider.ProjectionGap gap = gapBySleeperId.get(target.sleeperPlayerId());
                String coverageDescription = gap == null
                    ? "Current weekly projection evidence has no exact Sleeper player-id row for " + display(target)
                    : "Current weekly projection evidence has an exact Sleeper player-id row for " + display(target)
                        + " but it is not scoreable: " + gap.reason();

                SleeperPlayerAvailabilityProvider.PlayerAvailability availability =
                    availabilityBySleeperId.get(target.sleeperPlayerId());
                if (availability == null) {
                    return RecommendationReport.unavailable(
                        roster.providerSeason(), roster.providerLeg(), scoring,
                        coverageDescription
                            + ", and current availability evidence has no exact match either; Butler will not guess.");
                }
                if (!target.sleeperPlayerId().equals(availability.sleeperPlayerId())) {
                    return RecommendationReport.unavailable(
                        roster.providerSeason(), roster.providerLeg(), scoring,
                        "Current availability evidence returned a mismatched Sleeper player id for " + display(target)
                            + "; Butler will not guess.");
                }
                if (!availability.explicitlyUnavailable()) {
                    return RecommendationReport.unavailable(
                        roster.providerSeason(), roster.providerLeg(), scoring,
                        coverageDescription + "; exact current availability (" + availability.evidenceDescription()
                            + ") does not explicitly prove unavailable to play, so Butler will not guess.");
                }

                explicitlyUnavailablePlayerIds.add(target.sleeperPlayerId());
                availabilityExclusions.add(new UnavailablePlayerExclusion(
                    target.sleeperPlayerId(),
                    display(target),
                    availability.status(),
                    availability.injuryStatus(),
                    "Excluded from startable candidates because exact current Sleeper availability explicitly proves unavailable; "
                        + "Butler did not synthesize a zero projection."));
            }
        }

        AutoFillLineupOptimizer.Recommendation recommendation = new AutoFillLineupOptimizer()
            .optimize(
                roster.lineupSlots(),
                optimizerRoster,
                projectionsBySleeperId,
                Set.copyOf(explicitlyUnavailablePlayerIds));
        if (!recommendation.ready()) {
            return RecommendationReport.unavailable(
                roster.providerSeason(), roster.providerLeg(), scoring, recommendation.reason());
        }

        BigDecimal currentProjectedTotal = BigDecimal.ZERO;
        for (var player : optimizerRoster) {
            if (player.rosterSlot() == AutoFillLineupOptimizer.RosterSlot.STARTER
                && !explicitlyUnavailablePlayerIds.contains(player.playerId())) {
                BigDecimal projection = projectionsBySleeperId.get(player.playerId());
                if (projection == null) {
                    return RecommendationReport.unavailable(
                        roster.providerSeason(), roster.providerLeg(), scoring,
                        "Current starter projection evidence became incomplete during AutoFill; Butler will not guess.");
                }
                currentProjectedTotal = currentProjectedTotal.add(projection);
            }
        }
        BigDecimal projectedGain = recommendation.projectedTotal().subtract(currentProjectedTotal);
        return RecommendationReport.ready(
            roster.providerSeason(), roster.providerLeg(), scoring,
            snapshot.sourceName(), snapshot.sourceSurface(), snapshot.observedAt(), mappedActivePlayers,
            currentProjectedTotal, projectedGain, recommendation, availabilityExclusions,
            projectionProvenance(snapshot));
    }

    private static ProjectionSource productionProjectionSource() {
        SleeperWeeklyProjectionProvider provider = new SleeperWeeklyProjectionProvider();
        return new ProjectionSource() {
            @Override
            public SleeperWeeklyProjectionProvider.ProjectionSnapshot load(
                int season,
                int week,
                SleeperWeeklyProjectionProvider.ScoringBasis scoring)
                throws IOException, InterruptedException {
                return provider.load(season, week, scoring);
            }

            @Override
            public SleeperWeeklyProjectionProvider.ProjectionSnapshot load(
                int season,
                int week,
                SleeperWeeklyProjectionProvider.ScoringBasis scoring,
                Map<String, Double> leagueScoringSettings)
                throws IOException, InterruptedException {
                return provider.load(season, week, scoring, leagueScoringSettings);
            }
        };
    }

    private static String projectionProvenance(SleeperWeeklyProjectionProvider.ProjectionSnapshot snapshot) {
        boolean hasPrecomputed = false;
        boolean hasLeagueScoredRaw = false;
        for (var projection : snapshot.projections()) {
            if (projection.provenance() == SleeperWeeklyProjectionProvider.ProjectionProvenance.SLEEPER_PRECOMPUTED) {
                hasPrecomputed = true;
            } else if (projection.provenance()
                == SleeperWeeklyProjectionProvider.ProjectionProvenance.SLEEPER_RAW_STATS_LEAGUE_SCORED) {
                hasLeagueScoredRaw = true;
            }
        }
        if (hasPrecomputed && hasLeagueScoredRaw) {
            return "Sleeper precomputed totals plus Butler league-scoring of exact Sleeper raw projected stats";
        }
        if (hasLeagueScoredRaw) {
            return "Butler league-scoring of exact Sleeper raw projected stats";
        }
        return "Sleeper precomputed totals";
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

        default SleeperWeeklyProjectionProvider.ProjectionSnapshot load(
            int season,
            int week,
            SleeperWeeklyProjectionProvider.ScoringBasis scoring,
            Map<String, Double> leagueScoringSettings)
            throws IOException, InterruptedException {
            return load(season, week, scoring);
        }
    }

    @FunctionalInterface
    interface AvailabilitySource {
        Map<String, SleeperPlayerAvailabilityProvider.PlayerAvailability> load(Set<String> sleeperPlayerIds)
            throws IOException, InterruptedException;
    }

    public record UnavailablePlayerExclusion(
        String sleeperPlayerId,
        String displayName,
        String status,
        String injuryStatus,
        String reason) {
        public UnavailablePlayerExclusion {
            sleeperPlayerId = requireText(sleeperPlayerId, "sleeperPlayerId");
            displayName = requireText(displayName, "displayName");
            status = clean(status);
            injuryStatus = clean(injuryStatus);
            reason = requireText(reason, "reason");
        }
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
        Instant projectionObservedAt,
        int mappedActivePlayers,
        BigDecimal currentProjectedTotal,
        BigDecimal projectedGain,
        AutoFillLineupOptimizer.Recommendation recommendation,
        List<UnavailablePlayerExclusion> availabilityExclusions,
        String projectionProvenance) {
        public RecommendationReport {
            if (!POLICY_ID.equals(policyId)) throw new IllegalArgumentException("unexpected policyId");
            if (season <= 0) throw new IllegalArgumentException("season must be positive");
            availabilityExclusions = List.copyOf(Objects.requireNonNull(
                availabilityExclusions, "availabilityExclusions must not be null"));
            if (ready) {
                if (reason != null) throw new IllegalArgumentException("ready report cannot have a reason");
                if (week == null || week <= 0) throw new IllegalArgumentException("ready report requires week");
                Objects.requireNonNull(scoringBasis, "ready report requires scoringBasis");
                sourceName = requireText(sourceName, "sourceName");
                sourceSurface = requireText(sourceSurface, "sourceSurface");
                projectionProvenance = requireText(projectionProvenance, "projectionProvenance");
                Objects.requireNonNull(projectionObservedAt, "ready report requires projectionObservedAt");
                if (mappedActivePlayers <= 0) throw new IllegalArgumentException("mappedActivePlayers must be positive");
                Objects.requireNonNull(currentProjectedTotal, "currentProjectedTotal must not be null");
                Objects.requireNonNull(projectedGain, "projectedGain must not be null");
                Objects.requireNonNull(recommendation, "recommendation must not be null");
                if (!recommendation.ready()) throw new IllegalArgumentException("ready report requires ready recommendation");
            } else {
                reason = requireText(reason, "reason");
                if (sourceName != null || sourceSurface != null || projectionObservedAt != null || mappedActivePlayers != 0
                    || currentProjectedTotal != null || projectedGain != null || recommendation != null
                    || !availabilityExclusions.isEmpty() || projectionProvenance != null) {
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
                null, null, null, 0, null, null, null, List.of(), null);
        }

        public static RecommendationReport ready(
            int season,
            int week,
            SleeperWeeklyProjectionProvider.ScoringBasis scoringBasis,
            String sourceName,
            String sourceSurface,
            Instant projectionObservedAt,
            int mappedActivePlayers,
            BigDecimal currentProjectedTotal,
            BigDecimal projectedGain,
            AutoFillLineupOptimizer.Recommendation recommendation,
            List<UnavailablePlayerExclusion> availabilityExclusions,
            String projectionProvenance) {
            return new RecommendationReport(
                POLICY_ID, true, null, season, week, scoringBasis,
                sourceName, sourceSurface, projectionObservedAt, mappedActivePlayers,
                currentProjectedTotal, projectedGain, recommendation, availabilityExclusions, projectionProvenance);
        }
    }

    private static String clean(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private static String requireText(String value, String field) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(field + " must not be blank");
        return value.trim();
    }
}
