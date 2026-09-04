package io.butler.bet.intelligence;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Computes neutral FLEX/SUPERFLEX roster coverage after direct starters are reserved.
 * The analyzer maximizes covered current market value subject only to explicit lineup eligibility;
 * it does not assign pressure tiers, apply positional weights, or emit a recommendation/veto.
 */
public final class LeagueFlexibleSlotCoverageAnalyzer {
    public static final String POLICY_ID = "flexible-slot-coverage-v1-direct-reserved-max-value";
    private static final List<String> CORE_POSITIONS = List.of("QB", "RB", "WR", "TE");

    private LeagueFlexibleSlotCoverageAnalyzer() {}

    public static FlexibleCoverageReport compose(
        LeagueLineupRequirementsAnalyzer.LineupRequirementsReport lineup,
        LeaguePositionalDepthAnalyzer.DepthReport depth) {
        Objects.requireNonNull(lineup, "lineup must not be null");
        Objects.requireNonNull(depth, "depth must not be null");
        if (!lineup.leagueId().equals(depth.leagueId())) {
            throw new IllegalStateException("lineup and depth league mismatch");
        }
        if (depth.teams().isEmpty()) {
            throw new IllegalArgumentException("depth report must contain teams");
        }

        var exposure = TradeFlexibleSlotEligibilityPolicy.exposure(lineup.flexSlots(), lineup.superFlexSlots());
        String insufficiencyReason = insufficiencyReason(lineup, depth, exposure);
        boolean available = insufficiencyReason == null;

        List<TeamFlexibleCoverage> teams = depth.teams().stream()
            .map(team -> available
                ? measureTeam(team, lineup.directStarterRequirements(), exposure)
                : unavailableTeam(team, exposure))
            .sorted(Comparator.comparing(TeamFlexibleCoverage::teamName, String.CASE_INSENSITIVE_ORDER)
                .thenComparing(TeamFlexibleCoverage::teamId))
            .toList();

        return new FlexibleCoverageReport(
            depth.leagueId(),
            depth.source(),
            depth.minimumAsOfDate(),
            POLICY_ID,
            lineup.policyId(),
            TradeFlexibleSlotEligibilityPolicy.POLICY_ID,
            exposure.flexSlots(),
            exposure.superFlexSlots(),
            available,
            insufficiencyReason,
            teams);
    }

    private static String insufficiencyReason(
        LeagueLineupRequirementsAnalyzer.LineupRequirementsReport lineup,
        LeaguePositionalDepthAnalyzer.DepthReport depth,
        TradeFlexibleSlotEligibilityPolicy.Exposure exposure) {
        if (!lineup.available()) return "Persisted league lineup configuration is required.";
        if (!lineup.unknownSlots().isEmpty()) {
            return "Unknown lineup slot semantics prevent safe flexible-slot coverage measurement.";
        }
        if (!exposure.active(TradeFlexibleSlotEligibilityPolicy.SlotType.FLEX)
            && !exposure.active(TradeFlexibleSlotEligibilityPolicy.SlotType.SUPERFLEX)) {
            return null;
        }

        Set<String> relevantPositions = new HashSet<>();
        if (exposure.active(TradeFlexibleSlotEligibilityPolicy.SlotType.FLEX)) {
            relevantPositions.addAll(TradeFlexibleSlotEligibilityPolicy.eligiblePositions(
                TradeFlexibleSlotEligibilityPolicy.SlotType.FLEX));
        }
        if (exposure.active(TradeFlexibleSlotEligibilityPolicy.SlotType.SUPERFLEX)) {
            relevantPositions.addAll(TradeFlexibleSlotEligibilityPolicy.eligiblePositions(
                TradeFlexibleSlotEligibilityPolicy.SlotType.SUPERFLEX));
        }

        for (var team : depth.teams()) {
            for (String position : relevantPositions) {
                var positionDepth = team.positions().get(position);
                if (positionDepth == null) continue;
                if (positionDepth.stalePlayers() > 0 || positionDepth.missingPlayers() > 0) {
                    return "Complete current value coverage is required for every rostered player eligible for an active flexible slot.";
                }
            }
        }
        return null;
    }

