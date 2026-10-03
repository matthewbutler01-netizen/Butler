package io.butler.bet.sleeper;

import io.butler.bet.data.Database;
import io.butler.bet.data.LeagueScoringSettingsRepository;
import io.butler.bet.data.LiveWaiverSnapshotRepository;
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
    private final NewsSource newsSource;
    private final NewsSource analysisSource;
    private final UsageSource usageSource;
    private final MatchupSource matchupSource;
    private final ExpertSource expertSource;
    private final GameLockSource gameLockSource;
    private static final NflExpertPickProvider SHARED_EXPERT_PROVIDER = new NflExpertPickProvider();
    private static final NflverseDefensiveMatchupProvider SHARED_MATCHUP_PROVIDER = new NflverseDefensiveMatchupProvider();
    private static final NflverseGameLockProvider SHARED_GAME_LOCK_PROVIDER = new NflverseGameLockProvider();
    private static final RosterInjuryNewsProvider SHARED_NEWS_PROVIDER = new RosterInjuryNewsProvider();
    private static final NflverseRosterUsageProvider SHARED_USAGE_PROVIDER = new NflverseRosterUsageProvider();

    public SleeperLiveAutoFillLineupRecommendation(Database database) {
        this(database, productionProjectionSource(), SHARED_AVAILABILITY_PROVIDER::load, SHARED_NEWS_PROVIDER::load,
            SHARED_NEWS_PROVIDER::loadAnalysis, SHARED_USAGE_PROVIDER::load, SHARED_MATCHUP_PROVIDER::load,
            SHARED_EXPERT_PROVIDER::load, SHARED_GAME_LOCK_PROVIDER::load);
    }

    SleeperLiveAutoFillLineupRecommendation(Database database, ProjectionSource projectionSource) {
        this(database, projectionSource, sleeperPlayerIds -> Map.of());
    }

    SleeperLiveAutoFillLineupRecommendation(
        Database database,
        ProjectionSource projectionSource,
        AvailabilitySource availabilitySource) {
        this(database, projectionSource, availabilitySource, players -> Map.of());
    }

    SleeperLiveAutoFillLineupRecommendation(Database database, ProjectionSource projectionSource,
        AvailabilitySource availabilitySource, NewsSource newsSource) {
        this(database, projectionSource, availabilitySource, newsSource, players -> Map.of());
    }

    SleeperLiveAutoFillLineupRecommendation(Database database, ProjectionSource projectionSource,
        AvailabilitySource availabilitySource, NewsSource newsSource, NewsSource analysisSource) {
        this(database, projectionSource, availabilitySource, newsSource, analysisSource, (season, week, ids) -> Map.of());
    }

    SleeperLiveAutoFillLineupRecommendation(Database database, ProjectionSource projectionSource,
        AvailabilitySource availabilitySource, NewsSource newsSource, NewsSource analysisSource, UsageSource usageSource) {
        this(database, projectionSource, availabilitySource, newsSource, analysisSource, usageSource,
            (season, week, players) -> Map.of());
    }

    SleeperLiveAutoFillLineupRecommendation(Database database, ProjectionSource projectionSource,
        AvailabilitySource availabilitySource, NewsSource newsSource, NewsSource analysisSource, UsageSource usageSource,
        MatchupSource matchupSource) {
        this(database, projectionSource, availabilitySource, newsSource, analysisSource, usageSource, matchupSource,
            (season, week, players) -> List.of());
    }

    SleeperLiveAutoFillLineupRecommendation(Database database, ProjectionSource projectionSource,
        AvailabilitySource availabilitySource, NewsSource newsSource, NewsSource analysisSource, UsageSource usageSource,
        MatchupSource matchupSource, ExpertSource expertSource) {
        this(database, projectionSource, availabilitySource, newsSource, analysisSource, usageSource, matchupSource,
            expertSource, (season, week, players) -> {
                Map<String, NflverseGameLockProvider.GameLockEvidence> unlocked = new LinkedHashMap<>();
                for (var player : players) {
                    unlocked.put(player.sleeperPlayerId(), new NflverseGameLockProvider.GameLockEvidence(
                        true, false, Instant.MAX, "Test composition: kickoff is not locked."));
                }
                return Map.copyOf(unlocked);
            });
    }

    SleeperLiveAutoFillLineupRecommendation(Database database, ProjectionSource projectionSource,
        AvailabilitySource availabilitySource, NewsSource newsSource, NewsSource analysisSource, UsageSource usageSource,
        MatchupSource matchupSource, ExpertSource expertSource, GameLockSource gameLockSource) {
        this.expertSource = Objects.requireNonNull(expertSource);
        this.gameLockSource = Objects.requireNonNull(gameLockSource, "gameLockSource must not be null");
        this.matchupSource = Objects.requireNonNull(matchupSource);
        this.usageSource = Objects.requireNonNull(usageSource, "usageSource must not be null");
        this.analysisSource = Objects.requireNonNull(analysisSource, "analysisSource must not be null");
        this.newsSource = Objects.requireNonNull(newsSource, "newsSource must not be null");
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
                fantasyPositions = new LiveWaiverSnapshotRepository(database).rosterFantasyPositions(
                    roster.waiverSnapshotId(), roster.leagueId(), roster.sleeperLeagueId(),
                    roster.providerSeason(), target.sleeperPlayerId());
            }
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
        Set<String> projectionHoldPlayerIds = new LinkedHashSet<>();
        List<UnavailablePlayerExclusion> availabilityExclusions = new ArrayList<>();
        List<ProjectionHold> projectionHolds = new ArrayList<>();
        // A projection is not proof that a player is healthy enough to start.
        Set<String> activeIds = new LinkedHashSet<>();
        for (var player : optimizerRoster) activeIds.add(player.playerId());

        Set<String> gameStateHeldPlayerIds = new LinkedHashSet<>();
        Map<String, NflverseGameLockProvider.GameLockEvidence> gameLocks;
        try {
            gameLocks = Objects.requireNonNull(
                gameLockSource.load(roster.providerSeason(), roster.providerLeg(), roster.targetPlayers()),
                "gameLockSource returned null");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return RecommendationReport.unavailable(
                roster.providerSeason(), roster.providerLeg(), scoring,
                "NFL kickoff lock evidence request was interrupted; Butler will not propose a lineup change without verified game-lock state.");
        } catch (IOException | IllegalStateException | NullPointerException e) {
            return RecommendationReport.unavailable(
                roster.providerSeason(), roster.providerLeg(), scoring,
                "NFL kickoff lock evidence is unavailable: " + safeMessage(e)
                    + ". Butler will not propose a lineup change without verified game-lock state.");
        }

        for (var target : roster.targetPlayers()) {
            if (!activeIds.contains(target.sleeperPlayerId())) continue;
            var lock = gameLocks.get(target.sleeperPlayerId());
            if (lock != null && lock.verified() && !lock.locked()) continue;

            gameStateHeldPlayerIds.add(target.sleeperPlayerId());
            projectionsBySleeperId.remove(target.sleeperPlayerId());
            projectionHoldPlayerIds.add(target.sleeperPlayerId());
            String detail = lock == null
                ? "NFL kickoff lock unverified: no exact game-lock evidence returned for this active roster player."
                : lock.detail();
            projectionHolds.add(new ProjectionHold(
                target.sleeperPlayerId(), display(target), target.rosterSlot(), target.lineupSlot(),
                null, null,
                (lock != null && lock.verified() && lock.locked()
                    ? "Game locked: "
                    : "Game-lock review hold: ")
                    + detail
                    + " Butler preserved this player's current starter/bench state and excluded the player from lineup moves."));
        }

        Map<String, SleeperPlayerAvailabilityProvider.PlayerAvailability> availabilityBySleeperId = Map.of();
        String availabilityFailure = null;
        try {
            availabilityBySleeperId = Objects.requireNonNull(
                availabilitySource.load(Set.copyOf(activeIds)), "availabilitySource returned null");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            availabilityFailure = "Current Sleeper player availability evidence request was interrupted.";
        } catch (IOException | IllegalStateException | NullPointerException e) {
            availabilityFailure = "Current Sleeper player availability evidence is unavailable: " + safeMessage(e);
        }
        Map<String, String> injuryNews = Map.of();
        try {
            injuryNews = newsSource.load(roster.targetPlayers());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (IOException | IllegalStateException e) {
            // News failure never fabricates clearance or overwrites Sleeper availability.
        }
        for (var target : roster.targetPlayers()) {
            if (gameStateHeldPlayerIds.contains(target.sleeperPlayerId())) continue;
            if (!projectionsBySleeperId.containsKey(target.sleeperPlayerId())) continue;
            var availability = availabilityBySleeperId.get(target.sleeperPlayerId());
            String news = injuryNews.get(target.sleeperPlayerId());
            boolean exactAvailability = availability != null
                && target.sleeperPlayerId().equals(availability.sleeperPlayerId());
            if ((exactAvailability && availability.requiresInjuryReview()) || news != null) {
                projectionsBySleeperId.remove(target.sleeperPlayerId());
                if (exactAvailability && availability.confirmedUnavailable()) {
                    explicitlyUnavailablePlayerIds.add(target.sleeperPlayerId());
                    availabilityExclusions.add(new UnavailablePlayerExclusion(
                        target.sleeperPlayerId(), display(target), availability.status(), availability.injuryStatus(),
                        "Excluded from startable candidates: " + availability.evidenceDescription()
                            + ". A projection does not override confirmed unavailable status."));
                } else {
                    projectionHoldPlayerIds.add(target.sleeperPlayerId());
                    projectionHolds.add(new ProjectionHold(
                        target.sleeperPlayerId(), display(target), target.rosterSlot(), target.lineupSlot(),
                        exactAvailability ? availability.status() : null,
                        exactAvailability ? availability.injuryStatus() : null,
                        "Availability hold: " + (exactAvailability ? availability.evidenceDescription() : "current status unverified")
                            + (news == null ? "" : "; " + news)
                            + ". Pending clearance, Butler preserved this player's current lineup state and excluded "
                            + "the player from promotions and comparable projected totals. Questionable is not confirmed Out."));
                }
            }
        }
        if (!missingProjectionTargets.isEmpty()) {
            for (var target : missingProjectionTargets) {
                if (gameStateHeldPlayerIds.contains(target.sleeperPlayerId())) continue;
                SleeperWeeklyProjectionProvider.ProjectionGap gap = gapBySleeperId.get(target.sleeperPlayerId());
                String coverageDescription = gap == null
                    ? "Current weekly projection evidence has no exact Sleeper player-id row for " + display(target)
                    : "Current weekly projection evidence has an exact Sleeper player-id row for " + display(target)
                        + " but it is not scoreable: " + gap.reason();

                SleeperPlayerAvailabilityProvider.PlayerAvailability availability =
                    availabilityBySleeperId.get(target.sleeperPlayerId());

                if (availabilityFailure == null
                    && availability != null
                    && target.sleeperPlayerId().equals(availability.sleeperPlayerId())
                    && availability.confirmedUnavailable()) {
                    explicitlyUnavailablePlayerIds.add(target.sleeperPlayerId());
                    availabilityExclusions.add(new UnavailablePlayerExclusion(
                        target.sleeperPlayerId(),
                        display(target),
                        availability.status(),
                        availability.injuryStatus(),
                        "Excluded from startable candidates because exact current Sleeper availability confirms unavailable status; "
                            + "Butler did not synthesize a zero projection."));
                    continue;
                }

                String holdReason;
                String status = availability == null ? null : availability.status();
                String injuryStatus = availability == null ? null : availability.injuryStatus();
                if (availabilityFailure != null) {
                    holdReason = coverageDescription + "; " + availabilityFailure
                        + " Butler preserved the player's current lineup state instead of inventing availability or a projection.";
                } else if (availability == null) {
                    holdReason = coverageDescription
                        + "; current availability evidence has no exact match. Butler preserved the player's current lineup state instead of guessing.";
                } else if (!target.sleeperPlayerId().equals(availability.sleeperPlayerId())) {
                    holdReason = coverageDescription
                        + "; current availability evidence returned a mismatched Sleeper player id. Butler preserved the player's current lineup state instead of guessing.";
                } else {
                    holdReason = coverageDescription + "; exact current availability (" + availability.evidenceDescription()
                        + ") does not confirm unavailable status. Butler preserved the player's current lineup state and did not synthesize a zero projection.";
                }

                projectionHoldPlayerIds.add(target.sleeperPlayerId());
                projectionHolds.add(new ProjectionHold(
                    target.sleeperPlayerId(),
                    display(target),
                    target.rosterSlot(),
                    target.lineupSlot(),
                    status,
                    injuryStatus,
                    holdReason));
            }
        }

        Map<String, NflverseRosterUsageProvider.UsageEvidence> usage = Map.of();
        String usageCoverage = "Recent snap/target usage unavailable; missing observations are not zero usage. Manual review required.";
        try {
            usage = Objects.requireNonNull(usageSource.load(roster.providerSeason(), roster.providerLeg(), Set.copyOf(activeIds)));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            usageCoverage = "Usage source check interrupted; role evidence unverified. Manual review required.";
        } catch (IOException | IllegalStateException | NullPointerException e) {
            usageCoverage = "Usage source unavailable or inconsistent; role evidence unverified. Manual review required.";
        }
        for (var target : roster.targetPlayers()) {
            var observation = usage.get(target.sleeperPlayerId());
            if (observation == null || !observation.reviewHold()
                || !Set.of("RB", "WR", "TE").contains(target.position())
                || !projectionsBySleeperId.containsKey(target.sleeperPlayerId())) continue;
            projectionsBySleeperId.remove(target.sleeperPlayerId());
            projectionHoldPlayerIds.add(target.sleeperPlayerId());
            projectionHolds.add(new ProjectionHold(target.sleeperPlayerId(), display(target), target.rosterSlot(),
                target.lineupSlot(), null, null, "Usage review hold: " + observation.detail()
                    + " Conservative policy: at least 50% fewer carries plus targets and at least 20 percentage points"
                    + " lower snap share, from a prior baseline of at least four opportunities and 40% snaps."
                    + " Preserved current lineup state; excluded from promotions. This is not an injury designation."));
        }

        AutoFillLineupOptimizer.Recommendation recommendation = new AutoFillLineupOptimizer()
            .optimize(
                roster.startingSlots(),
                optimizerRoster,
                projectionsBySleeperId,
                Set.copyOf(explicitlyUnavailablePlayerIds),
                Set.copyOf(projectionHoldPlayerIds), Set.copyOf(roster.emptyStartingOrdinals()));
        List<AutoFillLineupOptimizer.SlotRecommendation> withheldSwaps = new ArrayList<>();
        // Re-evaluate after each batch of held bench candidates so replacement alternatives are checked too.
        while (recommendation.ready()) {
            boolean changed = false;
            for (var assignment : recommendation.assignments()) {
                if (!assignment.changed() || !LineupSwapReviewPolicy.conflictingUsage(assignment.projectedGain(),
                    usage.get(assignment.currentPlayerId()), usage.get(assignment.recommendedPlayerId()))) continue;
                var proposed = roster.targetPlayers().stream()
                    .filter(p -> p.sleeperPlayerId().equals(assignment.recommendedPlayerId())).findFirst().orElse(null);
                var current = roster.targetPlayers().stream()
                    .filter(p -> p.sleeperPlayerId().equals(assignment.currentPlayerId())).findFirst().orElse(null);
                if (proposed == null || !"BENCH".equals(proposed.rosterSlot())
                    || current == null || !proposed.position().equals(current.position())
                    || !Set.of("RB", "WR", "TE").contains(proposed.position())
                    || !projectionHoldPlayerIds.add(proposed.sleeperPlayerId())) continue;
                projectionsBySleeperId.remove(proposed.sleeperPlayerId());
                withheldSwaps.add(assignment);
                projectionHolds.add(new ProjectionHold(proposed.sleeperPlayerId(), display(proposed), proposed.rosterSlot(),
                    proposed.lineupSlot(), null, null,
                    "Close-call usage conflict: projected slot gain " + assignment.projectedGain()
                        + " is at most 1 point, while current player's carries plus targets rose at least 25%"
                        + " and proposed player's fell at least 50%, from baselines of at least four opportunities."
                        + " Manual review required; candidate withheld from promotion. This conservative heuristic"
                        + " does not prove future performance or a changed role."));
                changed = true;
            }
            if (!changed) break;
            recommendation = new AutoFillLineupOptimizer().optimize(roster.startingSlots(), optimizerRoster,
                projectionsBySleeperId, Set.copyOf(explicitlyUnavailablePlayerIds), Set.copyOf(projectionHoldPlayerIds), Set.copyOf(roster.emptyStartingOrdinals()));
        }
        if (!recommendation.ready()) {
            return RecommendationReport.unavailable(
                roster.providerSeason(), roster.providerLeg(), scoring, recommendation.reason());
        }

        BigDecimal currentProjectedTotal = BigDecimal.ZERO;
        for (var player : optimizerRoster) {
            if (player.rosterSlot() == AutoFillLineupOptimizer.RosterSlot.STARTER
                && !explicitlyUnavailablePlayerIds.contains(player.playerId())
                && !projectionHoldPlayerIds.contains(player.playerId())) {
                BigDecimal projection = projectionsBySleeperId.get(player.playerId());
                if (projection == null) {
                    return RecommendationReport.unavailable(
                        roster.providerSeason(), roster.providerLeg(), scoring,
                        "Current scoreable starter projection evidence became incomplete during AutoFill; Butler will not guess.");
                }
                currentProjectedTotal = currentProjectedTotal.add(projection);
            }
        }
        BigDecimal projectedGain = recommendation.projectedTotal().subtract(currentProjectedTotal);
        List<AutoFillLineupOptimizer.SlotRecommendation> withheldSmallEdgeSwaps = new ArrayList<>();
        boolean hardLegalityNeed = !roster.emptyStartingOrdinals().isEmpty()
            || optimizerRoster.stream().anyMatch(player ->
                player.rosterSlot() == AutoFillLineupOptimizer.RosterSlot.STARTER
                    && explicitlyUnavailablePlayerIds.contains(player.playerId()));

        while (LineupSwapReviewPolicy.belowActionableEdge(projectedGain, hardLegalityNeed)
            && recommendation.assignments().stream().anyMatch(AutoFillLineupOptimizer.SlotRecommendation::changed)) {
            var promotions = recommendation.promotions();
            if (promotions.isEmpty()) break;

            var changedAssignments = recommendation.assignments().stream()
                .filter(AutoFillLineupOptimizer.SlotRecommendation::changed)
                .toList();
            boolean heldAny = false;
            for (var promotion : promotions) {
                if (!projectionHoldPlayerIds.add(promotion.playerId())) continue;
                projectionsBySleeperId.remove(promotion.playerId());
                projectionHolds.add(new ProjectionHold(
                    promotion.playerId(), promotion.displayName(), "BENCH", null, null, null,
                    "Small-edge review hold: Butler's total comparable lineup improvement was "
                        + projectedGain + " points, below the 1.0-point action threshold."
                        + " No hard legality problem required this promotion, so Butler preserved"
                        + " the current lineup and withheld the candidate from an actionable swap."));
                heldAny = true;
            }
            if (!heldAny) break;

            withheldSmallEdgeSwaps.addAll(changedAssignments);
            recommendation = new AutoFillLineupOptimizer().optimize(
                roster.startingSlots(), optimizerRoster, projectionsBySleeperId,
                Set.copyOf(explicitlyUnavailablePlayerIds), Set.copyOf(projectionHoldPlayerIds),
                Set.copyOf(roster.emptyStartingOrdinals()));
            if (!recommendation.ready()) {
                return RecommendationReport.unavailable(
                    roster.providerSeason(), roster.providerLeg(), scoring, recommendation.reason());
            }
            projectedGain = recommendation.projectedTotal().subtract(currentProjectedTotal);
        }

        List<String> decisionEvidence = new ArrayList<>(LineupDecisionEvidence.describe(database, roster, recommendation));
        for (var assignment : recommendation.assignments()) {
            if (!assignment.changed()) continue;
            var currentUsage = usage.get(assignment.currentPlayerId());
            var proposedUsage = usage.get(assignment.recommendedPlayerId());
            decisionEvidence.add("Usage review for " + assignment.currentPlayerName() + " -> "
                + assignment.recommendedPlayerName() + ": Current player: "
                + (currentUsage == null ? usageCoverage : currentUsage.detail()) + " Proposed player: "
                + (proposedUsage == null ? usageCoverage : proposedUsage.detail())
                + " Ranking remains projection-based after availability and usage holds; matchup and expert start/sit picks remain unverified.");
        }
        Map<String, String> analysis = Map.of();
        String analysisCoverage = "No matching recent public analysis was found; this is not expert consensus.";
        try {
            analysis = analysisSource.load(roster.targetPlayers());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            analysisCoverage = "Public analysis check interrupted; expert coverage unverified.";
        } catch (IOException | IllegalStateException e) {
            analysisCoverage = "Public analysis feed unavailable; expert coverage unverified.";
        }
        for (var assignment : recommendation.assignments()) {
            if (!assignment.changed()) continue;
            String currentAnalysis = analysis.get(assignment.currentPlayerId());
            String proposedAnalysis = analysis.get(assignment.recommendedPlayerId());
            decisionEvidence.add("Public analysis for " + assignment.currentPlayerName() + " -> "
                + assignment.recommendedPlayerName() + ": "
                + (currentAnalysis == null ? "Current player: no matched commentary. " : currentAnalysis + " ")
                + (proposedAnalysis == null ? "Proposed player: no matched commentary. " : proposedAnalysis + " ")
                + (currentAnalysis == null && proposedAnalysis == null ? analysisCoverage : ""));
        }
        List<ExpertPick> expertPicks = List.of();
        try { expertPicks = expertSource.load(roster.providerSeason(), roster.providerLeg(), roster.targetPlayers()); }
        catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        Map<String, String> matchups = Map.of();
        if (!withheldSwaps.isEmpty() || !withheldSmallEdgeSwaps.isEmpty()
            || recommendation.assignments().stream().anyMatch(a -> a.changed())
            || !projectionHolds.isEmpty() || expertPicks.stream().anyMatch(p -> "SIT".equals(p.selection()))) {
            try {
                matchups = matchupSource.load(roster.providerSeason(), roster.providerLeg(), roster.targetPlayers());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } catch (IOException | IllegalStateException e) {
                // Optional evidence failure leaves the proposal qualified for manual review.
            }
        }
        List<SwapReview> reviews = new ArrayList<>();
        for (var assignment : withheldSwaps) {
            reviews.add(swapReview(assignment, usage, analysis, analysisCoverage, true, roster.providerSeason(), matchups));
        }
        for (var assignment : withheldSmallEdgeSwaps) {
            var evidence = swapReview(assignment, usage, analysis, analysisCoverage, false, roster.providerSeason(), matchups);
            reviews.add(new SwapReview(
                evidence.ordinal(), evidence.slot(), evidence.current(), evidence.proposed(), evidence.projectedGain(),
                "WITHHELD_SMALL_EDGE",
                "The total comparable lineup improvement was below Butler's 1.0-point action threshold"
                    + " and no hard legality problem required the move. Butler preserved the current lineup."
                    + " " + evidence.reason(),
                evidence.currentUsage(), evidence.proposedUsage(), evidence.commentary(), evidence.sources(),
                evidence.currentMatchup(), evidence.proposedMatchup(), evidence.currentExpert(), evidence.proposedExpert()));
        }
        for (var assignment : recommendation.assignments()) {
            if (assignment.changed()) reviews.add(swapReview(assignment, usage, analysis, analysisCoverage, false, roster.providerSeason(), matchups));
        }
        var eligibility = new io.butler.bet.intelligence.LineupSlotEligibilityPolicy();
        for (var starter : optimizerRoster) {
            if (starter.rosterSlot() != AutoFillLineupOptimizer.RosterSlot.STARTER) continue;
            if (gameStateHeldPlayerIds.contains(starter.playerId())) {
                decisionEvidence.add("Game-lock review for " + starter.displayName()
                    + ": current starter state is frozen because kickoff is locked or could not be verified; Butler did not generate replacement candidates.");
                continue;
            }
            var starterPicks = expertPicks.stream().filter(p -> starter.playerId().equals(p.playerId())).toList();
            boolean expertSit = starterPicks.size() == 1 && "SIT".equals(starterPicks.get(0).selection());
            if (!expertSit && !projectionHoldPlayerIds.contains(starter.playerId())) continue;
            List<AutoFillLineupOptimizer.RosterPlayer> alternatives = new ArrayList<>();
            for (var bench : optimizerRoster) {
                if (bench.rosterSlot() == AutoFillLineupOptimizer.RosterSlot.BENCH
                    && !explicitlyUnavailablePlayerIds.contains(bench.playerId())
                    && !projectionHoldPlayerIds.contains(bench.playerId())
                    && projectionsBySleeperId.containsKey(bench.playerId())
                    && eligibility.isPlayerEligible(starter.currentLineupSlot(), bench.providerFantasyPositions())) alternatives.add(bench);
            }
            alternatives.sort(java.util.Comparator.<AutoFillLineupOptimizer.RosterPlayer, BigDecimal>comparing(
                p -> projectionsBySleeperId.get(p.playerId())).reversed().thenComparing(p -> p.playerId()));
            if (alternatives.isEmpty()) decisionEvidence.add("Replacement review for " + starter.displayName()
                + ": no eligible, scoreable bench alternative remains after availability and review holds. This does not establish that the starter should play.");
            for (var bench : alternatives.stream().limit(3).toList()) {
                BigDecimal currentPoints = projectionsBySleeperId.get(starter.playerId());
                BigDecimal benchPoints = projectionsBySleeperId.get(bench.playerId());
                var comparison = new AutoFillLineupOptimizer.SlotRecommendation(starter.starterOrdinal(), starter.currentLineupSlot(),
                    starter.playerId(), starter.displayName(), bench.playerId(), bench.displayName(), currentPoints, benchPoints,
                    currentPoints == null ? null : benchPoints.subtract(currentPoints), true);
                var evidence = swapReview(comparison, usage, analysis, analysisCoverage, false, roster.providerSeason(), matchups);
                String currentExpert = expertSummary(expertPicks, starter.playerId());
                String proposedExpert = expertSummary(expertPicks, bench.playerId());
                String expertContext = " Current expert: " + currentExpert + "; candidate expert: " + proposedExpert + ".";
                reviews.add(new SwapReview(evidence.ordinal(), evidence.slot(), evidence.current(), evidence.proposed(), evidence.projectedGain(),
                    "MANUAL_REVIEW_REPLACEMENT", "Bench alternative for a flagged starter; comparison only, not a proposed lineup move."
                        + " Up to three alternatives ordered by available projection; alternatives across slots are independent and cannot be combined without checking lineup legality."
                        + expertContext + " Existing holds remain. " + evidence.reason(), evidence.currentUsage(), evidence.proposedUsage(),
                    evidence.commentary(), evidence.sources(), evidence.currentMatchup(), evidence.proposedMatchup(), currentExpert, proposedExpert));
            }
        }
        return RecommendationReport.ready(
            roster.providerSeason(), roster.providerLeg(), scoring,
            snapshot.sourceName(), snapshot.sourceSurface(), snapshot.observedAt(), mappedActivePlayers,
            currentProjectedTotal, projectedGain, recommendation, availabilityExclusions,
            projectionHolds, projectionProvenance(snapshot))
            .withDecisionEvidence(decisionEvidence).withSwapReviews(reviews).withExpertPicks(expertPicks);
    }

    private static String expertSummary(List<ExpertPick> picks, String playerId) {
        var matches = picks.stream().filter(p -> playerId.equals(p.playerId())).toList();
        return matches.size() == 1 && Set.of("START", "SIT").contains(matches.get(0).selection())
            ? matches.get(0).selection() + " by " + matches.get(0).author() : "unverified";
    }

    private static SwapReview swapReview(AutoFillLineupOptimizer.SlotRecommendation assignment,
        Map<String, NflverseRosterUsageProvider.UsageEvidence> usage, Map<String, String> analysis,
        String analysisCoverage, boolean withheld, int season, Map<String, String> matchups) {
        var current = usage.get(assignment.currentPlayerId());
        var proposed = usage.get(assignment.recommendedPlayerId());
        boolean missing = current == null || proposed == null || !current.complete() || !proposed.complete();
        boolean conflict = LineupSwapReviewPolicy.conflictingUsage(assignment.projectedGain(), current, proposed);
        String reason = "0".equals(assignment.currentPlayerId())
            ? "Explicit empty starting slot; candidate fills a legal slot. No current-player projection or slot delta is inferred."
            : conflict
            ? "Small projection edge conflicts with observed workload: current opportunities rose at least 25%; proposed opportunities fell at least 50%."
            : missing ? "Usage coverage is incomplete; missing observations do not establish zero workload."
            : "Usage is available, but it does not establish a better future role or a complete start/sit decision.";
        return new SwapReview(assignment.starterOrdinal(), assignment.slot(), assignment.currentPlayerName(),
            assignment.recommendedPlayerName(), assignment.projectedGain() == null ? "Unavailable" : assignment.projectedGain().toPlainString(),
            withheld ? "WITHHELD_USAGE_CONFLICT" : conflict ? "MANUAL_REVIEW_USAGE_CONFLICT"
                : missing ? "MANUAL_REVIEW_USAGE_GAP" : "MANUAL_REVIEW_PROJECTION_PROPOSAL",
            reason + " Review NFL matchup and attributed expert coverage below; no consensus is established.",
            "0".equals(assignment.currentPlayerId()) ? "Empty slot; no current player" : current == null ? "Usage unavailable" : current.summary(), proposed == null ? "Usage unavailable" : proposed.summary(),
            analysis.getOrDefault(assignment.currentPlayerId(), "No matched public commentary")
                + " | " + analysis.getOrDefault(assignment.recommendedPlayerId(), "No matched public commentary")
                + (analysis.isEmpty() ? ". " + analysisCoverage : "")
                + ". Headlines are context, not extracted expert picks.",
            List.of(io.butler.bet.intelligence.NflversePlayerWeekProductionImporter.statsUri(season).toString(),
                NflverseRosterUsageProvider.snapsUri(season).toString(),
                io.butler.bet.intelligence.NflversePlayerSeasonProductionImporter.PLAYER_IDS_URI.toString(),
                NflverseDefensiveMatchupProvider.SCHEDULE_URI.toString()),
            matchups.getOrDefault(assignment.currentPlayerId(), "NFL matchup evidence unavailable; manual review required."),
            matchups.getOrDefault(assignment.recommendedPlayerId(), "NFL matchup evidence unavailable; manual review required."), "", "");
    }

    public record ExpertPick(String playerId, String player, String position, String selection,
        String author, String publishedAt, String modifiedAt, String checkedAt, String source, String coverage) {}

    public record SwapReview(int ordinal, String slot, String current, String proposed, String projectedGain,
        String status, String reason, String currentUsage, String proposedUsage, String commentary, List<String> sources,
        String currentMatchup, String proposedMatchup, String currentExpert, String proposedExpert) {
        public SwapReview { sources = List.copyOf(sources); }
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
    interface ExpertSource {
        List<ExpertPick> load(int season, int week, List<SleeperLiveWaiverTargetRosterContextAudit.TargetPlayer> players)
            throws InterruptedException;
    }

    interface MatchupSource {
        Map<String, String> load(int season, int week, List<SleeperLiveWaiverTargetRosterContextAudit.TargetPlayer> players)
            throws IOException, InterruptedException;
    }

    interface GameLockSource {
        Map<String, NflverseGameLockProvider.GameLockEvidence> load(
            int season,
            int week,
            List<SleeperLiveWaiverTargetRosterContextAudit.TargetPlayer> players)
            throws IOException, InterruptedException;
    }

    interface UsageSource {
        Map<String, NflverseRosterUsageProvider.UsageEvidence> load(int season, int week, Set<String> ids)
            throws IOException, InterruptedException;
    }

    interface NewsSource {
        Map<String, String> load(List<SleeperLiveWaiverTargetRosterContextAudit.TargetPlayer> players)
            throws IOException, InterruptedException;
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

    public record ProjectionHold(
        String sleeperPlayerId,
        String displayName,
        String rosterSlot,
        String lineupSlot,
        String status,
        String injuryStatus,
        String reason) {
        public ProjectionHold {
            sleeperPlayerId = requireText(sleeperPlayerId, "sleeperPlayerId");
            displayName = requireText(displayName, "displayName");
            rosterSlot = requireText(rosterSlot, "rosterSlot");
            lineupSlot = clean(lineupSlot);
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
        List<ProjectionHold> projectionHolds,
        String projectionProvenance,
        List<String> decisionEvidence,
        List<SwapReview> swapReviews, List<ExpertPick> expertPicks) {
        public RecommendationReport {
            decisionEvidence = List.copyOf(Objects.requireNonNull(decisionEvidence));
            swapReviews = List.copyOf(Objects.requireNonNull(swapReviews));
            expertPicks = List.copyOf(Objects.requireNonNull(expertPicks));
            if (!POLICY_ID.equals(policyId)) throw new IllegalArgumentException("unexpected policyId");
            if (season <= 0) throw new IllegalArgumentException("season must be positive");
            availabilityExclusions = List.copyOf(Objects.requireNonNull(
                availabilityExclusions, "availabilityExclusions must not be null"));
            projectionHolds = List.copyOf(Objects.requireNonNull(
                projectionHolds, "projectionHolds must not be null"));
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
                    || !availabilityExclusions.isEmpty() || !projectionHolds.isEmpty() || projectionProvenance != null
                    || !decisionEvidence.isEmpty() || !swapReviews.isEmpty() || !expertPicks.isEmpty()) {
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
                null, null, null, 0, null, null, null, List.of(), List.of(), null, List.of(), List.of(), List.of());
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
            List<ProjectionHold> projectionHolds,
            String projectionProvenance) {
            return new RecommendationReport(
                POLICY_ID, true, null, season, week, scoringBasis,
                sourceName, sourceSurface, projectionObservedAt, mappedActivePlayers,
                currentProjectedTotal, projectedGain, recommendation, availabilityExclusions,
                projectionHolds, projectionProvenance, List.of(), List.of(), List.of());
        }

        RecommendationReport withDecisionEvidence(List<String> evidence) {
            return new RecommendationReport(policyId, ready, reason, season, week, scoringBasis,
                sourceName, sourceSurface, projectionObservedAt, mappedActivePlayers, currentProjectedTotal,
                projectedGain, recommendation, availabilityExclusions, projectionHolds, projectionProvenance, evidence, swapReviews, expertPicks);
        }

        RecommendationReport withExpertPicks(List<ExpertPick> picks) {
            return new RecommendationReport(policyId, ready, reason, season, week, scoringBasis,
                sourceName, sourceSurface, projectionObservedAt, mappedActivePlayers, currentProjectedTotal,
                projectedGain, recommendation, availabilityExclusions, projectionHolds, projectionProvenance,
                decisionEvidence, swapReviews, picks);
        }

        RecommendationReport withSwapReviews(List<SwapReview> reviews) {
            return new RecommendationReport(policyId, ready, reason, season, week, scoringBasis,
                sourceName, sourceSurface, projectionObservedAt, mappedActivePlayers, currentProjectedTotal,
                projectedGain, recommendation, availabilityExclusions, projectionHolds, projectionProvenance, decisionEvidence, reviews, expertPicks);
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
