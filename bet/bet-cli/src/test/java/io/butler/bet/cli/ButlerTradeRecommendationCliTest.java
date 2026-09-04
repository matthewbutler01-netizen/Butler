package io.butler.bet.cli;

import io.butler.bet.intelligence.LeagueCompetitiveTierPolicy;
import io.butler.bet.intelligence.LeagueFutureCapitalTierAnalyzer;
import io.butler.bet.intelligence.LeagueFutureCapitalTierPolicy;
import io.butler.bet.intelligence.LeaguePositionalPressureAnalyzer;
import io.butler.bet.intelligence.LeaguePositionalPressurePolicy;
import io.butler.bet.intelligence.LeagueRosterStrengthTierPolicy;
import io.butler.bet.intelligence.LeagueTeamPostureAnalyzer;
import io.butler.bet.intelligence.TeamPosturePolicy;
import io.butler.bet.intelligence.TradeAssetAnalyzer;
import io.butler.bet.intelligence.TradeAssetPositionalContextAnalyzer;
import io.butler.bet.intelligence.TradeAssetStrategicContextAnalyzer;
import io.butler.bet.intelligence.TradeFairnessPolicy;
import io.butler.bet.intelligence.TradeMarketEdgePolicy;
import io.butler.bet.intelligence.TradeRecommendationPolicy;
import io.butler.bet.intelligence.TradeRecommendationVetoPolicy;
import io.butler.bet.intelligence.TradeStrategicVetoDetector;
import io.butler.bet.intelligence.TradeTeamPerspectiveRecommendationPolicy;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ButlerTradeRecommendationCliTest {
    @Test
    void parsesExplicitSideAPerspective() {
        var options = ButlerTradeRecommendationCli.parse(new String[]{
            "trade", "recommendation", "l1", "2026", "player:p1,pick:d1", "p2", "side-a"});

        assertEquals("l1", options.leagueId());
        assertEquals(2026, options.season());
        assertEquals(List.of("p1"), options.sideA().playerIds());
        assertEquals(List.of("d1"), options.sideA().draftPickIds());
        assertEquals(TradeTeamPerspectiveRecommendationPolicy.Perspective.SIDE_A_TEAM, options.perspective());
        assertNull(options.source());
        assertNull(options.minimumAsOf());
    }

    @Test
    void parsesSideBSourceAndFreshnessBoundary() {
        var options = ButlerTradeRecommendationCli.parse(new String[]{
            "trade", "recommendation", "l1", "2026", "p1", "pick:d2", "side-b",
            "dynastyprocess", "--minimum-as-of", "2026-09-01"});

        assertEquals(TradeTeamPerspectiveRecommendationPolicy.Perspective.SIDE_B_TEAM, options.perspective());
        assertEquals("dynastyprocess", options.source());
        assertEquals(LocalDate.of(2026, 9, 1), options.minimumAsOf());
    }

    @Test
    void explainsEvidenceGateState() {
        var status = new ButlerTradeRecommendationCli.EvidenceStatus(true, false, true, false);

        assertEquals(
            "Evidence gates: market-direction=true posture=false future-capital=true positional-pressure=false",
            ButlerTradeRecommendationCli.formatEvidenceGates(status));
        assertEquals(
            "unavailable governed evidence: team posture, positional pressure",
            ButlerTradeRecommendationCli.formatInconclusiveReason(status));
    }

    @Test
    void formatsStrategicVetoReasons() {
        assertEquals(
            "low future capital: sending future pick(s) without receiving a future pick",
            ButlerTradeRecommendationCli.formatVetoReason(new TradeStrategicVetoDetector.VetoReason(
                TradeStrategicVetoDetector.ReasonCode.LOW_FUTURE_CAPITAL_OUTGOING_PICKS_WITHOUT_PICK_RETURN, null)));
        assertEquals(
            "QB pressure: sending QB without receiving QB",
            ButlerTradeRecommendationCli.formatVetoReason(new TradeStrategicVetoDetector.VetoReason(
                TradeStrategicVetoDetector.ReasonCode.POSITION_PRESSURE_OUTGOING_WITHOUT_SAME_POSITION_RETURN, "qb")));
    }

    @Test
    void locksStrategicVetoVocabulary() {
        assertEquals(List.of("CLEAR", "BLOCKED"),
            java.util.Arrays.stream(TradeRecommendationVetoPolicy.VetoState.values())
                .map(Enum::name)
                .toList());
        assertEquals(List.of(
                "LOW_FUTURE_CAPITAL_OUTGOING_PICKS_WITHOUT_PICK_RETURN",
                "POSITION_PRESSURE_OUTGOING_WITHOUT_SAME_POSITION_RETURN"),
            java.util.Arrays.stream(TradeStrategicVetoDetector.ReasonCode.values())
                .map(Enum::name)
                .toList());
    }

    @Test
    void sideAPerspectiveOwnsSideAOutgoingPackageForFutureCapitalVeto() {
        var report = report(
            LeagueFutureCapitalTierPolicy.Tier.LOW_FUTURE_CAPITAL,
            LeagueFutureCapitalTierPolicy.Tier.MIDDLE_FUTURE_CAPITAL,
            LeaguePositionalPressurePolicy.Tier.POSITION_BALANCED,
            LeaguePositionalPressurePolicy.Tier.POSITION_BALANCED,
            side(List.of(), List.of(pick("pick-a", "a", "Team A"))),
            side(List.of(player("wr-b", "WR", "b", "Team B")), List.of()),
            TradeMarketEdgePolicy.Direction.SIDE_A_MARKET_EDGE);

        var sideA = ButlerTradeRecommendationCli.recommend(
            report, TradeTeamPerspectiveRecommendationPolicy.Perspective.SIDE_A_TEAM);
        var sideB = ButlerTradeRecommendationCli.recommend(
            report, TradeTeamPerspectiveRecommendationPolicy.Perspective.SIDE_B_TEAM);

        assertEquals(TradeRecommendationVetoPolicy.VetoState.BLOCKED, sideA.vetoAssessment().state());
        assertEquals(TradeRecommendationPolicy.Recommendation.HOLD, sideA.packageRecommendation());
        assertEquals(TradeTeamPerspectiveRecommendationPolicy.Action.HOLD, sideA.action());

        assertEquals(TradeRecommendationVetoPolicy.VetoState.CLEAR, sideB.vetoAssessment().state());
        assertEquals(TradeRecommendationPolicy.Recommendation.SIDE_A_PACKAGE_PREFERRED, sideB.packageRecommendation());
        assertEquals(TradeTeamPerspectiveRecommendationPolicy.Action.ACCEPT, sideB.action());
    }

    @Test
    void sideBPerspectiveOwnsSideBOutgoingPackageForPositionalPressureVeto() {
        var report = report(
            LeagueFutureCapitalTierPolicy.Tier.MIDDLE_FUTURE_CAPITAL,
            LeagueFutureCapitalTierPolicy.Tier.MIDDLE_FUTURE_CAPITAL,
            LeaguePositionalPressurePolicy.Tier.POSITION_BALANCED,
            LeaguePositionalPressurePolicy.Tier.POSITION_PRESSURE,
            side(List.of(player("rb-a", "RB", "a", "Team A")), List.of()),
            side(List.of(player("wr-b", "WR", "b", "Team B")), List.of()),
            TradeMarketEdgePolicy.Direction.SIDE_B_MARKET_EDGE);

        var sideA = ButlerTradeRecommendationCli.recommend(
            report, TradeTeamPerspectiveRecommendationPolicy.Perspective.SIDE_A_TEAM);
        var sideB = ButlerTradeRecommendationCli.recommend(
            report, TradeTeamPerspectiveRecommendationPolicy.Perspective.SIDE_B_TEAM);

        assertEquals(TradeRecommendationVetoPolicy.VetoState.CLEAR, sideA.vetoAssessment().state());
        assertEquals(TradeRecommendationPolicy.Recommendation.SIDE_B_PACKAGE_PREFERRED, sideA.packageRecommendation());
        assertEquals(TradeTeamPerspectiveRecommendationPolicy.Action.ACCEPT, sideA.action());

        assertEquals(TradeRecommendationVetoPolicy.VetoState.BLOCKED, sideB.vetoAssessment().state());
        assertEquals(TradeRecommendationPolicy.Recommendation.HOLD, sideB.packageRecommendation());
        assertEquals(TradeTeamPerspectiveRecommendationPolicy.Action.HOLD, sideB.action());
    }

    @Test
    void printsExactVetoDrivenHoldContract() {
        var report = report(
            LeagueFutureCapitalTierPolicy.Tier.LOW_FUTURE_CAPITAL,
            LeagueFutureCapitalTierPolicy.Tier.MIDDLE_FUTURE_CAPITAL,
            LeaguePositionalPressurePolicy.Tier.POSITION_BALANCED,
            LeaguePositionalPressurePolicy.Tier.POSITION_BALANCED,
            side(List.of(), List.of(pick("pick-a", "a", "Team A"))),
            side(List.of(player("wr-b", "WR", "b", "Team B")), List.of()),
            TradeMarketEdgePolicy.Direction.SIDE_A_MARKET_EDGE);
        var options = new ButlerTradeRecommendationCli.Options(
            "l1",
            2026,
            TradeAssetAnalyzer.TradePackage.picks(List.of("pick-a")),
            TradeAssetAnalyzer.TradePackage.players(List.of("wr-b")),
            TradeTeamPerspectiveRecommendationPolicy.Perspective.SIDE_A_TEAM,
            null,
            null);

        PrintStream original = System.out;
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try {
            System.setOut(new PrintStream(bytes, true, StandardCharsets.UTF_8));
            ButlerTradeRecommendationCli.print(report, options);
        } finally {
            System.setOut(original);
        }

        String expected = String.join(System.lineSeparator(), List.of(
            "Trade recommendation (conservative market-first strategic veto)",
            "League ID: l1",
            "Season: 2026",
            "Perspective: Team A [a]",
            "Recommendation policy: trade-recommendation-v2-market-first-strategic-veto",
            "Strategic veto policy: trade-strategic-veto-v1-explicit-weakness-protection",
            "Perspective policy: trade-team-perspective-v1-explicit-owner",
            "Evidence complete: true",
            "Evidence gates: market-direction=true posture=true future-capital=true positional-pressure=true",
            "Strategic veto: BLOCKED",
            "Veto reason: low future capital: sending future pick(s) without receiving a future pick",
            "Package recommendation: HOLD",
            "Action: HOLD",
            "Reason: a governed strategic veto blocked the directional market recommendation.",
            "No hidden weighting, side flipping, or strategic score blending is applied.")) + System.lineSeparator();
        assertEquals(expected, bytes.toString(StandardCharsets.UTF_8));
    }

    @Test
    void completeEvidenceStatusRequiresEveryGate() {
        assertTrue(new ButlerTradeRecommendationCli.EvidenceStatus(true, true, true, true).complete());
        assertEquals(false, new ButlerTradeRecommendationCli.EvidenceStatus(false, true, true, true).complete());
    }

    @Test
    void locksRecommendationPolicyAndActionVocabulary() {
        assertEquals("trade-recommendation-v1-conservative-evidence-first", TradeRecommendationPolicy.POLICY_ID);
        assertEquals("trade-recommendation-v2-market-first-strategic-veto", TradeRecommendationVetoPolicy.POLICY_ID);
        assertEquals("trade-strategic-veto-v1-explicit-weakness-protection", TradeStrategicVetoDetector.POLICY_ID);
        assertEquals("trade-team-perspective-v1-explicit-owner", TradeTeamPerspectiveRecommendationPolicy.POLICY_ID);
        assertEquals(List.of("ACCEPT", "REJECT", "HOLD", "INCONCLUSIVE"),
            java.util.Arrays.stream(TradeTeamPerspectiveRecommendationPolicy.Action.values())
                .map(Enum::name)
                .toList());
    }

    @Test
    void rejectsMissingOrInvalidPerspective() {
        assertThrows(IllegalArgumentException.class, () -> ButlerTradeRecommendationCli.parse(new String[]{
            "trade", "recommendation", "l1", "2026", "p1", "p2"}));
        assertThrows(IllegalArgumentException.class, () -> ButlerTradeRecommendationCli.parse(new String[]{
            "trade", "recommendation", "l1", "2026", "p1", "p2", "mine"}));
    }

    @Test
    void recognizesRecommendationCommand() {
        assertTrue(ButlerTradeRecommendationCli.isCommand(new String[]{
            "trade", "recommendation", "l1", "2026", "p1", "p2", "side-a"}));
    }

    private static TradeAssetPositionalContextAnalyzer.TradePositionalContextReport report(
        LeagueFutureCapitalTierPolicy.Tier sideACapital,
        LeagueFutureCapitalTierPolicy.Tier sideBCapital,
        LeaguePositionalPressurePolicy.Tier sideAWrTier,
        LeaguePositionalPressurePolicy.Tier sideBWrTier,
        TradeAssetAnalyzer.TradeSide sideA,
        TradeAssetAnalyzer.TradeSide sideB,
        TradeMarketEdgePolicy.Direction marketEdge) {
        var identityA = new TradeAssetStrategicContextAnalyzer.TeamIdentity("a", "Team A");
        var identityB = new TradeAssetStrategicContextAnalyzer.TeamIdentity("b", "Team B");
        var strategicA = strategic(identityA, sideACapital);
        var strategicB = strategic(identityB, sideBCapital);
        var trade = new TradeAssetAnalyzer.TradeReport("l1", "source", null, sideA, sideB);
        var strategic = new TradeAssetStrategicContextAnalyzer.StrategicTradeReport(
            trade,
            "fairness-measurement",
            "fairness",
            10.0,
            TradeFairnessPolicy.Classification.OUTSIDE_FAIRNESS_BAND,
            "market-edge",
            marketEdge,
            "posture",
            true,
            "future-capital",
            true,
            strategicA,
            strategicB);

        Map<String, TradeAssetPositionalContextAnalyzer.PositionAvailability> availability = Map.of(
            "QB", available("QB"),
            "RB", available("RB"),
            "WR", available("WR"),
            "TE", available("TE"));
        return new TradeAssetPositionalContextAnalyzer.TradePositionalContextReport(
            strategic,
            "positional-pressure",
            "lineup",
            0,
            0,
            availability,
            positional(identityA, sideAWrTier),
            positional(identityB, sideBWrTier));
    }

    private static TradeAssetStrategicContextAnalyzer.TeamStrategicContext strategic(
        TradeAssetStrategicContextAnalyzer.TeamIdentity identity,
        LeagueFutureCapitalTierPolicy.Tier capitalTier) {
        var posture = new LeagueTeamPostureAnalyzer.TeamPosture(
            identity.teamId(),
            identity.teamName(),
            LeagueCompetitiveTierPolicy.Tier.MIDDLE_TIER,
            LeagueRosterStrengthTierPolicy.Tier.MIDDLE_ROSTER_TIER,
            TeamPosturePolicy.Posture.MIDDLE_OR_MIXED);
        var capital = new LeagueFutureCapitalTierAnalyzer.TeamFutureCapital(
            identity.teamId(), identity.teamName(), 100.0, 1, 0, 0, 1, List.of(), capitalTier);
        return new TradeAssetStrategicContextAnalyzer.TeamStrategicContext(identity, posture, capital);
    }

    private static TradeAssetPositionalContextAnalyzer.TeamPositionalContext positional(
        TradeAssetStrategicContextAnalyzer.TeamIdentity identity,
        LeaguePositionalPressurePolicy.Tier wrTier) {
        return new TradeAssetPositionalContextAnalyzer.TeamPositionalContext(identity, Map.of(
            "QB", pressure(identity, LeaguePositionalPressurePolicy.Tier.POSITION_BALANCED),
            "RB", pressure(identity, LeaguePositionalPressurePolicy.Tier.POSITION_BALANCED),
            "WR", pressure(identity, wrTier),
            "TE", pressure(identity, LeaguePositionalPressurePolicy.Tier.POSITION_BALANCED)));
    }

    private static LeaguePositionalPressureAnalyzer.TeamPositionPressure pressure(
        TradeAssetStrategicContextAnalyzer.TeamIdentity identity,
        LeaguePositionalPressurePolicy.Tier tier) {
        return new LeaguePositionalPressureAnalyzer.TeamPositionPressure(
            identity.teamId(), identity.teamName(), 50.0, 75.0, 3, 3, 0, 0, tier);
    }

    private static TradeAssetPositionalContextAnalyzer.PositionAvailability available(String position) {
        return new TradeAssetPositionalContextAnalyzer.PositionAvailability(position, 1, true, null);
    }

    private static TradeAssetAnalyzer.TradeSide side(
        List<TradeAssetAnalyzer.TradePlayer> players,
        List<TradeAssetAnalyzer.TradeDraftPick> picks) {
        return new TradeAssetAnalyzer.TradeSide(players, picks, 100.0, players.size(), 0, picks.size(), 0);
    }

    private static TradeAssetAnalyzer.TradePlayer player(
        String id, String position, String teamId, String teamName) {
        return new TradeAssetAnalyzer.TradePlayer(
            id, id, position, "NFL", teamId, teamName, 100.0, LocalDate.of(2026, 9, 1), false);
    }

    private static TradeAssetAnalyzer.TradeDraftPick pick(String id, String teamId, String teamName) {
        return new TradeAssetAnalyzer.TradeDraftPick(
            id, 2027, 1, "2027 1st", teamId, teamName, teamId, teamName,
            null, 100.0, LocalDate.of(2026, 9, 1), false);
    }
}
