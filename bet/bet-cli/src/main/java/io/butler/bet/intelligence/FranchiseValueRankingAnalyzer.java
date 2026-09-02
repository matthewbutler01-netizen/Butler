package io.butler.bet.intelligence;

import io.butler.bet.data.Database;

import java.sql.SQLException;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;

/**
 * Ranks complete team asset portfolios by total persisted franchise value.
 * Rankings are unavailable when any player or draft-pick value is missing. Callers may also supply
 * an explicit minimum as-of date to prevent rankings from using stale persisted values.
 */
public final class FranchiseValueRankingAnalyzer {
    private final TeamAssetPortfolioAnalyzer portfolios;
    private final FranchiseValueReadinessAnalyzer readiness;

    public FranchiseValueRankingAnalyzer(Database database) {
        Objects.requireNonNull(database, "database must not be null");
        this.portfolios = new TeamAssetPortfolioAnalyzer(database);
        this.readiness = new FranchiseValueReadinessAnalyzer(database);
    }

    public RankingReport rank(String leagueId) throws SQLException {
        return rank(portfolios.analyze(leagueId), null);
    }

    public RankingReport rank(String leagueId, String source) throws SQLException {
        return rank(portfolios.analyze(leagueId, source), null);
    }

    public RankingReport rank(String leagueId, LocalDate minimumAsOfDate) throws SQLException {
        LocalDate cutoff = Objects.requireNonNull(minimumAsOfDate, "minimumAsOfDate must not be null");
        var readinessReport = readiness.analyze(leagueId, cutoff);
        requireRankable(readinessReport);
        return rank(portfolios.analyze(leagueId), cutoff);
    }

    public RankingReport rank(String leagueId, String source, LocalDate minimumAsOfDate) throws SQLException {
        LocalDate cutoff = Objects.requireNonNull(minimumAsOfDate, "minimumAsOfDate must not be null");
        var readinessReport = readiness.analyze(leagueId, source, cutoff);
        requireRankable(readinessReport);
        return rank(portfolios.analyze(leagueId, source), cutoff);
    }

    private RankingReport rank(TeamAssetPortfolioAnalyzer.PortfolioReport portfolio, LocalDate minimumAsOfDate) {
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
            minimumAsOfDate,
            portfolio.playerValue(),
            portfolio.draftPickValue(),
            portfolio.totalAssetValue(),
            List.copyOf(ranked));
    }

    private static void requireRankable(FranchiseValueReadinessAnalyzer.ReadinessReport report) {
        if (report.rankable()) return;
        throw new IllegalArgumentException(
            "franchise value ranking requires READY asset coverage on or after " + report.minimumAsOfDate()
                + " for source " + report.source()
                + ": status=" + report.status()
                + " missing-assets=" + report.missingAssets()
                + " stale-assets=" + report.staleAssets()
                + " oldest-value-date=" + report.oldestValueDate());
    }

    public record RankingReport(String leagueId, String source, LocalDate minimumAsOfDate,
                                double playerValue, double draftPickValue, double totalAssetValue,
                                List<FranchiseValue> teams) {}

    public record FranchiseValue(int rank, String teamId, String teamName,
                                 double playerValue, double draftPickValue, double totalAssetValue,
                                 int valuedPlayers, int valuedDraftPicks,
                                 LocalDate oldestValueDate,
                                 LocalDate latestValueDate) {}
}
