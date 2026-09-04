package io.butler.bet.intelligence;

import java.time.LocalDate;
import java.util.Objects;

/**
 * Attaches descriptive combined FLEX/SUPERFLEX pressure evidence to the two explicit trade teams.
 * This layer is read-only context and does not emit or modify a recommendation or veto.
 */
public final class TradeFlexibleSlotContextAnalyzer {
    private TradeFlexibleSlotContextAnalyzer() {}

    public static TradeFlexibleContextReport compose(
        TradeAssetPositionalContextAnalyzer.TradePositionalContextReport trade,
        LeagueFlexibleSlotPressureAnalyzer.FlexiblePressureReport flexible) {
        Objects.requireNonNull(trade, "trade must not be null");
        Objects.requireNonNull(flexible, "flexible must not be null");

        var strategic = trade.strategic();
        var tradeReport = strategic.trade();
        if (!tradeReport.leagueId().equals(flexible.leagueId())) {
            throw new IllegalStateException("trade and flexible-pressure reports reference different leagues");
        }
        if (!tradeReport.source().equals(flexible.source())) {
            throw new IllegalStateException("trade and flexible-pressure reports use different value sources");
        }
        if (!Objects.equals(tradeReport.minimumAsOfDate(), flexible.minimumAsOfDate())) {
            throw new IllegalStateException("trade and flexible-pressure reports use different freshness boundaries");
        }

        return attach(
            tradeReport.leagueId(),
            tradeReport.source(),
            tradeReport.minimumAsOfDate(),
            strategic.sideA().identity(),
            strategic.sideB().identity(),
            trade.flexSlots(),
            trade.superFlexSlots(),
            flexible);
    }

    static TradeFlexibleContextReport attach(
        String leagueId,
        String source,
        LocalDate minimumAsOfDate,
        TradeAssetStrategicContextAnalyzer.TeamIdentity sideAIdentity,
        TradeAssetStrategicContextAnalyzer.TeamIdentity sideBIdentity,
        int flexSlots,
        int superFlexSlots,
        LeagueFlexibleSlotPressureAnalyzer.FlexiblePressureReport flexible) {
        requireText(leagueId, "leagueId");
        requireText(source, "source");
        Objects.requireNonNull(sideAIdentity, "sideAIdentity must not be null");
        Objects.requireNonNull(sideBIdentity, "sideBIdentity must not be null");
        Objects.requireNonNull(flexible, "flexible must not be null");
        if (flexSlots < 0 || superFlexSlots < 0) {
            throw new IllegalArgumentException("flex exposure counts must be non-negative");
        }
        if (!leagueId.equals(flexible.leagueId())) {
            throw new IllegalStateException("trade and flexible-pressure reports reference different leagues");
        }
        if (!source.equals(flexible.source())) {
            throw new IllegalStateException("trade and flexible-pressure reports use different value sources");
        }
        if (!Objects.equals(minimumAsOfDate, flexible.minimumAsOfDate())) {
            throw new IllegalStateException("trade and flexible-pressure reports use different freshness boundaries");
        }

        int expectedFlexibleSlots = flexSlots + superFlexSlots;
        for (var team : flexible.teams()) {
            if (team.flexibleSlots() != expectedFlexibleSlots) {
                throw new IllegalStateException(
                    "flexible-pressure team slot count differs from trade lineup exposure: " + team.teamId());
            }
        }

        var sideA = attachTeam(sideAIdentity, flexible);
        var sideB = attachTeam(sideBIdentity, flexible);
        return new TradeFlexibleContextReport(
            leagueId,
            source,
            minimumAsOfDate,
            flexible.policyId(),
            flexible.coveragePolicyId(),
            flexSlots,
            superFlexSlots,
            flexible.available(),
            flexible.insufficiencyReason(),
            sideA,
            sideB);
    }

    private static TeamFlexibleContext attachTeam(
        TradeAssetStrategicContextAnalyzer.TeamIdentity identity,
        LeagueFlexibleSlotPressureAnalyzer.FlexiblePressureReport flexible) {
        var team = flexible.teams().stream()
            .filter(candidate -> candidate.teamId().equals(identity.teamId()))
            .findFirst()
            .orElseThrow(() -> new IllegalStateException(
                "flexible pressure missing for trade team: " + identity.teamId()));
        if (!team.teamName().equals(identity.teamName())) {
            throw new IllegalStateException(
                "trade and flexible-pressure team names differ: " + identity.teamId());
        }
        return new TeamFlexibleContext(identity, team);
    }

    private static String requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
        return value.trim();
    }

    public record TeamFlexibleContext(
        TradeAssetStrategicContextAnalyzer.TeamIdentity identity,
        LeagueFlexibleSlotPressureAnalyzer.TeamFlexiblePressure pressure) {
        public TeamFlexibleContext {
            Objects.requireNonNull(identity, "identity must not be null");
            Objects.requireNonNull(pressure, "pressure must not be null");
            if (!identity.teamId().equals(pressure.teamId())
                || !identity.teamName().equals(pressure.teamName())) {
                throw new IllegalArgumentException("flexible context identity mismatch");
            }
        }
    }

    public record TradeFlexibleContextReport(
        String leagueId,
        String source,
        LocalDate minimumAsOfDate,
        String flexiblePressurePolicyId,
        String flexibleCoveragePolicyId,
        int flexSlots,
        int superFlexSlots,
        boolean flexiblePressureAvailable,
        String flexiblePressureInsufficiencyReason,
        TeamFlexibleContext sideA,
        TeamFlexibleContext sideB) {
        public TradeFlexibleContextReport {
            requireText(leagueId, "leagueId");
            requireText(source, "source");
            if (!LeagueFlexibleSlotPressurePolicy.POLICY_ID.equals(flexiblePressurePolicyId)) {
                throw new IllegalArgumentException("unexpected flexiblePressurePolicyId");
            }
            if (!LeagueFlexibleSlotCoverageAnalyzer.POLICY_ID.equals(flexibleCoveragePolicyId)) {
                throw new IllegalArgumentException("unexpected flexibleCoveragePolicyId");
            }
            if (flexSlots < 0 || superFlexSlots < 0) {
                throw new IllegalArgumentException("flex exposure counts must be non-negative");
            }
            if (flexiblePressureAvailable && flexiblePressureInsufficiencyReason != null) {
                throw new IllegalArgumentException("available flexible pressure cannot have insufficiency reason");
            }
            if (!flexiblePressureAvailable
                && (flexiblePressureInsufficiencyReason == null
                    || flexiblePressureInsufficiencyReason.isBlank())) {
                throw new IllegalArgumentException("unavailable flexible pressure requires insufficiency reason");
            }
            Objects.requireNonNull(sideA, "sideA must not be null");
            Objects.requireNonNull(sideB, "sideB must not be null");
        }
    }
}
