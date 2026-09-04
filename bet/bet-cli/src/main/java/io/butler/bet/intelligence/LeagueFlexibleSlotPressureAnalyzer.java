package io.butler.bet.intelligence;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Classifies combined FLEX/SUPERFLEX pressure from neutral flexible-slot coverage evidence.
 * Classification is league-relative and uses the same outer-quartile boundary discipline as
 * direct positional pressure. It does not modify QB/RB/WR/TE pressure tiers or recommendations.
 */
public final class LeagueFlexibleSlotPressureAnalyzer {
    private LeagueFlexibleSlotPressureAnalyzer() {}

    public static FlexiblePressureReport classify(
        LeagueFlexibleSlotCoverageAnalyzer.FlexibleCoverageReport coverage) {
        Objects.requireNonNull(coverage, "coverage must not be null");
        if (coverage.teams().isEmpty()) {
            throw new IllegalArgumentException("coverage report must contain teams");
        }

        int totalFlexibleSlots = coverage.flexSlots() + coverage.superFlexSlots();
        if (totalFlexibleSlots == 0) {
            return new FlexiblePressureReport(
                coverage.leagueId(),
                coverage.source(),
                coverage.minimumAsOfDate(),
                LeagueFlexibleSlotPressurePolicy.POLICY_ID,
                coverage.policyId(),
                true,
                null,
                coverage.teams().stream()
                    .map(team -> attach(team, LeagueFlexibleSlotPressurePolicy.Tier.NO_FLEXIBLE_REQUIREMENT))
                    .sorted(teamOrder())
                    .toList());
        }

        String insufficiencyReason = null;
        if (!coverage.available()) {
            insufficiencyReason = coverage.insufficiencyReason();
        } else if (coverage.teams().size() < LeagueFlexibleSlotPressurePolicy.MINIMUM_LEAGUE_TEAMS) {
            insufficiencyReason = "At least four league teams are required for relative flexible-slot tiers.";
        }

        if (insufficiencyReason != null) {
            String reason = insufficiencyReason;
            return new FlexiblePressureReport(
                coverage.leagueId(),
                coverage.source(),
                coverage.minimumAsOfDate(),
                LeagueFlexibleSlotPressurePolicy.POLICY_ID,
                coverage.policyId(),
                false,
                reason,
                coverage.teams().stream()
                    .map(team -> attach(team, LeagueFlexibleSlotPressurePolicy.Tier.INSUFFICIENT_EVIDENCE))
                    .sorted(teamOrder())
                    .toList());
        }

        List<LeagueFlexibleSlotCoverageAnalyzer.TeamFlexibleCoverage> ranked = new ArrayList<>(coverage.teams());
        ranked.sort(Comparator.comparingDouble(
                LeagueFlexibleSlotCoverageAnalyzer.TeamFlexibleCoverage::flexibleCoverageValue).reversed()
            .thenComparing(LeagueFlexibleSlotCoverageAnalyzer.TeamFlexibleCoverage::teamId));

        int outerCount = (int) Math.floor(ranked.size() * 0.25);
        double topBoundary = ranked.get(outerCount - 1).flexibleCoverageValue();
        double bottomBoundary = ranked.get(ranked.size() - outerCount).flexibleCoverageValue();

        Map<String, LeagueFlexibleSlotPressurePolicy.Tier> tiers = new HashMap<>();
        for (var team : ranked) {
            boolean top = team.flexibleCoverageValue() >= topBoundary;
            boolean bottom = team.flexibleCoverageValue() <= bottomBoundary;
            var tier = top && !bottom
                ? LeagueFlexibleSlotPressurePolicy.Tier.FLEXIBLE_STRENGTH
                : bottom && !top
                    ? LeagueFlexibleSlotPressurePolicy.Tier.FLEXIBLE_PRESSURE
                    : LeagueFlexibleSlotPressurePolicy.Tier.FLEXIBLE_BALANCED;
            tiers.put(team.teamId(), tier);
        }

        List<TeamFlexiblePressure> classified = coverage.teams().stream()
            .map(team -> attach(team, tiers.get(team.teamId())))
            .sorted(teamOrder())
            .toList();

        return new FlexiblePressureReport(
            coverage.leagueId(),
            coverage.source(),
            coverage.minimumAsOfDate(),
            LeagueFlexibleSlotPressurePolicy.POLICY_ID,
            coverage.policyId(),
            true,
            null,
            classified);
    }

    private static TeamFlexiblePressure attach(
        LeagueFlexibleSlotCoverageAnalyzer.TeamFlexibleCoverage team,
        LeagueFlexibleSlotPressurePolicy.Tier tier) {
        return new TeamFlexiblePressure(
            team.teamId(),
            team.teamName(),
            team.flexibleSlots(),
            team.flexibleCoveredSlots(),
            team.flexibleUnfilledSlots(),
            team.flexibleCoverageValue(),
            tier);
    }

    private static Comparator<TeamFlexiblePressure> teamOrder() {
        return Comparator.comparing(TeamFlexiblePressure::teamName, String.CASE_INSENSITIVE_ORDER)
            .thenComparing(TeamFlexiblePressure::teamId);
    }

    public record TeamFlexiblePressure(
        String teamId,
        String teamName,
        int flexibleSlots,
        int flexibleCoveredSlots,
        int flexibleUnfilledSlots,
        double flexibleCoverageValue,
        LeagueFlexibleSlotPressurePolicy.Tier tier) {
        public TeamFlexiblePressure {
            if (teamId == null || teamId.isBlank()) throw new IllegalArgumentException("teamId must not be blank");
            if (teamName == null || teamName.isBlank()) throw new IllegalArgumentException("teamName must not be blank");
            if (flexibleSlots < 0 || flexibleCoveredSlots < 0 || flexibleCoveredSlots > flexibleSlots
                || flexibleUnfilledSlots != flexibleSlots - flexibleCoveredSlots) {
                throw new IllegalArgumentException("invalid flexible slot coverage");
            }
            if (!Double.isFinite(flexibleCoverageValue) || flexibleCoverageValue < 0.0) {
                throw new IllegalArgumentException("flexibleCoverageValue must be finite and non-negative");
            }
            Objects.requireNonNull(tier, "tier must not be null");
        }
    }

    public record FlexiblePressureReport(
        String leagueId,
        String source,
        LocalDate minimumAsOfDate,
        String policyId,
        String coveragePolicyId,
        boolean available,
        String insufficiencyReason,
        List<TeamFlexiblePressure> teams) {
        public FlexiblePressureReport {
            if (leagueId == null || leagueId.isBlank()) throw new IllegalArgumentException("leagueId must not be blank");
            if (source == null || source.isBlank()) throw new IllegalArgumentException("source must not be blank");
            if (!LeagueFlexibleSlotPressurePolicy.POLICY_ID.equals(policyId)) {
                throw new IllegalArgumentException("unexpected policyId");
            }
            if (!LeagueFlexibleSlotCoverageAnalyzer.POLICY_ID.equals(coveragePolicyId)) {
                throw new IllegalArgumentException("unexpected coveragePolicyId");
            }
            teams = List.copyOf(Objects.requireNonNull(teams, "teams must not be null"));
            if (available && insufficiencyReason != null) {
                throw new IllegalArgumentException("available report cannot have insufficiencyReason");
            }
            if (!available && (insufficiencyReason == null || insufficiencyReason.isBlank())) {
                throw new IllegalArgumentException("unavailable report requires insufficiencyReason");
            }
        }
    }
}
