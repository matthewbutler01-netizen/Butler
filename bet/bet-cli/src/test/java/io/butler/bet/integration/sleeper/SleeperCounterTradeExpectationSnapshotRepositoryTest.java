package io.butler.bet.integration.sleeper;

import io.butler.bet.data.Database;
import io.butler.bet.data.DraftPickRepository;
import io.butler.bet.data.LeagueRepository;
import io.butler.bet.data.PlayerRepository;
import io.butler.bet.data.RosterRepository;
import io.butler.bet.data.TeamRepository;
import io.butler.bet.data.TradeCounterAuthorizationGrantRepository;
import io.butler.bet.domain.DraftPick;
import io.butler.bet.domain.League;
import io.butler.bet.domain.Player;
import io.butler.bet.domain.Roster;
import io.butler.bet.domain.Team;
import io.butler.bet.execution.TradeCounterManualHandoffCoordinator;
import io.butler.bet.intelligence.TradeAssetAnalyzer;
import io.butler.bet.intelligence.TradeCounterAuthorizationPolicy;
import io.butler.bet.intelligence.TradeCounterCandidateSelectionPolicy;
import io.butler.bet.intelligence.TradeCounterExecutionReadinessPolicy;
import io.butler.bet.intelligence.TradeCounterMaterializedPackagePolicy;
import io.butler.bet.intelligence.TradeCounterNegotiationMessagePolicy;
import io.butler.bet.intelligence.TradeCounterOpportunityPolicy;
import io.butler.bet.intelligence.TradeCounterProposalEnvelopePolicy;
import io.butler.bet.intelligence.TradeCounterProposalIdentityPolicy;
import io.butler.bet.intelligence.TradeCounterProposalPolicy;
import io.butler.bet.intelligence.TradeCounterSingleAssetCandidateAnalyzer;
import io.butler.bet.intelligence.TradeCounterValueTargetAnalyzer;
import io.butler.bet.intelligence.TradeFairnessPolicy;
import io.butler.bet.intelligence.TradeTeamPerspectiveRecommendationPolicy;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SleeperCounterTradeExpectationSnapshotRepositoryTest {
    private static final LocalDate AS_OF = LocalDate.of(2026, 9, 1);
    private static final Instant PRESENTED_AT = Instant.parse("2026-09-04T21:20:00Z");
    private static final Instant SNAPSHOTTED_AT = Instant.parse("2026-09-04T21:20:01Z");

    @TempDir
    Path tempDir;

    @Test
    void snapshotsExactProviderMovementFromDurableTradeHandoff() throws Exception {
        Fixture fixture = tradeFixture();
        var repository = new SleeperCounterTradeExpectationSnapshotRepository(fixture.database());

        var result = repository.snapshot(
            fixture.claimId(),
            "l1",
            "team-a",
            "team-b",
            fixture.artifacts().materialized().revisedSideA(),
            fixture.artifacts().materialized().revisedSideB(),
            SNAPSHOTTED_AT);

        assertEquals(SleeperCounterTradeExpectationSnapshotRepository.State.SNAPSHOTTED, result.state());
        var snapshot = result.snapshot();
        assertEquals("289646328504385536", snapshot.sleeperLeagueId());
        assertEquals(Set.of(1, 2), snapshot.rosterIds());
        assertEquals(Map.of("101", 2, "202", 1, "303", 1), snapshot.playerAdds());
        assertEquals(Map.of("101", 1, "202", 2, "303", 2), snapshot.playerDrops());
        assertEquals(Set.of(new SleeperReadOnlyClient.DraftPick("2027", 2, 1, 1, 2)), snapshot.draftPicks());
        assertEquals(SNAPSHOTTED_AT, snapshot.snapshottedAt());
        assertTrue(snapshot.movementJson().contains("\"rosterIds\":[1,2]"));
        assertTrue(snapshot.movementSha256().matches("[0-9a-f]{64}"));
        assertEquals(snapshot, repository.findByClaimId(fixture.claimId()).orElseThrow());
    }

    @Test
    void exactRepeatPreservesFirstImmutableSnapshot() throws Exception {
        Fixture fixture = tradeFixture();
        var repository = new SleeperCounterTradeExpectationSnapshotRepository(fixture.database());

        var first = repository.snapshot(
            fixture.claimId(), "l1", "team-a", "team-b",
            fixture.artifacts().materialized().revisedSideA(),
            fixture.artifacts().materialized().revisedSideB(), SNAPSHOTTED_AT);
        var second = repository.snapshot(
            fixture.claimId(), "l1", "team-a", "team-b",
            fixture.artifacts().materialized().revisedSideA(),
            fixture.artifacts().materialized().revisedSideB(), SNAPSHOTTED_AT.plusSeconds(90));

        assertEquals(SleeperCounterTradeExpectationSnapshotRepository.State.ALREADY_SNAPSHOTTED, second.state());
        assertEquals(first.snapshot().movementSha256(), second.snapshot().movementSha256());
        assertEquals(SNAPSHOTTED_AT, second.snapshot().snapshottedAt());
        assertEquals(first.snapshot().handoffId(), second.snapshot().handoffId());
    }

    @Test
    void messageHandoffCannotCreateTradeExpectationSnapshot() throws Exception {
        Fixture fixture = fixture(TradeCounterAuthorizationPolicy.Action.SEND_NEGOTIATION_MESSAGE);
        var repository = new SleeperCounterTradeExpectationSnapshotRepository(fixture.database());

        var result = repository.snapshot(
            fixture.claimId(), "l1", "team-a", "team-b",
            fixture.artifacts().materialized().revisedSideA(),
            fixture.artifacts().materialized().revisedSideB(), SNAPSHOTTED_AT);

        assertEquals(SleeperCounterTradeExpectationSnapshotRepository.State.NOT_AVAILABLE, result.state());
        assertTrue(result.reason().contains("trade handoff"));
        assertTrue(repository.findByClaimId(fixture.claimId()).isEmpty());
    }

    @Test
    void storedSnapshotRejectsDirectMutation() throws Exception {
        Fixture fixture = tradeFixture();
        var repository = new SleeperCounterTradeExpectationSnapshotRepository(fixture.database());
        repository.snapshot(
            fixture.claimId(), "l1", "team-a", "team-b",
            fixture.artifacts().materialized().revisedSideA(),
            fixture.artifacts().materialized().revisedSideB(), SNAPSHOTTED_AT);

        repository.initialize();
        try (var connection = fixture.database().openConnection();
             var statement = connection.createStatement()) {
            var exception = org.junit.jupiter.api.Assertions.assertThrows(
                java.sql.SQLException.class,
                () -> statement.executeUpdate(
                    "UPDATE sleeper_counter_trade_expectation_snapshots SET side_a_team_id='other'"));
            assertNotNull(exception.getMessage());
        }
    }

    private Fixture tradeFixture() throws Exception {
        return fixture(TradeCounterAuthorizationPolicy.Action.SUBMIT_COUNTER_TRADE);
    }

    private Fixture fixture(TradeCounterAuthorizationPolicy.Action action) throws Exception {
        Database database = new Database(tempDir.resolve("snapshot-" + action.name() + ".db"));
        database.initialize();
        seedSleeperIdentity(database);

        Artifacts artifacts = artifacts();
        var destination = action == TradeCounterAuthorizationPolicy.Action.SUBMIT_COUNTER_TRADE
            ? new TradeCounterAuthorizationPolicy.Destination(
                TradeCounterAuthorizationPolicy.DestinationType.LEAGUE, "l1")
            : new TradeCounterAuthorizationPolicy.Destination(
                TradeCounterAuthorizationPolicy.DestinationType.MANAGER, "manager-22");
        var request = TradeCounterAuthorizationPolicy.request(artifacts.identity(), action, destination);
        var grant = TradeCounterAuthorizationPolicy.authorize(request, request.requiredConfirmation()).grant();
        var grants = new TradeCounterAuthorizationGrantRepository(database);
        grants.initialize();
        grants.save(grant);
        var readiness = TradeCounterExecutionReadinessPolicy.assess(grant, false, true, artifacts.identity());
        var coordinated = new TradeCounterManualHandoffCoordinator(database).coordinate(
            grant, readiness, artifacts.identity(), artifacts.materialized(), artifacts.message(), PRESENTED_AT);
        assertTrue(coordinated.state() == TradeCounterManualHandoffCoordinator.State.HANDOFF_PRESENTED
            || coordinated.state() == TradeCounterManualHandoffCoordinator.State.HANDOFF_ALREADY_PRESENTED);
        return new Fixture(database, coordinated.claimId(), artifacts);
    }

    private static void seedSleeperIdentity(Database database) throws Exception {
        new LeagueRepository(database).save(new League("l1", "289646328504385536", "League", 2026));
        var teams = new TeamRepository(database);
        teams.save(new Team("team-a", "1", "l1", "Team A"));
        teams.save(new Team("team-b", "2", "l1", "Team B"));
        var players = new PlayerRepository(database);
        players.save(new Player("p1", "101", "P1", "WR", "CHI"));
        players.save(new Player("p2", "202", "P2", "RB", "DET"));
        players.save(new Player("p3", "303", "P3", "WR", "GB"));
        var rosters = new RosterRepository(database);
        rosters.save(new Roster("r1", null, "team-a", "p1", "STARTER"));
        rosters.save(new Roster("r2", null, "team-b", "p2", "STARTER"));
        rosters.save(new Roster("r3", null, "team-b", "p3", "BENCH"));
        new DraftPickRepository(database).save(
            new DraftPick("k1", "l1", 2027, 2, "team-a", "team-a", null));
    }

    private static Artifacts artifacts() {
        var proposal = new TradeCounterProposalPolicy.Proposal(
            1,
            TradeCounterSingleAssetCandidateAnalyzer.AdjustmentType.ADD_ASSET_TO_LOWER_PACKAGE,
            TradeCounterValueTargetAnalyzer.Side.SIDE_B,
            TradeCounterSingleAssetCandidateAnalyzer.AssetType.PLAYER,
            "p3",
            "P3",
            "team-b",
            "Team B",
            5.0,
            AS_OF,
            4.0,
            1.0,
            100.0,
            104.0,
            3.921568627,
            TradeFairnessPolicy.Classification.MARKET_FAIR);
        var result = new TradeCounterProposalPolicy.Result(
            TradeCounterProposalPolicy.POLICY_ID,
            TradeCounterOpportunityPolicy.POLICY_ID,
            TradeCounterCandidateSelectionPolicy.POLICY_ID,
            "l1", 2026, "source", AS_OF,
            TradeCounterProposalPolicy.Action.COUNTER,
            TradeCounterProposalPolicy.ReasonCode.UNIQUE_SELECTED_CANDIDATE,
            proposal);
        var envelope = TradeCounterProposalEnvelopePolicy.bind(
            result,
            TradeTeamPerspectiveRecommendationPolicy.Perspective.SIDE_A_TEAM,
            new TradeAssetAnalyzer.TradePackage(List.of("p1"), List.of("k1")),
            new TradeAssetAnalyzer.TradePackage(List.of("p2"), List.of()));
        var materialized = TradeCounterMaterializedPackagePolicy.materialize(envelope);
        var identity = TradeCounterProposalIdentityPolicy.identify(envelope, materialized);
        var message = TradeCounterNegotiationMessagePolicy.compose(envelope);
        return new Artifacts(materialized, identity, message);
    }

    private record Artifacts(
        TradeCounterMaterializedPackagePolicy.MaterializedCounter materialized,
        TradeCounterProposalIdentityPolicy.Identity identity,
        TradeCounterNegotiationMessagePolicy.MessageResult message) {}

    private record Fixture(Database database, String claimId, Artifacts artifacts) {}
}
