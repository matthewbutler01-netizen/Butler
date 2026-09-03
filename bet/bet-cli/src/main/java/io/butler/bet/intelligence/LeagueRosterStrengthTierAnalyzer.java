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
 * Governed league-relative current-roster strength tiers.
 *
 * Ranking is lexicographic: usable starter market value first, then total usable player market
 * value. Positional depth is retained as descriptive context only and draft capital is excluded.
 * No contender/rebuilder posture or recommendation is inferred.
 */
public final class LeagueRosterStrengthTierAnalyzer {
    private final LeagueCompositeTeamProfileAnalyzer profiles;

    public LeagueRosterStrengthTierAnalyzer(Database database) {
        this.profiles = new LeagueCompositeTeamProfileAnalyzer(Objects.requireNonNull(database, "database must not be null"));
    }

    public RosterStrengthReport analyze(String leagueId) throws SQLException {
        return compose(profiles.analyze(leagueId));
    }

    public RosterStrengthReport analyze(String leagueId, String source) throws SQLException {
        return compose(profiles.analyze(leagueId, source));
    }

    public RosterStrengthReport analyze(String leagueId, LocalDate minimumAsOfDate) throws SQLException {
        return compose(profiles.analyze(leagueId, minimumAsOfDate));
    }

    public RosterStrengthReport analyze(String leagueId, String source, LocalDate minimumAsOfDate) throws SQLException {
        return compose(profiles.analyze(leagueId, source, minimumAsOfDate));
    }

    public static RosterStrengthReport compose(LeagueCompositeTeamProfileAnalyzer.CompositeProfileReport profileReport) {
        Objects.requireNonNull(profileReport, "profileReport must not be null");
        if (profileReport.teams().isEmpty()) {
            throw new IllegalArgumentException("profile report must contain teams");
        }

        List<TeamRosterStrength> evidence = new ArrayList<>();
        boolean completeCoverage = true;
        for (var team : profileReport.teams()) {
            int totalPlayers = team.rosterSlots().slots().values().stream()
                .mapToInt(LeagueRosterSlotValueAnalyzer.SlotValue::totalPlayers).sum();
            int valuedPlayers = team.rosterSlots().slots().values().stream()
                .mapToInt(LeagueRosterSlotValueAnalyzer.SlotValue::valuedPlayers).sum();
            int stalePlayers = team.rosterSlots().slots().values().stream()
                .mapToInt(LeagueRosterSlotValueAnalyzer.SlotValue::stalePlayers).sum();
            int missingPlayers = team.rosterSlots().slots().values().stream()
                .mapToInt(LeagueRosterSlotValueAnalyzer.SlotValue::missingPlayers).sum();
            boolean teamComplete = totalPlayers > 0 && valuedPlayers == totalPlayers && stalePlayers == 0 && missingPlayers == 0;
            completeCoverage &= teamComplete;
            evidence.add(new TeamRosterStrength(
                team.teamId(), team.teamName(), starterValue(team), team.usablePlayerValue(),
                totalPlayers, valuedPlayers, stalePlayers, missingPlayers,
                LeagueRosterStrengthTierPolicy.Tier.INSUFFICIENT_EVIDENCE));
        }

        if (!completeCoverage) {
            return new RosterStrengthReport(profileReport.leagueId(), profileReport.source(), profileReport.minimumAsOfDate(),
                LeagueRosterStrengthTierPolicy.POLICY_ID, false,
                "Complete usable player-value coverage is required for every roster.", List.copyOf(evidence));
        }
        if (evidence.size() < LeagueRosterStrengthTierPolicy.MINIMUM_LEAGUE_TEAMS) {
            return new RosterStrengthReport(profileReport.leagueId(), profileReport.source(), profileReport.minimumAsOfDate(),
                LeagueRosterStrengthTierPolicy.POLICY_ID, false,
                "At least four league teams are required for relative roster tiers.", List.copyOf(evidence));
        }

        List<TeamRosterStrength> ranked = new ArrayList<>(evidence);
        ranked.sort(Comparator.comparingDouble(TeamRosterStrength::starterValue).reversed()
            .thenComparing(Comparator.comparingDouble(TeamRosterStrength::totalPlayerValue).reversed())
            .thenComparing(TeamRosterStrength::teamId));

        int outerCount = (int) Math.floor(ranked.size() * 0.25);
        Map<String, LeagueRosterStrengthTierPolicy.Tier> tiers = new HashMap<>();
        for (var team : ranked) tiers.put(team.teamId(), LeagueRosterStrengthTierPolicy.Tier.MIDDLE_ROSTER_TIER);

        RankKey frontBoundary = key(ranked.get(outerCount - 1));
        RankKey backBoundary = key(ranked.get(ranked.size() - outerCount));
        for (var team : ranked) {
            boolean front = compare(key(team), frontBoundary) >= 0;
            boolean back = compare(key(team), backBoundary) <= 0;
            if (front && !back) tiers.put(team.teamId(), LeagueRosterStrengthTierPolicy.Tier.FRONT_ROSTER_TIER);
            else if (back && !front) tiers.put(team.teamId(), LeagueRosterStrengthTierPolicy.Tier.BACK_ROSTER_TIER);
            else tiers.put(team.teamId(), LeagueRosterStrengthTierPolicy.Tier.MIDDLE_ROSTER_TIER);
        }

        List<TeamRosterStrength> classified = evidence.stream()
            .map(team -> team.withTier(tiers.get(team.teamId())))
            .sorted(Comparator.comparing(TeamRosterStrength::teamName, String.CASE_INSENSITIVE_ORDER)
                .thenComparing(TeamRosterStrength::teamId))
            .toList();
        return new RosterStrengthReport(profileReport.leagueId(), profileReport.source(), profileReport.minimumAsOfDate(),
            LeagueRosterStrengthTierPolicy.POLICY_ID, true, null, classified);
    }