    private static TeamFlexibleCoverage measureTeam(
        LeaguePositionalDepthAnalyzer.TeamDepth team,
        Map<String, Integer> directRequirements,
        TradeFlexibleSlotEligibilityPolicy.Exposure exposure) {
        Set<String> reservedPlayerIds = new HashSet<>();
        double directReservedValue = 0.0;
        int directRequiredSlots = 0;
        int directCoveredSlots = 0;

        for (String position : CORE_POSITIONS) {
            int required = directRequirements.getOrDefault(position, 0);
            directRequiredSlots += required;
            var positionDepth = team.positions().get(position);
            if (positionDepth == null || required == 0) continue;
            for (var player : positionDepth.topPlayers(required)) {
                reservedPlayerIds.add(player.playerId());
                directReservedValue += player.value();
                directCoveredSlots++;
            }
        }

        List<LeaguePositionalDepthAnalyzer.PlayerDepthValue> remainingQbs = new ArrayList<>();
        List<LeaguePositionalDepthAnalyzer.PlayerDepthValue> remainingFlex = new ArrayList<>();
        for (String position : CORE_POSITIONS) {
            var positionDepth = team.positions().get(position);
            if (positionDepth == null) continue;
            for (var player : positionDepth.players()) {
                if (reservedPlayerIds.contains(player.playerId())) continue;
                if ("QB".equals(position)) remainingQbs.add(player);
                else remainingFlex.add(player);
            }
        }
        remainingQbs.sort(playerOrder());
        remainingFlex.sort(playerOrder());

        int totalFlexibleSlots = exposure.flexSlots() + exposure.superFlexSlots();
        Coverage maximum = maximizeCoverage(remainingQbs, remainingFlex, totalFlexibleSlots, exposure.superFlexSlots());
        double eligibleRemainingValue = remainingQbs.stream().mapToDouble(LeaguePositionalDepthAnalyzer.PlayerDepthValue::value).sum()
            + remainingFlex.stream().mapToDouble(LeaguePositionalDepthAnalyzer.PlayerDepthValue::value).sum();

        return new TeamFlexibleCoverage(
            team.teamId(),
            team.teamName(),
            directRequiredSlots,
            directCoveredSlots,
            directReservedValue,
            totalFlexibleSlots,
            maximum.coveredSlots(),
            totalFlexibleSlots - maximum.coveredSlots(),
            maximum.coveredValue(),
            eligibleRemainingValue);
    }

    private static TeamFlexibleCoverage unavailableTeam(
        LeaguePositionalDepthAnalyzer.TeamDepth team,
        TradeFlexibleSlotEligibilityPolicy.Exposure exposure) {
        int totalFlexibleSlots = exposure.flexSlots() + exposure.superFlexSlots();
        return new TeamFlexibleCoverage(team.teamId(), team.teamName(), 0, 0, 0.0,
            totalFlexibleSlots, 0, totalFlexibleSlots, 0.0, 0.0);
    }

    static Coverage maximizeCoverage(
        List<LeaguePositionalDepthAnalyzer.PlayerDepthValue> qbs,
        List<LeaguePositionalDepthAnalyzer.PlayerDepthValue> flexEligible,
        int totalFlexibleSlots,
        int superFlexSlots) {
        Objects.requireNonNull(qbs, "qbs must not be null");
        Objects.requireNonNull(flexEligible, "flexEligible must not be null");
        if (totalFlexibleSlots < 0 || superFlexSlots < 0 || superFlexSlots > totalFlexibleSlots) {
            throw new IllegalArgumentException("invalid flexible-slot counts");
        }
        if (totalFlexibleSlots == 0) return new Coverage(0, 0.0);

        List<LeaguePositionalDepthAnalyzer.PlayerDepthValue> sortedQbs = qbs.stream().sorted(playerOrder()).toList();
        List<LeaguePositionalDepthAnalyzer.PlayerDepthValue> sortedFlex = flexEligible.stream().sorted(playerOrder()).toList();
        double[] qbPrefix = prefixValues(sortedQbs);
        double[] flexPrefix = prefixValues(sortedFlex);

        Coverage best = new Coverage(0, 0.0);
        int maxQbs = Math.min(Math.min(superFlexSlots, sortedQbs.size()), totalFlexibleSlots);
        for (int qbCount = 0; qbCount <= maxQbs; qbCount++) {
            int flexCount = Math.min(sortedFlex.size(), totalFlexibleSlots - qbCount);
            int coveredSlots = qbCount + flexCount;
            double coveredValue = qbPrefix[qbCount] + flexPrefix[flexCount];
            if (coveredValue > best.coveredValue()
                || (Double.compare(coveredValue, best.coveredValue()) == 0 && coveredSlots > best.coveredSlots())) {
                best = new Coverage(coveredSlots, coveredValue);
            }
        }
        return best;
    }

