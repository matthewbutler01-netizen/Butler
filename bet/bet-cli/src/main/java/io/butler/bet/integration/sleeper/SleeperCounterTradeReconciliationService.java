package io.butler.bet.integration.sleeper;

import io.butler.bet.data.Database;
import io.butler.bet.intelligence.TradeAssetAnalyzer;

import java.io.IOException;
import java.sql.SQLException;
import java.util.List;
import java.util.Objects;

/**
 * Read-only orchestration from Butler trade coordinates through official Sleeper transaction evidence.
 * This service never mutates execution state and never calls an unsupported Sleeper write endpoint.
 */
public final class SleeperCounterTradeReconciliationService {
    public static final String SERVICE_ID =
        "sleeper-counter-trade-reconciliation-service-v1-explicit-round-read-only";

    private final SleeperTradeExpectationResolver resolver;
    private final SleeperReadOnlyClient client;

    public SleeperCounterTradeReconciliationService(Database database, SleeperReadOnlyClient client) {
        this.resolver = new SleeperTradeExpectationResolver(
            Objects.requireNonNull(database, "database must not be null"));
        this.client = Objects.requireNonNull(client, "client must not be null");
    }

    public Report reconcile(
        String leagueId,
        String sideATeamId,
        String sideBTeamId,
        TradeAssetAnalyzer.TradePackage sideA,
        TradeAssetAnalyzer.TradePackage sideB,
        int round,
        String creatorUserId,
        long notBeforeEpochMillis) throws SQLException, IOException, InterruptedException {
        var resolution = resolver.resolve(
            leagueId,
            sideATeamId,
            sideBTeamId,
            sideA,
            sideB,
            round,
            creatorUserId,
            notBeforeEpochMillis);

        if (!resolution.available()) {
            return new Report(
                SERVICE_ID,
                State.INCONCLUSIVE,
                resolution,
                null,
                List.of(),
                "Sleeper reconciliation expectation could not be resolved from trusted Butler identity/ownership data.");
        }

        var transactions = client.transactions(
            resolution.expectedTrade().leagueId(),
            resolution.expectedTrade().round());
        var reconciliation = SleeperTradeReconciliationPolicy.reconcile(
            resolution.expectedTrade(), transactions);

        return new Report(
            SERVICE_ID,
            State.RECONCILED,
            resolution,
            reconciliation,
            transactions,
            "Official Sleeper read-only transaction evidence was fetched and evaluated by the governed exact-match policy.");
    }

    public enum State {
        RECONCILED,
        INCONCLUSIVE
    }

    public record Report(
        String serviceId,
        State state,
        SleeperTradeExpectationResolver.Resolution expectationResolution,
        SleeperTradeReconciliationPolicy.Result reconciliation,
        List<SleeperReadOnlyClient.SleeperTransaction> observedTransactions,
        String reason) {
        public Report {
            if (!SERVICE_ID.equals(serviceId)) throw new IllegalArgumentException("unexpected serviceId");
            Objects.requireNonNull(state, "state must not be null");
            Objects.requireNonNull(expectationResolution, "expectationResolution must not be null");
            observedTransactions = List.copyOf(Objects.requireNonNull(
                observedTransactions, "observedTransactions must not be null"));
            if (reason == null || reason.isBlank()) throw new IllegalArgumentException("reason must not be blank");
            if (state == State.RECONCILED) {
                if (!expectationResolution.available() || reconciliation == null) {
                    throw new IllegalArgumentException("RECONCILED report requires resolved expectation and reconciliation result");
                }
            } else {
                if (expectationResolution.available() || reconciliation != null || !observedTransactions.isEmpty()) {
                    throw new IllegalArgumentException("INCONCLUSIVE identity report must not claim fetched transaction evidence");
                }
            }
        }
    }
}
