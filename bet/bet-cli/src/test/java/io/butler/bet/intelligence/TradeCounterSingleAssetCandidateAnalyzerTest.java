package io.butler.bet.intelligence;

import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TradeCounterSingleAssetCandidateAnalyzerTest {
    private static final LocalDate MINIMUM_AS_OF = LocalDate.of(2026, 9, 1);

    @Test
    void discoversOnlySingleAssetsThatActuallyProduceMarketFairTrade() {
        var trade = outsideTrade(MINIMUM_AS_OF);
        var context = TradeCounterValueContextAnalyzer.compose(trade);
        var report = TradeCounterSingleAssetCandidateAnalyzer.assess(
            trade, context, inventory(MINIMUM_AS_OF));

        assertTrue(report.available());
        assertEquals(TradeFairnessPolicy.Classification.OUTSIDE_FAIRNESS_BAND, report.currentFairness());
        assertEquals(3, report.candidates().size());

        var first = report.candidates().get(0);
        assertEquals(TradeCounterSingleAssetCandidateAnalyzer.AdjustmentType.ADD_ASSET_TO_LOWER_PACKAGE,
            first.adjustmentType());
        assertEquals(TradeCounterValueTargetAnalyzer.Side.SIDE_B, first.side());
        assertEquals(TradeCounterSingleAssetCandidateAnalyzer.AssetType.PLAYER, first.assetType());
        assertEquals("b-extra-5", first.assetId());
        assertEquals(5.0, first.assetValue());
        assertEquals(TradeFairnessPolicy.Classification.MARKET_FAIR, first.resultingFairness());

        var second = report.candidates().get(1);
        assertEquals(TradeCounterSingleAssetCandidateAnalyzer.AssetType.DRAFT_PICK, second.assetType());
        assertEquals("b-pick-5.2", second.assetId());
        assertEquals(5.2, second.assetValue());

        var third = report.candidates().get(2);
        assertEquals(TradeCounterSingleAssetCandidateAnalyzer.AdjustmentType.REMOVE_ASSET_FROM_HIGHER_PACKAGE,
            third.adjustmentType());
        assertEquals(TradeCounterValueTargetAnalyzer.Side.SIDE_A, third.side());
        assertEquals("a-remove-5.5", third.assetId());
        assertEquals(5.5, third.assetValue());

        assertTrue(first.excessValue() < second.excessValue());
        assertTrue(second.excessValue() < third.excessValue());
        report.candidates().forEach(candidate ->
            assertEquals(TradeFairnessPolicy.Classification.MARKET_FAIR,
                TradeFairnessPolicy.classify(candidate.resultingGapPercent())));
    }

    @Test
    void doesNotTreatExistingPackageOrOtherTeamInventoryAsAddCandidates() {
        var trade = outsideTrade(MINIMUM_AS_OF);
        var report = TradeCounterSingleAssetCandidateAnalyzer.assess(
            trade,
            TradeCounterValueContextAnalyzer.compose(trade),
            inventory(MINIMUM_AS_OF));

        var ids = report.candidates().stream()
            .map(TradeCounterSingleAssetCandidateAnalyzer.Candidate::assetId)
            .toList();
        assertFalse(ids.contains("b-existing-5"));
        assertFalse(ids.contains("a-inventory-extra-5"));
        assertFalse(ids.contains("c-extra-5"));
        assertTrue(ids.contains("b-extra-5"));
    }

    @Test
    void freshnessBoundaryFiltersAddCandidatesUsingInventoryAsOfDate() {
        var trade = outsideTrade(MINIMUM_AS_OF);
        var staleInventory = inventoryWithStaleBestCandidate();
        var report = TradeCounterSingleAssetCandidateAnalyzer.assess(
            trade,
            TradeCounterValueContextAnalyzer.compose(trade),
            staleInventory);

        var ids = report.candidates().stream()
            .map(TradeCounterSingleAssetCandidateAnalyzer.Candidate::assetId)
            .toList();
        assertFalse(ids.contains("b-stale-4.9"));
        assertTrue(ids.contains("b-fresh-5.1"));
    }

    @Test
    void alreadyFairTradeNeedsNoCandidateDiscovery() {
        var trade = trade(
            side("A", "Team A", List.of(player("a", "A", "Team A", 102.0, MINIMUM_AS_OF))),
            side("B", "Team B", List.of(player("b", "B", "Team B", 100.0, MINIMUM_AS_OF))),
            MINIMUM_AS_OF);
        var report = TradeCounterSingleAssetCandidateAnalyzer.assess(
            trade,
            TradeCounterValueContextAnalyzer.compose(trade),
            emptyInventory());

        assertTrue(report.available());
        assertEquals(TradeFairnessPolicy.Classification.MARKET_FAIR, report.currentFairness());
        assertTrue(report.candidates().isEmpty());
    }

    @Test
    void incompleteMarketEvidenceFailsClosedBeforeAssetDiscovery() {
        var missing = new TradeAssetAnalyzer.TradePlayer(
            "missing", "Missing", "WR", "NFL", "A", "Team A", null, null, false);
        var trade = trade(
            new TradeAssetAnalyzer.TradeSide(List.of(missing), List.of(), 0.0, 0, 1, 0, 0),
            side("B", "Team B", List.of(player("b", "B", "Team B", 95.0, MINIMUM_AS_OF))),
            MINIMUM_AS_OF);
        var report = TradeCounterSingleAssetCandidateAnalyzer.assess(
            trade,
            TradeCounterValueContextAnalyzer.compose(trade),
            emptyInventory());

        assertFalse(report.available());
        assertEquals("Trade counter value target requires complete market-value coverage.",
            report.insufficiencyReason());
        assertTrue(report.candidates().isEmpty());
    }

    @Test
    void multiTeamPackageOwnershipFailsClosed() {
        var trade = trade(
            side("A", "Team A", List.of(
                player("a-100", "A", "Team A", 100.0, MINIMUM_AS_OF),
                player("c-5", "C", "Team C", 5.0, MINIMUM_AS_OF))),
            side("B", "Team B", List.of(player("b-95", "B", "Team B", 95.0, MINIMUM_AS_OF))),
            MINIMUM_AS_OF);
        var report = TradeCounterSingleAssetCandidateAnalyzer.assess(
            trade,
            TradeCounterValueContextAnalyzer.compose(trade),
            emptyInventory());

        assertFalse(report.available());
        assertEquals(
            "Trade counter candidate discovery requires each package to resolve to one current fantasy team.",
            report.insufficiencyReason());
    }

    @Test
    void sameOwnerOnBothPackagesFailsClosed() {
        var trade = trade(
            side("A", "Team A", List.of(
                player("a-99.5", "A", "Team A", 99.5, MINIMUM_AS_OF),
                player("a-5.5", "A", "Team A", 5.5, MINIMUM_AS_OF))),
            side("A", "Team A", List.of(player("a-95", "A", "Team A", 95.0, MINIMUM_AS_OF))),
            MINIMUM_AS_OF);
        var report = TradeCounterSingleAssetCandidateAnalyzer.assess(
            trade,
            TradeCounterValueContextAnalyzer.compose(trade),
            emptyInventory());

        assertFalse(report.available());
        assertEquals("Trade counter candidate discovery requires distinct package owners.",
            report.insufficiencyReason());
    }

    @Test
    void rejectsMismatchedInventoryCoordinates() {
        var trade = outsideTrade(MINIMUM_AS_OF);
        var wrong = new LeagueAssetInventoryAnalyzer.InventoryReport(
            "other", "source", 0, 0, 0, 0, List.of());

        assertThrows(IllegalArgumentException.class, () ->
            TradeCounterSingleAssetCandidateAnalyzer.assess(
                trade, TradeCounterValueContextAnalyzer.compose(trade), wrong));
    }

    @Test
    void locksCandidatePolicyProvenance() {
        var trade = outsideTrade(MINIMUM_AS_OF);
        var report = TradeCounterSingleAssetCandidateAnalyzer.assess(
            trade,
            TradeCounterValueContextAnalyzer.compose(trade),
            inventory(MINIMUM_AS_OF));

        assertEquals("trade-counter-single-asset-candidate-v1-market-fair-minimum-excess",
            report.policyId());
        assertEquals(TradeCounterValueContextAnalyzer.POLICY_ID, report.contextPolicyId());
        assertEquals(TradeCounterValueTargetAnalyzer.POLICY_ID, report.targetPolicyId());
        assertEquals("l1", report.leagueId());
        assertEquals("source", report.source());
        assertEquals(MINIMUM_AS_OF, report.minimumAsOfDate());
    }

    private static TradeAssetAnalyzer.TradeReport outsideTrade(LocalDate minimumAsOf) {
        return trade(
            side("A", "Team A", List.of(
                player("a-99.5", "A", "Team A", 99.5, minimumAsOf),
                player("a-remove-5.5", "A", "Team A", 5.5, minimumAsOf))),
            side("B", "Team B", List.of(
                player("b-90", "B", "Team B", 90.0, minimumAsOf),
                player("b-existing-5", "B", "Team B", 5.0, minimumAsOf))),
            minimumAsOf);
    }

    private static LeagueAssetInventoryAnalyzer.InventoryReport inventory(LocalDate asOf) {
        var teamA = new LeagueAssetInventoryAnalyzer.TeamInventory(
            "A", "Team A",
            List.of(new LeagueAssetInventoryAnalyzer.PlayerAsset(
                "a-inventory-extra-5", "A Extra", "WR", "NFL", "BN", 5.0, asOf)),
            List.of());
        var teamB = new LeagueAssetInventoryAnalyzer.TeamInventory(
            "B", "Team B",
            List.of(
                new LeagueAssetInventoryAnalyzer.PlayerAsset(
                    "b-90", "B 90", "WR", "NFL", "STARTER", 90.0, asOf),
                new LeagueAssetInventoryAnalyzer.PlayerAsset(
                    "b-existing-5", "B Existing", "RB", "NFL", "BN", 5.0, asOf),
                new LeagueAssetInventoryAnalyzer.PlayerAsset(
                    "b-small-4", "B Small", "WR", "NFL", "BN", 4.0, asOf),
                new LeagueAssetInventoryAnalyzer.PlayerAsset(
                    "b-extra-5", "B Extra", "WR", "NFL", "BN", 5.0, asOf)),
            List.of(new LeagueAssetInventoryAnalyzer.DraftPickAsset(
                "b-pick-5.2", 2027, 2, "2027 2nd", "B", "Team B", null, 5.2, asOf)));
        var teamC = new LeagueAssetInventoryAnalyzer.TeamInventory(
            "C", "Team C",
            List.of(new LeagueAssetInventoryAnalyzer.PlayerAsset(
                "c-extra-5", "C Extra", "WR", "NFL", "BN", 5.0, asOf)),
            List.of());
        return new LeagueAssetInventoryAnalyzer.InventoryReport(
            "l1", "source", 7, 0, 1, 0, List.of(teamA, teamB, teamC));
    }

    private static LeagueAssetInventoryAnalyzer.InventoryReport inventoryWithStaleBestCandidate() {
        var teamB = new LeagueAssetInventoryAnalyzer.TeamInventory(
            "B", "Team B",
            List.of(
                new LeagueAssetInventoryAnalyzer.PlayerAsset(
                    "b-stale-4.9", "B Stale", "WR", "NFL", "BN", 4.9, MINIMUM_AS_OF.minusDays(1)),
                new LeagueAssetInventoryAnalyzer.PlayerAsset(
                    "b-fresh-5.1", "B Fresh", "WR", "NFL", "BN", 5.1, MINIMUM_AS_OF)),
            List.of());
        return new LeagueAssetInventoryAnalyzer.InventoryReport(
            "l1", "source", 2, 0, 0, 0, List.of(teamB));
    }

    private static LeagueAssetInventoryAnalyzer.InventoryReport emptyInventory() {
        return new LeagueAssetInventoryAnalyzer.InventoryReport(
            "l1", "source", 0, 0, 0, 0, List.of());
    }

    private static TradeAssetAnalyzer.TradeReport trade(
        TradeAssetAnalyzer.TradeSide sideA,
        TradeAssetAnalyzer.TradeSide sideB,
        LocalDate minimumAsOf) {
        return new TradeAssetAnalyzer.TradeReport("l1", "source", minimumAsOf, sideA, sideB);
    }

    private static TradeAssetAnalyzer.TradeSide side(
        String teamId,
        String teamName,
        List<TradeAssetAnalyzer.TradePlayer> players) {
        double total = players.stream().mapToDouble(player -> player.value() == null ? 0.0 : player.value()).sum();
        int valued = (int) players.stream().filter(TradeAssetAnalyzer.TradePlayer::valued).count();
        return new TradeAssetAnalyzer.TradeSide(
            players, List.of(), total, valued, players.size() - valued, 0, 0);
    }

    private static TradeAssetAnalyzer.TradePlayer player(
        String id,
        String teamId,
        String teamName,
        double value,
        LocalDate asOf) {
        return new TradeAssetAnalyzer.TradePlayer(
            id, id, "WR", "NFL", teamId, teamName, value, asOf, false);
    }
}