    private static double[] prefixValues(List<LeaguePositionalDepthAnalyzer.PlayerDepthValue> players) {
        double[] prefix = new double[players.size() + 1];
        for (int i = 0; i < players.size(); i++) {
            double value = players.get(i).value();
            if (!Double.isFinite(value) || value < 0.0) {
                throw new IllegalArgumentException("flexible-slot player value must be finite and non-negative");
            }
            prefix[i + 1] = prefix[i] + value;
        }
        return prefix;
    }

    private static Comparator<LeaguePositionalDepthAnalyzer.PlayerDepthValue> playerOrder() {
        return Comparator.comparingDouble(LeaguePositionalDepthAnalyzer.PlayerDepthValue::value).reversed()
            .thenComparing(LeaguePositionalDepthAnalyzer.PlayerDepthValue::playerId);
    }

    record Coverage(int coveredSlots, double coveredValue) {
        Coverage {
            if (coveredSlots < 0) throw new IllegalArgumentException("coveredSlots must not be negative");
            if (!Double.isFinite(coveredValue) || coveredValue < 0.0) {
                throw new IllegalArgumentException("coveredValue must be finite and non-negative");
            }
        }
    }

    public record TeamFlexibleCoverage(
        String teamId,
        String teamName,
        int directRequiredSlots,
        int directCoveredSlots,
        double directReservedValue,
        int flexibleSlots,
        int flexibleCoveredSlots,
        int flexibleUnfilledSlots,
        double flexibleCoverageValue,
        double eligibleRemainingValue) {
        public TeamFlexibleCoverage {
            if (teamId == null || teamId.isBlank()) throw new IllegalArgumentException("teamId must not be blank");
            if (teamName == null || teamName.isBlank()) throw new IllegalArgumentException("teamName must not be blank");
            if (directRequiredSlots < 0 || directCoveredSlots < 0 || directCoveredSlots > directRequiredSlots) {
                throw new IllegalArgumentException("invalid direct slot coverage");
            }
            if (flexibleSlots < 0 || flexibleCoveredSlots < 0 || flexibleCoveredSlots > flexibleSlots
                || flexibleUnfilledSlots != flexibleSlots - flexibleCoveredSlots) {
                throw new IllegalArgumentException("invalid flexible slot coverage");
            }
            if (!Double.isFinite(directReservedValue) || directReservedValue < 0.0
                || !Double.isFinite(flexibleCoverageValue) || flexibleCoverageValue < 0.0
                || !Double.isFinite(eligibleRemainingValue) || eligibleRemainingValue < 0.0
                || flexibleCoverageValue > eligibleRemainingValue) {
                throw new IllegalArgumentException("invalid flexible coverage values");
            }
        }
    }

    public record FlexibleCoverageReport(
        String leagueId,
        String source,
        LocalDate minimumAsOfDate,
        String policyId,
        String lineupPolicyId,
        String eligibilityPolicyId,
        int flexSlots,
        int superFlexSlots,
        boolean available,
        String insufficiencyReason,
        List<TeamFlexibleCoverage> teams) {
        public FlexibleCoverageReport {
            if (leagueId == null || leagueId.isBlank()) throw new IllegalArgumentException("leagueId must not be blank");
            if (source == null || source.isBlank()) throw new IllegalArgumentException("source must not be blank");
            if (!POLICY_ID.equals(policyId)) throw new IllegalArgumentException("unexpected policyId");
            Objects.requireNonNull(lineupPolicyId, "lineupPolicyId must not be null");
            if (!TradeFlexibleSlotEligibilityPolicy.POLICY_ID.equals(eligibilityPolicyId)) {
                throw new IllegalArgumentException("unexpected eligibilityPolicyId");
            }
            if (flexSlots < 0 || superFlexSlots < 0) throw new IllegalArgumentException("flex slot counts must not be negative");
            teams = List.copyOf(Objects.requireNonNull(teams, "teams must not be null"));
            if (available && insufficiencyReason != null) throw new IllegalArgumentException("available report cannot have insufficiencyReason");
            if (!available && (insufficiencyReason == null || insufficiencyReason.isBlank())) {
                throw new IllegalArgumentException("unavailable report requires insufficiencyReason");
            }
        }
    }
}
