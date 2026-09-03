package io.butler.bet.intelligence;

import io.butler.bet.data.Database;

import java.sql.SQLException;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Governs league-relative QB/RB/WR/TE pressure using the value of only the players needed to fill
 * each position's direct starter requirement. FLEX and SUPERFLEX remain separate exposure context.
 */
public final class LeaguePositionalPressureAnalyzer {
    private static final List<String> CORE_POSITIONS = List.of("QB", "RB", "WR", "TE");

    private final LeagueLineupRequirementsAnalyzer lineup;
    private final LeaguePositionalDepthAnalyzer depth;

    public LeaguePositionalPressureAnalyzer(Database database) {
        Objects.requireNonNull(database, "database must not be null");
        this.lineup = new LeagueLineupRequirementsAnalyzer(database);
        this.depth = new LeaguePositionalDepthAnalyzer(database);
    }

    public PositionalPressureReport analyze(String leagueId) throws SQLException {
        return compose(lineup.analyze(leagueId), depth.analyze(leagueId));
    }

    public PositionalPressureReport analyze(String leagueId, String source) throws SQLException {
        return compose(lineup.analyze(leagueId), depth.analyze(leagueId, source));
    }

    public PositionalPressureReport analyze(String leagueId, LocalDate minimumAsOfDate) throws SQLException {
        return compose(lineup.analyze(leagueId), depth.analyze(leagueId, minimumAsOfDate));
    }

    public PositionalPressureReport analyze(String leagueId, String source, LocalDate minimumAsOfDate) throws SQLException {
        return compose(lineup.analyze(leagueId), depth.analyze(leagueId, source, minimumAsOfDate));
    }

    public static PositionalPressureReport compose(
        LeagueLineupRequirementsAnalyzer.LineupRequirementsReport lineup,
        LeaguePositionalDepthAnalyzer.DepthReport depth) {
        Objects.requireNonNull(lineup, "lineup must not be null");
        Objects.requireNonNull(depth, "depth must not be null");
        if (!lineup.leagueId().equals(depth.leagueId())) throw new IllegalStateException("lineup and depth league mismatch");
        if (depth.teams().isEmpty()) throw new IllegalArgumentException("depth report must contain teams");

        String globalReason = null;
        if (!lineup.available()) globalReason = "Persisted league lineup configuration is required.";
        else if (!lineup.unknownSlots().isEmpty()) globalReason = "Unknown lineup slot semantics prevent safe positional pressure classification.";
        else if (depth.teams().size() < LeaguePositionalPressurePolicy.MINIMUM_LEAGUE_TEAMS) {
            globalReason = "At least four league teams are required for relative positional tiers.";
        }

        Map<String, PositionPressure> positions = new HashMap<>();
        for (String position : CORE_POSITIONS) {
            int required = lineup.directStarterRequirements().getOrDefault(position, 0);
            positions.put(position, classifyPosition(position, required, depth.teams(), globalReason));
        }
        return new PositionalPressureReport(depth.leagueId(), depth.source(), depth.minimumAsOfDate(),
            LeaguePositionalPressurePolicy.POLICY_ID, lineup.policyId(), lineup.flexSlots(), lineup.superFlexSlots(),
            Map.copyOf(positions), lineup.unknownSlots());
    }

    private static PositionPressure classifyPosition(String position, int required,
                                                      List<LeaguePositionalDepthAnalyzer.TeamDepth> teams,
                                                      String globalReason) {
        List<TeamPositionPressure> evidence = new ArrayList<>();
        for (var team : teams) {
            var positionDepth = team.positions().get(position);
            int totalPlayers = positionDepth == null ? 0 : positionDepth.totalPlayers();
            int valuedPlayers = positionDepth == null ? 0 : positionDepth.valuedPlayers();
            int stalePlayers = positionDepth == null ? 0 : positionDepth.stalePlayers();
            int missingPlayers = positionDepth == null ? 0 : positionDepth.missingPlayers();
            double starterCoverageValue = positionDepth == null ? 0.0 : positionDepth.topPlayers(required).stream()
                .mapToDouble(LeaguePositionalDepthAnalyzer.PlayerDepthValue::value).sum();
            double totalPositionValue = positionDepth == null ? 0.0 : positionDepth.totalUsableValue();
            evidence.add(new TeamPositionPressure(team.teamId(), team.teamName(), starterCoverageValue,
                totalPositionValue, totalPlayers, valuedPlayers, stalePlayers, missingPlayers,
                LeaguePositionalPressurePolicy.Tier.INSUFFICIENT_EVIDENCE));
        }

        if (globalReason != null) return unavailable(position, required, globalReason, evidence);
        if (required == 0) {
            return new PositionPressure(position, 0, false,
                "This league has no direct " + position + " starter requirement; FLEX/SUPERFLEX exposure is reported separately.",
                evidence.stream().map(t -> t.withTier(LeaguePositionalPressurePolicy.Tier.NO_DIRECT_REQUIREMENT)).toList());
        }
        boolean complete = evidence.stream().allMatch(t -> t.stalePlayers() == 0 && t.missingPlayers() == 0);
        if (!complete) return unavailable(position, required,
            "Complete current value coverage is required for every rostered " + position + ".", evidence);

        List<TeamPositionPressure> ranked = new ArrayList<>(evidence);
        ranked.sort(Comparator.comparingDouble(TeamPositionPressure::starterCoverageValue).reversed()
            .thenComparing(TeamPositionPressure::teamId));
        int outerCount = (int) Math.floor(ranked.size() * 0.25);
        double topBoundary = ranked.get(outerCount - 1).starterCoverageValue();
        double bottomBoundary = ranked.get(ranked.size() - outerCount).starterCoverageValue();
        Map<String, LeaguePositionalPressurePolicy.Tier> tiers = new HashMap<>();
        for (var team : ranked) {
            boolean top = team.starterCoverageValue() >= topBoundary;
            boolean bottom = team.starterCoverageValue() <= bottomBoundary;
            var tier = top && !bottom ? LeaguePositionalPressurePolicy.Tier.POSITION_STRENGTH
                : bottom && !top ? LeaguePositionalPressurePolicy.Tier.POSITION_PRESSURE
                : LeaguePositionalPressurePolicy.Tier.POSITION_BALANCED;
            tiers.put(team.teamId(), tier);
        }
        List<TeamPositionPressure> classified = evidence.stream().map(t -> t.withTier(tiers.get(t.teamId())))
            .sorted(Comparator.comparing(TeamPositionPressure::teamName, String.CASE_INSENSITIVE_ORDER)
                .thenComparing(TeamPositionPressure::teamId)).toList();
        return new PositionPressure(position, required, true, null, classified);
    }