    private static double starterValue(LeagueCompositeTeamProfileAnalyzer.TeamProfile team) {
        return team.rosterSlots().slots().getOrDefault("STARTER",
            new LeagueRosterSlotValueAnalyzer.SlotValue("STARTER", 0.0, 0, 0, 0, 0)).value();
    }

    private static RankKey key(TeamRosterStrength team) {
        return new RankKey(team.starterValue(), team.totalPlayerValue());
    }

    private static int compare(RankKey left, RankKey right) {
        int starter = Double.compare(left.starterValue(), right.starterValue());
        return starter != 0 ? starter : Double.compare(left.totalPlayerValue(), right.totalPlayerValue());
    }

    private record RankKey(double starterValue, double totalPlayerValue) {}

    public record TeamRosterStrength(String teamId, String teamName, double starterValue, double totalPlayerValue,
                                     int totalPlayers, int valuedPlayers, int stalePlayers, int missingPlayers,
                                     LeagueRosterStrengthTierPolicy.Tier tier) {
        public TeamRosterStrength {
            if (teamId == null || teamId.isBlank()) throw new IllegalArgumentException("teamId must not be blank");
            if (teamName == null || teamName.isBlank()) throw new IllegalArgumentException("teamName must not be blank");
            if (!Double.isFinite(starterValue) || starterValue < 0.0) throw new IllegalArgumentException("starterValue invalid");
            if (!Double.isFinite(totalPlayerValue) || totalPlayerValue < 0.0) throw new IllegalArgumentException("totalPlayerValue invalid");
            Objects.requireNonNull(tier, "tier must not be null");
        }

        TeamRosterStrength withTier(LeagueRosterStrengthTierPolicy.Tier replacement) {
            return new TeamRosterStrength(teamId, teamName, starterValue, totalPlayerValue,
                totalPlayers, valuedPlayers, stalePlayers, missingPlayers, replacement);
        }

        public double coveragePercent() {
            return totalPlayers == 0 ? 0.0 : valuedPlayers * 100.0 / totalPlayers;
        }
    }

    public record RosterStrengthReport(String leagueId, String source, LocalDate minimumAsOfDate,
                                       String policyId, boolean available, String insufficiencyReason,
                                       List<TeamRosterStrength> teams) {
        public RosterStrengthReport {
            if (leagueId == null || leagueId.isBlank()) throw new IllegalArgumentException("leagueId must not be blank");
            if (source == null || source.isBlank()) throw new IllegalArgumentException("source must not be blank");
            if (!LeagueRosterStrengthTierPolicy.POLICY_ID.equals(policyId)) throw new IllegalArgumentException("unexpected policyId");
            teams = List.copyOf(Objects.requireNonNull(teams, "teams must not be null"));
            if (available && insufficiencyReason != null) throw new IllegalArgumentException("available report cannot have insufficiencyReason");
            if (!available && (insufficiencyReason == null || insufficiencyReason.isBlank())) {
                throw new IllegalArgumentException("unavailable report requires insufficiencyReason");
            }
        }
    }
}
