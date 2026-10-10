package io.butler.bet.sleeper;

import io.butler.bet.data.Database;
import io.butler.bet.data.LeagueRepository;
import io.butler.bet.data.LiveWaiverSnapshotRepository;
import io.butler.bet.data.LeagueScoringSettingsRepository;
import io.butler.bet.data.PlayerFantasyPositionRepository;
import io.butler.bet.data.PlayerRepository;
import io.butler.bet.domain.League;
import io.butler.bet.domain.Player;
import io.butler.bet.integration.SleeperWeeklyProjectionProvider;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SleeperLiveAutoFillLineupRecommendationBf825Test {
    private static final Instant PROJECTION_OBSERVED_AT = Instant.parse("2026-09-17T04:00:00Z");

    // Ordinary healthy-feed fixture: other tests explicitly inject missing,
    // contradictory, questionable or unavailable status to exercise holds.
    private static Map<String, SleeperPlayerAvailabilityProvider.PlayerAvailability>
    allActiveStatuses(java.util.Set<String> ids) {
        return ids.stream().collect(java.util.stream.Collectors.toUnmodifiableMap(
            id -> id,
            id -> new SleeperPlayerAvailabilityProvider.PlayerAvailability(id, "Active", "Healthy")));
    }

    @TempDir
    Path tempDir;

    @Test
    void explicitEmptyStarterProducesLegalFillAndNoSyntheticSlotDelta() throws Exception {
        Database database = initializedDatabase("league-empty-starter");
        var snapshot = snapshot(List.of(projection("s-qb", "20"), projection("s-wr-a", "10"), projection("s-wr-b", "15")));
        var original = rosterReport("league-empty-starter");
        var players = original.targetPlayers().stream().map(p -> "s-wr-a".equals(p.sleeperPlayerId())
            ? new SleeperLiveWaiverTargetRosterContextAudit.TargetPlayer(p.sleeperPlayerId(), "BENCH", null, null,
                p.butlerPlayerId(), p.displayName(), p.position(), p.nflTeam(), p.mappingState()) : p).toList();
        var emptyRoster = new SleeperLiveWaiverTargetRosterContextAudit.AuditReport(
            original.policyId(), original.leagueId(), original.marketSnapshotId(), original.waiverSnapshotId(),
            original.sleeperLeagueId(), original.providerSeason(), original.providerStatus(), original.providerLeg(),
            original.sleeperOwnerId(), original.ownerDisplayName(), original.ownerTeamName(), original.rosterId(),
            original.butlerTeamId(), original.butlerTeamName(), original.lineupSlots(), original.startingSlots(),
            original.candidateCount(), original.reviewableCandidateCount(), 3, 1, 2, 0, 0, 3, 0, players, List.of(1));
        var report = new SleeperLiveAutoFillLineupRecommendation(database, (season, week, scoring) -> snapshot,
            ids -> allActiveStatuses(ids), playersToCheck -> Map.of()).recommend(emptyRoster);
        assertTrue(report.ready());
        assertEquals(new BigDecimal("20"), report.currentProjectedTotal());
        assertEquals(new BigDecimal("35"), report.recommendation().projectedTotal());
        assertEquals(new BigDecimal("15"), report.projectedGain());
        assertTrue(report.recommendation().movesToBench().isEmpty());
        var fill = report.recommendation().assignments().get(1);
        assertEquals("0", fill.currentPlayerId());
        assertEquals("s-wr-b", fill.recommendedPlayerId());
        assertEquals(null, fill.currentProjectedPoints());
        assertEquals(null, fill.projectedGain());
        assertTrue(report.swapReviews().getFirst().reason().contains("Explicit empty starting slot"));
    }

    @Test
    void questionableBenchPlayerMayFillExplicitEmptySlotOnlyForHardLegalityReview() throws Exception {
        Database database = initializedDatabase("league-empty-questionable");
        var snapshot = snapshot(List.of(
            projection("s-qb", "20"), projection("s-wr-a", "10"), projection("s-wr-b", "15")));

        var original = rosterReport("league-empty-questionable");
        var players = original.targetPlayers().stream().map(p -> "s-wr-a".equals(p.sleeperPlayerId())
            ? new SleeperLiveWaiverTargetRosterContextAudit.TargetPlayer(
                p.sleeperPlayerId(), "BENCH", null, null,
                p.butlerPlayerId(), p.displayName(), p.position(), p.nflTeam(), p.mappingState())
            : p).toList();
        var emptyRoster = new SleeperLiveWaiverTargetRosterContextAudit.AuditReport(
            original.policyId(), original.leagueId(), original.marketSnapshotId(), original.waiverSnapshotId(),
            original.sleeperLeagueId(), original.providerSeason(), original.providerStatus(), original.providerLeg(),
            original.sleeperOwnerId(), original.ownerDisplayName(), original.ownerTeamName(), original.rosterId(),
            original.butlerTeamId(), original.butlerTeamName(), original.lineupSlots(), original.startingSlots(),
            original.candidateCount(), original.reviewableCandidateCount(), 3, 1, 2, 0, 0, 3, 0, players, List.of(1));

        var report = new SleeperLiveAutoFillLineupRecommendation(
            database,
            (season, week, scoring) -> snapshot,
            ids -> Map.of(
                "s-wr-a",
                new SleeperPlayerAvailabilityProvider.PlayerAvailability(
                    "s-wr-a", "Active", "Out"),
                "s-wr-b",
                new SleeperPlayerAvailabilityProvider.PlayerAvailability(
                    "s-wr-b", "Active", "Questionable", "Ankle", "Limited", PROJECTION_OBSERVED_AT)))
            .recommend(emptyRoster);

        assertTrue(report.ready());
        assertEquals("s-wr-b", report.recommendation().assignments().stream()
            .filter(assignment -> assignment.starterOrdinal() == 1)
            .findFirst().orElseThrow().recommendedPlayerId());
        assertTrue(report.recommendation().promotions().stream()
            .anyMatch(player -> "s-wr-b".equals(player.playerId())));
        assertFalse(report.projectionHolds().stream()
            .anyMatch(hold -> "s-wr-b".equals(hold.sleeperPlayerId())));
        assertTrue(report.decisionEvidence().stream().anyMatch(evidence ->
            evidence.contains("Hard-lineup-legality review for Receiver B")
                && evidence.contains("Questionable")
                && evidence.contains("Manager approval is required")));
    }

    @Test
    void expertSitStarterGetsLowerProjectedBenchComparisonWithoutChangingLineup() throws Exception {
        Database database = initializedDatabase("league-replacement");
        var snapshot = snapshot(List.of(projection("s-qb", "20"), projection("s-wr-a", "10"), projection("s-wr-b", "8")));
        var pick = new SleeperLiveAutoFillLineupRecommendation.ExpertPick("s-wr-a", "Different source name", "WR", "SIT",
            "Test Author", "2026-09-30T17:00:00Z", "2026-09-30T18:00:00Z", "2026-10-01T07:00:00Z",
            "https://www.nfl.com/news/test", "One author");
        var report = new SleeperLiveAutoFillLineupRecommendation(database, (season, week, scoring) -> snapshot,
            ids -> allActiveStatuses(ids), players -> Map.of(), players -> Map.of(), (season, week, ids) -> Map.of(),
            (season, week, players) -> Map.of("s-wr-b", "Candidate matchup"), (season, week, players) -> List.of(pick))
            .recommend(rosterReport("league-replacement"));
        assertTrue(report.ready());
        assertTrue(report.recommendation().promotions().isEmpty());
        assertEquals(BigDecimal.ZERO, report.projectedGain());
        var comparisons = report.swapReviews().stream().filter(r -> "MANUAL_REVIEW_REPLACEMENT".equals(r.status())).toList();
        assertEquals(1, comparisons.size());
        assertEquals("-2", comparisons.get(0).projectedGain());
        assertEquals("Candidate matchup", comparisons.get(0).proposedMatchup());
        assertEquals("SIT by Test Author", comparisons.get(0).currentExpert());
        assertEquals("unverified", comparisons.get(0).proposedExpert());
        assertTrue(comparisons.get(0).reason().contains("SIT by Test Author"));
        assertTrue(comparisons.get(0).reason().contains("candidate expert: unverified"));
    }

    @Test
    void heldStarterComparisonDoesNotInventProjectionDelta() throws Exception {
        Database database = initializedDatabase("league-held-starter-review");
        var snapshot = snapshot(List.of(projection("s-qb", "20"), projection("s-wr-a", "10"), projection("s-wr-b", "8")));
        var pick = new SleeperLiveAutoFillLineupRecommendation.ExpertPick("s-wr-a", "Starter WR", "WR", "START",
            "Test Author", "", "", "", "https://www.nfl.com/news/test", "One author");
        var report = new SleeperLiveAutoFillLineupRecommendation(database, (season, week, scoring) -> snapshot,
            ids -> Map.of("s-wr-a", new SleeperPlayerAvailabilityProvider.PlayerAvailability("s-wr-a", "Active", "Questionable")),
            players -> Map.of(), players -> Map.of(), (season, week, ids) -> Map.of(),
            (season, week, players) -> Map.of(), (season, week, players) -> List.of(pick))
            .recommend(rosterReport("league-held-starter-review"));
        assertTrue(report.ready());
        var comparison = report.swapReviews().stream().filter(r -> "MANUAL_REVIEW_REPLACEMENT".equals(r.status())).findFirst().orElseThrow();
        assertEquals("Unavailable", comparison.projectedGain());
        assertEquals("START by Test Author", comparison.currentExpert());
        assertEquals("WR", comparison.slot());
        assertTrue(report.recommendation().promotions().isEmpty());
        assertEquals(new BigDecimal("20"), report.currentProjectedTotal());
    }

    @Test
    void heldBenchCandidateCannotBecomeExpertReplacement() throws Exception {
        Database database = initializedDatabase("league-replacement-held");
        var snapshot = snapshot(List.of(projection("s-qb", "20"), projection("s-wr-a", "10"), projection("s-wr-b", "15")));
        var pick = new SleeperLiveAutoFillLineupRecommendation.ExpertPick("s-wr-a", "Starter WR", "WR", "SIT",
            "Test Author", "", "", "", "https://www.nfl.com/news/test", "One author");
        var report = new SleeperLiveAutoFillLineupRecommendation(database, (season, week, scoring) -> snapshot,
            ids -> Map.of("s-wr-b", new SleeperPlayerAvailabilityProvider.PlayerAvailability("s-wr-b", "Active", "Questionable")),
            players -> Map.of(), players -> Map.of(), (season, week, ids) -> Map.of(),
            (season, week, players) -> Map.of(), (season, week, players) -> List.of(pick))
            .recommend(rosterReport("league-replacement-held"));
        assertTrue(report.swapReviews().isEmpty());
        assertTrue(report.decisionEvidence().stream().anyMatch(e -> e.contains("no eligible, scoreable bench alternative")));
    }

    @Test
    void attributedStartPickDoesNotLiftAvailabilityHoldOrChangePoints() throws Exception {
        Database database = initializedDatabase("league-expert-hold");
        var snapshot = snapshot(List.of(projection("s-qb", "20"), projection("s-wr-a", "10"), projection("s-wr-b", "15")));
        var pick = new SleeperLiveAutoFillLineupRecommendation.ExpertPick("s-wr-b", "Bench WR", "WR", "START",
            "Test Author", "2026-09-30T17:00:00Z", "2026-09-30T18:00:00Z", "2026-10-01T07:00:00Z",
            "https://www.nfl.com/news/test", "One author; review required");
        var report = new SleeperLiveAutoFillLineupRecommendation(database, (season, week, scoring) -> snapshot,
            ids -> Map.of("s-wr-b", new SleeperPlayerAvailabilityProvider.PlayerAvailability("s-wr-b", "Active", "Questionable")),
            players -> Map.of(), players -> Map.of(), (season, week, ids) -> Map.of(),
            (season, week, players) -> Map.of(), (season, week, players) -> List.of(pick))
            .recommend(rosterReport("league-expert-hold"));
        assertTrue(report.ready());
        assertEquals(List.of(pick), report.expertPicks());
        assertEquals(new BigDecimal("30"), report.currentProjectedTotal());
        assertEquals(new BigDecimal("30"), report.recommendation().projectedTotal());
        assertEquals(BigDecimal.ZERO, report.projectedGain());
        assertTrue(report.recommendation().promotions().isEmpty());
        assertTrue(report.projectionHolds().stream().anyMatch(h -> "s-wr-b".equals(h.sleeperPlayerId())));
    }

    @Test
    void benchExplicitlyUnavailableWithoutProjectionDoesNotBlockRecommendation() throws Exception {
        Database database = initializedDatabase("league-bench-out");
        var snapshot = snapshot(List.of(projection("s-qb", "20"), projection("s-wr-a", "10")));

        var report = new SleeperLiveAutoFillLineupRecommendation(
            database,
            (season, week, scoring) -> snapshot,
            ids -> Map.of(
                "s-wr-b",
                new SleeperPlayerAvailabilityProvider.PlayerAvailability("s-wr-b", "Inactive", null)))
            .recommend(rosterReport("league-bench-out"));

        assertTrue(report.ready());
        assertEquals(new BigDecimal("30"), report.currentProjectedTotal());
        assertEquals(new BigDecimal("30"), report.recommendation().projectedTotal());
        assertEquals(BigDecimal.ZERO, report.projectedGain());
        assertTrue(report.recommendation().promotions().isEmpty());
        assertEquals(1, report.availabilityExclusions().size());
        assertEquals("s-wr-b", report.availabilityExclusions().getFirst().sleeperPlayerId());
        assertTrue(report.availabilityExclusions().getFirst().reason().contains("did not synthesize a zero projection"));
    }

    @Test
    void starterExplicitlyUnavailableWithoutProjectionIsReplacedByEligibleBenchPlayer() throws Exception {
        Database database = initializedDatabase("league-starter-out");
        var snapshot = snapshot(List.of(projection("s-qb", "20"), projection("s-wr-b", "15")));

        var report = new SleeperLiveAutoFillLineupRecommendation(
            database,
            (season, week, scoring) -> snapshot,
            ids -> {
                var statuses = new java.util.LinkedHashMap<>(allActiveStatuses(ids));
                statuses.put("s-wr-a", new SleeperPlayerAvailabilityProvider.PlayerAvailability(
                    "s-wr-a", "Commissioner Exempt", null));
                return Map.copyOf(statuses);
            })
            .recommend(rosterReport("league-starter-out"));

        assertTrue(report.ready());
        assertEquals(new BigDecimal("20"), report.currentProjectedTotal());
        assertEquals(new BigDecimal("35"), report.recommendation().projectedTotal());
        assertEquals(new BigDecimal("15"), report.projectedGain());
        assertEquals("s-wr-b", report.recommendation().assignments().get(1).recommendedPlayerId());
        assertEquals(List.of("s-wr-a"), report.recommendation().movesToBench().stream()
            .map(player -> player.playerId()).toList());
        assertEquals(List.of("s-wr-b"), report.recommendation().promotions().stream()
            .map(player -> player.playerId()).toList());
        assertEquals("Commissioner Exempt", report.availabilityExclusions().getFirst().status());
    }

    @Test
    void ambiguousAvailabilityCreatesProjectionHoldInsteadOfBlockingOtherScoreableSlots() throws Exception {
        Database database = initializedDatabase("league-ambiguous");
        var snapshot = snapshot(List.of(projection("s-qb", "20"), projection("s-wr-b", "15")));

        List<SleeperPlayerAvailabilityProvider.PlayerAvailability> ambiguous = List.of(
            new SleeperPlayerAvailabilityProvider.PlayerAvailability("s-wr-a", "Active", null),
            new SleeperPlayerAvailabilityProvider.PlayerAvailability("s-wr-a", null, "Questionable"),
            new SleeperPlayerAvailabilityProvider.PlayerAvailability("s-wr-a", null, "Doubtful"),
            new SleeperPlayerAvailabilityProvider.PlayerAvailability("s-wr-a", "Unknown", null));

        for (var availability : ambiguous) {
            var report = new SleeperLiveAutoFillLineupRecommendation(
                database,
                (season, week, scoring) -> snapshot,
                ids -> Map.of("s-wr-a", availability))
                .recommend(rosterReport("league-ambiguous"));

            assertTrue(report.ready());
            assertEquals(1, report.projectionHolds().size());
            assertEquals("s-wr-a", report.projectionHolds().getFirst().sleeperPlayerId());
            assertTrue(report.projectionHolds().getFirst().reason().contains("preserved the player's current lineup state"));
            assertEquals(new BigDecimal("20"), report.currentProjectedTotal());
            assertEquals(new BigDecimal("20"), report.recommendation().projectedTotal());
            assertEquals(BigDecimal.ZERO, report.projectedGain());
        }
    }

    @Test
    void missingOrUnknownPlayerStatusWithholdsHighProjectionPromotion() throws Exception {
        for (String status : List.of("Reserved", "Status_Unknown")) {
            String leagueId = "league-unverified-status-" + status;
            Database database = initializedDatabase(leagueId);
            var snapshot = snapshot(List.of(
                projection("s-qb", "20"), projection("s-wr-a", "10"), projection("s-wr-b", "20")));
            var report = new SleeperLiveAutoFillLineupRecommendation(
                database,
                (season, week, scoring) -> snapshot,
                ids -> Map.of("s-wr-b", new SleeperPlayerAvailabilityProvider.PlayerAvailability(
                    "s-wr-b", status, null)))
                .recommend(rosterReport(leagueId));
            assertTrue(report.ready(), "The unchanged lineup remains reviewable.");
            assertTrue(report.recommendation().promotions().isEmpty(),
                "Unrecognized injury status must not promote the higher-projected bench player.");
            assertTrue(report.projectionHolds().stream().anyMatch(h ->
                "s-wr-b".equals(h.sleeperPlayerId())
                    && h.reason().contains("Availability hold")));
        }
    }

    @Test
    void missingExactPlayerMapRowsCannotAuthorizeHighProjectionSwap() throws Exception {
        Database database = initializedDatabase("league-missing-swap-status");
        var snapshot = snapshot(List.of(
            projection("s-qb", "20"), projection("s-wr-a", "10"), projection("s-wr-b", "20")));
        var report = new SleeperLiveAutoFillLineupRecommendation(
            database, (season, week, scoring) -> snapshot,
            ids -> Map.of("s-wr-a",
                new SleeperPlayerAvailabilityProvider.PlayerAvailability("s-wr-a", "Active", "Healthy")))
            .recommend(rosterReport("league-missing-swap-status"));
        assertFalse(report.ready(), "Missing proposed bench player's exact live status blocks the swap.");
        assertTrue(report.reason().contains("BF-1066 BLOCKED"));
        assertTrue(report.reason().contains("withheld the swap"));
        assertTrue(report.recommendation() == null);
    }

    @Test
    void currentInjurySourceFailureWithholdsOtherwiseActionableLineupSwap() throws Exception {
        Database database = initializedDatabase("league-injury-outage-swap");
        var snapshot = snapshot(List.of(
            projection("s-qb", "20"),
            projection("s-wr-a", "10"),
            projection("s-wr-b", "20")));

        var report = new SleeperLiveAutoFillLineupRecommendation(
            database,
            (season, week, scoring) -> snapshot,
            ids -> { throw new IOException("synthetic injury source outage"); })
            .recommend(rosterReport("league-injury-outage-swap"));

        assertFalse(report.ready(),
            "Missing current injury status must not permit a projected bench promotion.");
        assertTrue(report.reason().contains("BF-1063 BLOCKED"));
        assertTrue(report.reason().contains("did not prepare a player swap"));
        assertTrue(report.recommendation() == null);
    }

    @Test
    void availabilityProviderFailureCreatesProjectionHoldInsteadOfBlockingReview() throws Exception {
        Database database = initializedDatabase("league-provider-failure");
        var snapshot = snapshot(List.of(projection("s-qb", "20"), projection("s-wr-b", "15")));

        var report = new SleeperLiveAutoFillLineupRecommendation(
            database,
            (season, week, scoring) -> snapshot,
            ids -> { throw new IOException("provider down"); })
            .recommend(rosterReport("league-provider-failure"));

        assertTrue(report.ready());
        assertEquals(1, report.projectionHolds().size());
        assertTrue(report.projectionHolds().getFirst().reason().contains("availability evidence is unavailable"));
        assertTrue(report.projectionHolds().getFirst().reason().contains("provider down"));
        assertTrue(report.projectionHolds().getFirst().reason().contains("preserved the player's current lineup state"));
    }

    @Test
    void missingExactAvailabilityCreatesProjectionHoldWithoutUsingWrongIdentity() throws Exception {
        Database database = initializedDatabase("league-exact-id");
        var snapshot = snapshot(List.of(projection("s-qb", "20"), projection("s-wr-b", "15")));

        var report = new SleeperLiveAutoFillLineupRecommendation(
            database,
            (season, week, scoring) -> snapshot,
            ids -> Map.of(
                "wrong-id",
                new SleeperPlayerAvailabilityProvider.PlayerAvailability("wrong-id", "Inactive", null)))
            .recommend(rosterReport("league-exact-id"));

        assertTrue(report.ready());
        assertEquals(1, report.projectionHolds().size());
        assertEquals("s-wr-a", report.projectionHolds().getFirst().sleeperPlayerId());
        assertTrue(report.projectionHolds().getFirst().reason().contains("availability evidence has no exact match"));
        assertFalse(report.projectionHolds().getFirst().reason().contains("wrong-id"));
    }

    @Test
    void newlyAcquiredPlayerUsesExactAuditedRosterEligibilityWithoutUpdatingImportedMetadata() throws Exception {
        Database database = initializedDatabase("league-acquisition");
        new PlayerFantasyPositionRepository(database).replace("butler-wr-b", List.of());
        var repository = new LiveWaiverSnapshotRepository(database);
        repository.save(new LiveWaiverSnapshotRepository.Snapshot(
            "waiver", "league-acquisition", "sleeper-league-acquisition", 2026, "in_season", 2,
            "Sleeper", "proof", "eligibility", PROJECTION_OBSERVED_AT, 1, 1, 1, 0, 0, 0),
            List.of(new LiveWaiverSnapshotRepository.Entry(
                "s-wr-b", "Receiver B", "WR", List.of("WR"), "WAS", "Active",
                true, false, false, "ROSTERED")));
        var projections = snapshot(List.of(
            projection("s-qb", "20"), projection("s-wr-a", "10"), projection("s-wr-b", "15")));
        var report = new SleeperLiveAutoFillLineupRecommendation(database,
            (season, week, scoring) -> projections, ids -> allActiveStatuses(ids))
            .recommend(rosterReport("league-acquisition"));
        assertTrue(report.ready());
        assertEquals("s-wr-b", report.recommendation().assignments().get(1).recommendedPlayerId());
        assertTrue(new PlayerFantasyPositionRepository(database).findByPlayerId("butler-wr-b").isEmpty());
        assertTrue(repository.rosterFantasyPositions("wrong-frame", "league-acquisition",
            "sleeper-league-acquisition", 2026, "s-wr-b").isEmpty());
        assertTrue(repository.rosterFantasyPositions("waiver", "other-league",
            "sleeper-league-acquisition", 2026, "s-wr-b").isEmpty());
        assertTrue(repository.rosterFantasyPositions("waiver", "league-acquisition",
            "sleeper-league-acquisition", 2025, "s-wr-b").isEmpty());
    }

    @Test
    void missingEligibilityStillBlocksInsteadOfInferringNominalPosition() throws Exception {
        Database database = initializedDatabase("league-missing-eligibility");
        new PlayerFantasyPositionRepository(database).replace("butler-wr-b", List.of());
        var projections = snapshot(List.of(
            projection("s-qb", "20"), projection("s-wr-a", "10"), projection("s-wr-b", "15")));
        var report = new SleeperLiveAutoFillLineupRecommendation(database,
            (season, week, scoring) -> projections).recommend(rosterReport("league-missing-eligibility"));
        assertFalse(report.ready());
        assertTrue(report.reason().contains("fantasy-position eligibility for Receiver B"));
    }

    @Test
    void projectedPlayerWithCurrentInjuryCannotBeRecommendedAsAnUnqualifiedStart() throws Exception {
        Database database = initializedDatabase("league-projected-injury");
        var projections = snapshot(List.of(
            projection("s-qb", "20"), projection("s-wr-a", "10"), projection("s-wr-b", "15")));
        for (String injury : List.of("Out", "Questionable", "Doubtful", "IR")) {
            var report = new SleeperLiveAutoFillLineupRecommendation(database,
                (season, week, scoring) -> projections,
                ids -> {
                    assertEquals(java.util.Set.of("s-qb", "s-wr-a", "s-wr-b"), ids);
                    return Map.of("s-wr-b", new SleeperPlayerAvailabilityProvider.PlayerAvailability(
                        "s-wr-b", "Active", injury));
                }).recommend(rosterReport("league-projected-injury"));
            assertTrue(report.ready());
            assertTrue(report.recommendation().promotions().isEmpty());
            assertEquals(new BigDecimal("30"), report.recommendation().projectedTotal());
            if (injury.equals("Questionable") || injury.equals("Doubtful")) {
                assertEquals(injury, report.projectionHolds().getFirst().injuryStatus());
                assertTrue(report.projectionHolds().getFirst().reason().contains("Pending clearance"));
            } else {
                assertEquals(injury, report.availabilityExclusions().getFirst().injuryStatus());
            }
        }
    }

    @Test
    void questionableStarterIsHeldWhileHealthySlotsStillImprove() throws Exception {
        Database database = initializedDatabase("league-starter-injury-hold");
        var projections = snapshot(List.of(
            projection("s-qb", "20"), projection("s-wr-a", "10"), projection("s-wr-b", "15")));
        var report = new SleeperLiveAutoFillLineupRecommendation(database,
            (season, week, scoring) -> projections,
            ids -> {
                var statuses = new java.util.LinkedHashMap<>(allActiveStatuses(ids));
                statuses.put("s-qb", new SleeperPlayerAvailabilityProvider.PlayerAvailability(
                    "s-qb", "Active", "Questionable", "Chest", "Limited", PROJECTION_OBSERVED_AT));
                return Map.copyOf(statuses);
            })
            .recommend(rosterReport("league-starter-injury-hold"));
        assertTrue(report.ready());
        assertEquals(new BigDecimal("10"), report.currentProjectedTotal());
        assertEquals(new BigDecimal("15"), report.recommendation().projectedTotal());
        assertEquals("s-wr-b", report.recommendation().promotions().getFirst().playerId());
        assertEquals("s-qb", report.projectionHolds().getFirst().sleeperPlayerId());
        assertTrue(report.projectionHolds().getFirst().reason().contains("Limited"));
        assertTrue(report.projectionHolds().getFirst().reason().contains("checked="));
    }

    @Test
    void sourcedNewsTriggersReviewWithoutInventingAnOutDesignation() throws Exception {
        Database database = initializedDatabase("league-news-hold");
        var projections = snapshot(List.of(
            projection("s-qb", "20"), projection("s-wr-a", "10"), projection("s-wr-b", "15")));
        var report = new SleeperLiveAutoFillLineupRecommendation(database,
            (season, week, scoring) -> projections, ids -> allActiveStatuses(ids),
            players -> Map.of("s-wr-b", "Recent injury headline; source=https://www.espn.com/example"))
            .recommend(rosterReport("league-news-hold"));
        assertTrue(report.ready());
        assertTrue(report.recommendation().promotions().isEmpty());
        assertEquals(1, report.projectionHolds().size());
        assertTrue(report.availabilityExclusions().isEmpty());
        assertTrue(report.projectionHolds().getFirst().reason().contains("https://www.espn.com/example"));
    }

    @Test
    void swapEvidenceUsesCompletedWeeksAndPreservesLegacyUsageGaps() throws Exception {
        Database database = initializedDatabase("league-decision-evidence");
        var repository = new io.butler.bet.data.PlayerWeekProductionRepository(database);
        var date = java.time.LocalDate.now(java.time.ZoneOffset.UTC).minusDays(1);
        repository.save(io.butler.bet.domain.PlayerWeekProduction.create(
            "butler-wr-b", 2026, 1, 0, 0, 0, 20, 0, 3, 30, 0, 0, "nflverse", date));
        repository.save(io.butler.bet.domain.PlayerWeekProduction.create(
            "butler-wr-b", 2026, 2, 0, 0, 0, 999, 0, 99, 999, 0, 0, "nflverse", date));
        repository.save(io.butler.bet.domain.PlayerWeekProduction.create(
            "butler-wr-b", 2026, 1, 0, 0, 0, 888, 0, 88, 888, 0, 0, "nflverse", date.plusDays(2)));
        var projections = snapshot(List.of(
            projection("s-qb", "20"), projection("s-wr-a", "10"), projection("s-wr-b", "15")));
        var report = new SleeperLiveAutoFillLineupRecommendation(database,
            (season, week, scoring) -> projections, ids -> allActiveStatuses(ids))
            .recommend(rosterReport("league-decision-evidence"));
        assertTrue(report.ready());
        assertEquals(3, report.decisionEvidence().size());
        String evidence = report.decisionEvidence().getFirst();
        assertTrue(evidence.contains("receptions=3"));
        assertTrue(evidence.contains("carries=unavailable in this schema"));
        assertTrue(evidence.contains("missing data is not zero usage"));
        assertTrue(evidence.contains("expert start/sit advice: not verified"));
        assertFalse(evidence.contains("999"));
        assertFalse(evidence.contains("888"));
    }

    @Test
    void publicCommentaryAppearsAsContextWithoutFabricatingConsensusOrPoints() throws Exception {
        Database database = initializedDatabase("league-public-analysis");
        var projections = snapshot(List.of(
            projection("s-qb", "20"), projection("s-wr-a", "10"), projection("s-wr-b", "15")));
        var report = new SleeperLiveAutoFillLineupRecommendation(database,
            (season, week, scoring) -> projections, ids -> allActiveStatuses(ids), players -> Map.of(),
            players -> Map.of("s-wr-b", "ESPN public analysis; source=https://www.espn.com/example; author=Example Writer"))
            .recommend(rosterReport("league-public-analysis"));
        assertTrue(report.ready());
        assertEquals(new BigDecimal("35"), report.recommendation().projectedTotal());
        assertTrue(report.decisionEvidence().get(2).contains("Example Writer"));
        assertTrue(report.decisionEvidence().get(2).contains("https://www.espn.com/example"));
        assertTrue(report.decisionEvidence().get(2).contains("Current player: no matched commentary"));
    }

    @Test
    void verifiedUsageRiskPreventsHigherProjectedBenchPromotion() throws Exception {
        Database database = initializedDatabase("league-usage-risk");
        var projections = snapshot(List.of(
            projection("s-qb", "20"), projection("s-wr-a", "10"), projection("s-wr-b", "15")));
        var report = new SleeperLiveAutoFillLineupRecommendation(database,
            (season, week, scoring) -> projections, ids -> allActiveStatuses(ids), players -> Map.of(), players -> Map.of(),
            (season, week, ids) -> Map.of("s-wr-b",
                new NflverseRosterUsageProvider.UsageEvidence(true, "Verified snap/workload decline; source=example")))
            .recommend(rosterReport("league-usage-risk"));
        assertTrue(report.ready());
        assertTrue(report.recommendation().promotions().isEmpty());
        assertEquals(BigDecimal.ZERO, report.projectedGain());
        assertEquals(1, report.projectionHolds().size());
        assertTrue(report.projectionHolds().getFirst().reason().contains("Usage review hold"));
    }

    @Test
    void unavailableUsageLeavesProposalExplicitlyPendingManualReview() throws Exception {
        Database database = initializedDatabase("league-usage-failure");
        var projections = snapshot(List.of(
            projection("s-qb", "20"), projection("s-wr-a", "10"), projection("s-wr-b", "15")));
        var report = new SleeperLiveAutoFillLineupRecommendation(database,
            (season, week, scoring) -> projections, ids -> allActiveStatuses(ids), players -> Map.of(), players -> Map.of(),
            (season, week, ids) -> { throw new IOException("source down"); })
            .recommend(rosterReport("league-usage-failure"));
        assertTrue(report.ready());
        assertEquals(new BigDecimal("5"), report.projectedGain());
        assertTrue(report.decisionEvidence().get(1).contains("Manual review required"));
        assertTrue(report.decisionEvidence().get(1).contains("role evidence unverified"));
        assertEquals("MANUAL_REVIEW_USAGE_GAP", report.swapReviews().getFirst().status());
    }

    @Test
    void closeProjectionEdgeCannotPromoteBenchPlayerAgainstOpposingUsageTrends() throws Exception {
        Database database = initializedDatabase("league-close-usage-conflict");
        var projections = snapshot(List.of(
            projection("s-qb", "20"), projection("s-wr-a", "10"), projection("s-wr-b", "10.25")));
        var report = new SleeperLiveAutoFillLineupRecommendation(database,
            (season, week, scoring) -> projections, ids -> allActiveStatuses(ids), players -> Map.of(), players -> Map.of(),
            (season, week, ids) -> Map.of("s-wr-a", workload(6, 9), "s-wr-b", workload(7, 2)))
            .recommend(rosterReport("league-close-usage-conflict"));
        assertTrue(report.ready());
        assertTrue(report.recommendation().promotions().isEmpty());
        assertEquals(BigDecimal.ZERO, report.projectedGain());
        assertEquals("s-wr-b", report.projectionHolds().getFirst().sleeperPlayerId());
        assertEquals("WITHHELD_USAGE_CONFLICT", report.swapReviews().getFirst().status());
        assertEquals("0.25", report.swapReviews().getFirst().projectedGain());
        assertTrue(report.swapReviews().getFirst().currentUsage().contains("targets 9"));
        assertTrue(report.swapReviews().getFirst().proposedUsage().contains("targets 2"));
    }

    @Test
    void largerProjectionEdgeRetainsManualProposalWithoutSyntheticPointAdjustment() throws Exception {
        Database database = initializedDatabase("league-large-usage-conflict");
        var projections = snapshot(List.of(
            projection("s-qb", "20"), projection("s-wr-a", "10"), projection("s-wr-b", "15")));
        var report = new SleeperLiveAutoFillLineupRecommendation(database,
            (season, week, scoring) -> projections, ids -> allActiveStatuses(ids), players -> Map.of(), players -> Map.of(),
            (season, week, ids) -> Map.of("s-wr-a", workload(6, 9), "s-wr-b", workload(7, 2)))
            .recommend(rosterReport("league-large-usage-conflict"));
        assertEquals(new BigDecimal("5"), report.projectedGain());
        assertTrue(report.projectionHolds().isEmpty());
        assertEquals("MANUAL_REVIEW_PROJECTION_PROPOSAL", report.swapReviews().getFirst().status());
    }

    private static NflverseRosterUsageProvider.UsageEvidence workload(int beforeTargets, int afterTargets) {
        return new NflverseRosterUsageProvider.UsageEvidence(false, "Observed workload", List.of(
            new NflverseRosterUsageProvider.WeekUsage(2, 0, beforeTargets, 50, .8),
            new NflverseRosterUsageProvider.WeekUsage(3, 0, afterTargets, 50, .8)), "2026-10-01T06:00:00Z");
    }

    private Database initializedDatabase(String leagueId) throws Exception {
        Database database = new Database(tempDir.resolve(leagueId + ".db"));
        database.initialize();
        new LeagueRepository(database).save(new League(leagueId, "sleeper-" + leagueId, "Test League", 2026));
        new LeagueScoringSettingsRepository(database).replace(leagueId, Map.of("rec", 1.0));
        savePlayer(database, "butler-qb", "s-qb", "Quarter Back", "QB", "CHI", List.of("QB"));
        savePlayer(database, "butler-wr-a", "s-wr-a", "Receiver A", "WR", "JAX", List.of("WR"));
        savePlayer(database, "butler-wr-b", "s-wr-b", "Receiver B", "WR", "WAS", List.of("WR"));
        return database;
    }

    private static void savePlayer(
        Database database,
        String butlerId,
        String sleeperId,
        String name,
        String position,
        String team,
        List<String> fantasyPositions) throws Exception {
        new PlayerRepository(database).save(new Player(butlerId, sleeperId, name, position, team));
        new PlayerFantasyPositionRepository(database).replace(butlerId, fantasyPositions);
    }

    private static SleeperWeeklyProjectionProvider.ProjectionSnapshot snapshot(
        List<SleeperWeeklyProjectionProvider.Projection> projections) {
        return new SleeperWeeklyProjectionProvider.ProjectionSnapshot(
            SleeperWeeklyProjectionProvider.SOURCE_NAME,
            "projections/nfl/2026/2?season_type=regular",
            2026,
            2,
            SleeperWeeklyProjectionProvider.ScoringBasis.PPR,
            PROJECTION_OBSERVED_AT,
            projections);
    }

    private static SleeperWeeklyProjectionProvider.Projection projection(String id, String points) {
        return new SleeperWeeklyProjectionProvider.Projection(id, new BigDecimal(points));
    }

    private static SleeperLiveWaiverTargetRosterContextAudit.AuditReport rosterReport(String leagueId) {
        List<SleeperLiveWaiverTargetRosterContextAudit.TargetPlayer> players = List.of(
            new SleeperLiveWaiverTargetRosterContextAudit.TargetPlayer(
                "s-qb", "STARTER", 0, "QB", "butler-qb", "Quarter Back", "QB", "CHI", "EXACT_CANONICAL"),
            new SleeperLiveWaiverTargetRosterContextAudit.TargetPlayer(
                "s-wr-a", "STARTER", 1, "WR", "butler-wr-a", "Receiver A", "WR", "JAX", "EXACT_CANONICAL"),
            new SleeperLiveWaiverTargetRosterContextAudit.TargetPlayer(
                "s-wr-b", "BENCH", null, null, "butler-wr-b", "Receiver B", "WR", "WAS", "EXACT_CANONICAL"));
        return new SleeperLiveWaiverTargetRosterContextAudit.AuditReport(
            SleeperLiveWaiverTargetRosterContextAudit.POLICY_ID,
            leagueId,
            "market",
            "waiver",
            "sleeper-" + leagueId,
            2026,
            "in_season",
            2,
            "owner",
            "Owner",
            "Team",
            1,
            "butler-team",
            "Team",
            List.of("QB", "WR", "BN"),
            List.of("QB", "WR"),
            1,
            1,
            3,
            2,
            1,
            0,
            0,
            3,
            0,
            players);
    }
}
