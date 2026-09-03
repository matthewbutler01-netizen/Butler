package io.butler.bet.intelligence;

import io.butler.bet.domain.TeamSeasonPerformance;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;

/**
 * Applies the governed league-relative competitive tier policy to observed performance evidence.
 * Ranking is lexicographic: win percentage, then points-for per game, then point differential per
 * game. No weighted composite score is created.
 */
public final class LeagueCompetitiveTierAnalyzer {

    public CompetitiveTierReport analyze(LeaguePerformanceEvidenceAnalyzer.PerformanceReport performance) {
        Objects.requireNonNull(performance, "performance must not be null");
        if (performance.teams().size() < 4) {
            return insufficient(performance, "At least four league teams are required for relative tiers.");
        }
        if (!performance.complete()) {
            return insufficient(performance, "League performance coverage is incomplete.");
        }
        if (performance.teams().stream().anyMatch(team -> team.performance().gamesPlayed() < LeagueCompetitiveTierPolicy.MIN_GAMES)) {
            return insufficient(performance, "All league teams require at least four completed games.");
        }

        List<RankedTeam> ranked = performance.teams().stream()
            .map(LeagueCompetitiveTierAnalyzer::ranked)
            .sorted(RANKING.reversed())
            .toList();
        int outerSize = LeagueCompetitiveTierPolicy.outerTierSize(ranked.size());
        RankingKey frontBoundary = ranked.get(outerSize - 1).key();
        RankingKey backBoundary = ranked.get(ranked.size() - outerSize).key();
        boolean collapsedBoundaries = frontBoundary.compareTo(backBoundary) == 0;

        List<TeamTier> tiers = new ArrayList<>();
        for (RankedTeam team : ranked) {
            LeagueCompetitiveTierPolicy.Tier tier;
            if (collapsedBoundaries) {
                tier = LeagueCompetitiveTierPolicy.Tier.MIDDLE_TIER;
            } else if (team.key().compareTo(frontBoundary) >= 0) {
                tier = LeagueCompetitiveTierPolicy.Tier.FRONT_TIER;
            } else if (team.key().compareTo(backBoundary) <= 0) {
                tier = LeagueCompetitiveTierPolicy.Tier.BACK_TIER;
            } else {
                tier = LeagueCompetitiveTierPolicy.Tier.MIDDLE_TIER;
            }
            tiers.add(team.freeze(tier));
        }
        tiers.sort(Comparator.comparing(TeamTier::teamName, String.CASE_INSENSITIVE_ORDER)
            .thenComparing(TeamTier::teamId));
        return new CompetitiveTierReport(performance.leagueId(), performance.season(), performance.source(),
            LeagueCompetitiveTierPolicy.POLICY_ID, true, null, List.copyOf(tiers));
    }

    private static CompetitiveTierReport insufficient(LeaguePerformanceEvidenceAnalyzer.PerformanceReport performance,
                                                      String reason) {
        List<TeamTier> tiers = performance.teams().stream()
            .map(team -> {
                TeamSeasonPerformance p = team.performance();
                return new TeamTier(team.teamId(), team.teamName(), LeagueCompetitiveTierPolicy.Tier.INSUFFICIENT_EVIDENCE,
                    p == null ? null : p.gamesPlayed(),
                    p == null ? null : p.winPercentage(),
                    p == null || p.gamesPlayed() == 0 ? null : p.pointsFor() / p.gamesPlayed(),
                    p == null || p.gamesPlayed() == 0 ? null : p.pointDifferential() / p.gamesPlayed());
            })
            .toList();
        return new CompetitiveTierReport(performance.leagueId(), performance.season(), performance.source(),
            LeagueCompetitiveTierPolicy.POLICY_ID, false, reason, tiers);
    }

    private static RankedTeam ranked(LeaguePerformanceEvidenceAnalyzer.TeamPerformanceEvidence team) {
        TeamSeasonPerformance p = Objects.requireNonNull(team.performance(), "performance must not be null");
        double pfPerGame = p.pointsFor() / p.gamesPlayed();
        double pdPerGame = p.pointDifferential() / p.gamesPlayed();
        RankingKey key = new RankingKey(p.winPercentage(), pfPerGame, pdPerGame);
        return new RankedTeam(team.teamId(), team.teamName(), p.gamesPlayed(), key);
    }

    private static final Comparator<RankedTeam> RANKING = Comparator.comparing(RankedTeam::key);

    private record RankingKey(double winPercentage, double pointsForPerGame, double pointDifferentialPerGame)
        implements Comparable<RankingKey> {
        @Override
        public int compareTo(RankingKey other) {
            int result = Double.compare(winPercentage, other.winPercentage);
            if (result != 0) return result;
            result = Double.compare(pointsForPerGame, other.pointsForPerGame);
            if (result != 0) return result;
            return Double.compare(pointDifferentialPerGame, other.pointDifferentialPerGame);
        }
    }

    private record RankedTeam(String teamId, String teamName, int gamesPlayed, RankingKey key) {
        TeamTier freeze(LeagueCompetitiveTierPolicy.Tier tier) {
            return new TeamTier(teamId, teamName, tier, gamesPlayed,
                key.winPercentage(), key.pointsForPerGame(), key.pointDifferentialPerGame());
        }
    }

    public record TeamTier(String teamId, String teamName, LeagueCompetitiveTierPolicy.Tier tier,
                           Integer gamesPlayed, Double winPercentage, Double pointsForPerGame,
                           Double pointDifferentialPerGame) {
        public TeamTier {
            Objects.requireNonNull(teamId, "teamId must not be null");
            Objects.requireNonNull(teamName, "teamName must not be null");
            Objects.requireNonNull(tier, "tier must not be null");
        }
    }

    public record CompetitiveTierReport(String leagueId, int season, String source, String policyId,
                                        boolean available, String unavailableReason, List<TeamTier> teams) {
        public CompetitiveTierReport {
            Objects.requireNonNull(leagueId, "leagueId must not be null");
            Objects.requireNonNull(source, "source must not be null");
            Objects.requireNonNull(policyId, "policyId must not be null");
            teams = List.copyOf(Objects.requireNonNull(teams, "teams must not be null"));
            if (available && unavailableReason != null) throw new IllegalArgumentException("available report must not have unavailableReason");
            if (!available && (unavailableReason == null || unavailableReason.isBlank())) {
                throw new IllegalArgumentException("unavailable report requires unavailableReason");
            }
        }
    }
}
