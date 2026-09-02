package io.butler.bet.intelligence;

import io.butler.bet.data.Database;

import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;

/**
 * Ranks complete team asset portfolios by total persisted franchise value.
 * Rankings are intentionally unavailable when any player or draft-pick value is missing.
 */
public final class FranchiseValueRankingAnalyzer {
    private final TeamAssetPortfolioAnalyzer portfolios;

    public FranchiseValueRankingAnalyzer(Database database) {
        Objects.requireNonNull(database, "database must not be null");
        this.portfolios = new TeamAssetPortfolioAnalyzer(database);
    }

    public RankingReport rank(String leagueId) throws SQLException {
        return rank(portfolios.analyze(leagueId));
    }

    public RankingReport rank(String leagueId, String source) throws SQLException {
        return rank(portfolios.analyze(leagueId, source));
    }

    private RankingReport rank(TeamAssetPortfolioAnalyzer.PortfolioReport portfolio) {
        if (!portfolio.complete()) {
            List<String> incomplete = new ArrayList<>();
            for (var team : portfolio.teams()) {
                if (!team.complete()) {
                    incomplete.add(team.teamName() + " [" + team.teamId() + "]"
                        + " missing-players=" + team.missingPlayers()
                        + " missing-picks=" + team.missingDraftPicks());
                }
            }
            throw new IllegalArgumentException("franchise value ranking requires complete asset coverage for source "
                + portfolio.source() + ": " + String.join(", ", incomplete));
        }

        List<TeamAssetPortfolioAnalyzer.TeamPortfolio> sorted = new ArrayList<>(portfolio.teams());
        sorted.sort(Comparator.comparingDouble(TeamAssetPortfolioAnalyzer.TeamPortfolio::totalAssetValue).reversed()
            .thenComparing(Comparator.comparingDouble(TeamAssetPortfolioAnalyzer.TeamPortfolio::playerValue).reversed())
            .thenComparing(TeamAssetPortfolioAnalyzer.TeamPortfolio::teamName, String.CASE_INSENSITIVE_ORDER)
            .thenComparing(TeamAssetPortfolioAnalyzer.TeamPortfolio::teamId));

        List<FranchiseValue> ranked = new ArrayList<>();
        for (int i = 0; i < sorted.size(); i++) {
            var team = sorted.get(i);
            ranked.add(new FranchiseValue(
                i + 1,
                team.teamId(),
                team.teamName(),
                team.playerValue(),
                team.draftPickValue(),
                team.totalAssetValue(),
                team.valuedPlayers(),
                team.valuedDraftPicks(),
                team.oldestValueDate(),
                team.latestValueDate()));
        }

        return new RankingReport(
            portfolio.leagueId(),
            portfolio.source(),
            portfolio.playerValue(),
            portfolio.draftPickValue(),
            portfolio.totalAssetValue(),
            List.copyOf(ranked));
    }

    public record RankingReport(String leagueId, String source,
                                double playerValue, double draftPickValue, double totalAssetValue,
                                List<FranchiseValue> teams) {}

    public record FranchiseValue(int rank, String teamId, String teamName,
                                 double playerValue, double draftPickValue, double totalAssetValue,
                                 int valuedPlayers, int valuedDraftPicks,
                                 java.time.LocalDate oldestValueDate,
                                 java.time.LocalDate latestValueDate) {}
}