    private static PositionPressure unavailable(String position, int required, String reason,
                                                List<TeamPositionPressure> evidence) {
        return new PositionPressure(position, required, false, reason,
            evidence.stream().map(t -> t.withTier(LeaguePositionalPressurePolicy.Tier.INSUFFICIENT_EVIDENCE)).toList());
    }

    public record TeamPositionPressure(String teamId, String teamName, double starterCoverageValue,
                                       double totalPositionValue, int totalPlayers, int valuedPlayers,
                                       int stalePlayers, int missingPlayers,
                                       LeaguePositionalPressurePolicy.Tier tier) {
        public TeamPositionPressure {
            if (teamId == null || teamId.isBlank()) throw new IllegalArgumentException("teamId must not be blank");
            if (teamName == null || teamName.isBlank()) throw new IllegalArgumentException("teamName must not be blank");
            if (!Double.isFinite(starterCoverageValue) || starterCoverageValue < 0.0) throw new IllegalArgumentException("starterCoverageValue invalid");
            if (!Double.isFinite(totalPositionValue) || totalPositionValue < 0.0) throw new IllegalArgumentException("totalPositionValue invalid");
            if (totalPlayers < 0 || valuedPlayers < 0 || stalePlayers < 0 || missingPlayers < 0) throw new IllegalArgumentException("coverage counts must be non-negative");
            Objects.requireNonNull(tier, "tier must not be null");
        }
        TeamPositionPressure withTier(LeaguePositionalPressurePolicy.Tier replacement) {
            return new TeamPositionPressure(teamId, teamName, starterCoverageValue, totalPositionValue,
                totalPlayers, valuedPlayers, stalePlayers, missingPlayers, replacement);
        }
    }

    public record PositionPressure(String position, int directStarterRequirement, boolean available,
                                   String insufficiencyReason, List<TeamPositionPressure> teams) {
        public PositionPressure {
            if (position == null || position.isBlank()) throw new IllegalArgumentException("position must not be blank");
            if (directStarterRequirement < 0) throw new IllegalArgumentException("directStarterRequirement must not be negative");
            teams = List.copyOf(Objects.requireNonNull(teams, "teams must not be null"));
            if (available && insufficiencyReason != null) throw new IllegalArgumentException("available position cannot have insufficiencyReason");
            if (!available && (insufficiencyReason == null || insufficiencyReason.isBlank())) throw new IllegalArgumentException("unavailable position requires insufficiencyReason");
        }
    }

    public record PositionalPressureReport(String leagueId, String source, LocalDate minimumAsOfDate,
                                           String policyId, String lineupPolicyId, int flexSlots,
                                           int superFlexSlots, Map<String, PositionPressure> positions,
                                           List<String> unknownLineupSlots) {
        public PositionalPressureReport {
            if (leagueId == null || leagueId.isBlank()) throw new IllegalArgumentException("leagueId must not be blank");
            if (source == null || source.isBlank()) throw new IllegalArgumentException("source must not be blank");
            if (!LeaguePositionalPressurePolicy.POLICY_ID.equals(policyId)) throw new IllegalArgumentException("unexpected policyId");
            Objects.requireNonNull(lineupPolicyId, "lineupPolicyId must not be null");
            if (flexSlots < 0 || superFlexSlots < 0) throw new IllegalArgumentException("flex counts must not be negative");
            positions = Map.copyOf(Objects.requireNonNull(positions, "positions must not be null"));
            unknownLineupSlots = List.copyOf(Objects.requireNonNull(unknownLineupSlots, "unknownLineupSlots must not be null"));
        }
    }
}
