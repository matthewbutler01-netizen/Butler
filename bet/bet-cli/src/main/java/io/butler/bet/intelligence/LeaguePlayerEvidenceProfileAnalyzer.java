package io.butler.bet.intelligence;

import io.butler.bet.data.Database;

import java.sql.SQLException;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Composes neutral age and raw-production context by team. The dimensions stay separate and are
 * never reduced to a blended score, grade, roster posture, or strategy label.
 */
public final class LeaguePlayerEvidenceProfileAnalyzer {
    private final LeagueAgeContextAnalyzer age;
    private final LeagueProductionContextAnalyzer production;

    public LeaguePlayerEvidenceProfileAnalyzer(Database database) {
        Objects.requireNonNull(database, "database must not be null");
        this.age = new LeagueAgeContextAnalyzer(database);
        this.production = new LeagueProductionContextAnalyzer(database);
    }

    public PlayerEvidenceProfileReport analyze(String leagueId) throws SQLException {
        return compose(age.analyze(leagueId), production.analyze(leagueId));
    }

    public PlayerEvidenceProfileReport analyze(String leagueId, int season) throws SQLException {
        return compose(age.analyze(leagueId), production.analyze(leagueId, season));
    }

    public PlayerEvidenceProfileReport analyze(String leagueId, int season, LocalDate ageAsOf,
                                               LocalDate minimumProfileAsOf) throws SQLException {
        Objects.requireNonNull(ageAsOf, "ageAsOf must not be null");
        return compose(
            age.analyze(leagueId, ageAsOf, LeaguePlayerProfileCoverageAnalyzer.DEFAULT_PROVIDER_SOURCE,
                minimumProfileAsOf),
            production.analyze(leagueId, season));
    }

    public PlayerEvidenceProfileReport analyze(String leagueId, LocalDate ageAsOf,
                                               LocalDate minimumProfileAsOf) throws SQLException {
        Objects.requireNonNull(ageAsOf, "ageAsOf must not be null");
        return compose(
            age.analyze(leagueId, ageAsOf, LeaguePlayerProfileCoverageAnalyzer.DEFAULT_PROVIDER_SOURCE,
                minimumProfileAsOf),
            production.analyze(leagueId));
    }

    private static PlayerEvidenceProfileReport compose(LeagueAgeContextAnalyzer.AgeContextReport ageReport,
                                                        LeagueProductionContextAnalyzer.ProductionContextReport productionReport) {
        if (!ageReport.leagueId().equals(productionReport.leagueId())) {
            throw new IllegalStateException("age and production reports reference different leagues");
        }

        Map<String, LeagueProductionContextAnalyzer.TeamProductionContext> productionByTeam = new LinkedHashMap<>();
        for (var team : productionReport.teams()) productionByTeam.put(team.teamId(), team);

        List<TeamPlayerEvidenceProfile> teams = new ArrayList<>();
        for (var ageTeam : ageReport.teams()) {
            var productionTeam = productionByTeam.remove(ageTeam.teamId());
            if (productionTeam == null) {
                throw new IllegalStateException("production context missing team: " + ageTeam.teamId());
            }
            if (ageTeam.totalPlayers() != productionTeam.totalPlayers()) {
                throw new IllegalStateException("age/production roster counts differ for team: " + ageTeam.teamId());
            }
            teams.add(new TeamPlayerEvidenceProfile(ageTeam.teamId(), ageTeam.teamName(), ageTeam, productionTeam));
        }
        if (!productionByTeam.isEmpty()) {
            throw new IllegalStateException("age context missing teams: " + productionByTeam.keySet());
        }
        teams.sort(Comparator.comparing(TeamPlayerEvidenceProfile::teamName, String.CASE_INSENSITIVE_ORDER)
            .thenComparing(TeamPlayerEvidenceProfile::teamId));

        return new PlayerEvidenceProfileReport(ageReport.leagueId(), productionReport.season(), ageReport.ageAsOf(),
            ageReport.providerSource(), ageReport.minimumProviderAsOf(), productionReport.source(), List.copyOf(teams));
    }

    public record PlayerEvidenceProfileReport(String leagueId, int season, LocalDate ageAsOf,
                                              String profileSource, LocalDate minimumProfileAsOf,
                                              String productionSource, List<TeamPlayerEvidenceProfile> teams) {
        public PlayerEvidenceProfileReport {
            Objects.requireNonNull(leagueId, "leagueId must not be null");
            Objects.requireNonNull(ageAsOf, "ageAsOf must not be null");
            Objects.requireNonNull(profileSource, "profileSource must not be null");
            Objects.requireNonNull(productionSource, "productionSource must not be null");
            teams = List.copyOf(Objects.requireNonNull(teams, "teams must not be null"));
        }
        public int totalPlayers() { return teams.stream().mapToInt(TeamPlayerEvidenceProfile::totalPlayers).sum(); }
        public int ageCoveredPlayers() { return teams.stream().mapToInt(team -> team.age().coveredPlayers()).sum(); }
        public int productionCoveredPlayers() { return teams.stream().mapToInt(team -> team.production().coveredPlayers()).sum(); }
        public double ageCoveragePercent() { return percent(ageCoveredPlayers(), totalPlayers()); }
        public double productionCoveragePercent() { return percent(productionCoveredPlayers(), totalPlayers()); }
    }

    public record TeamPlayerEvidenceProfile(String teamId, String teamName,
                                            LeagueAgeContextAnalyzer.TeamAgeContext age,
                                            LeagueProductionContextAnalyzer.TeamProductionContext production) {
        public TeamPlayerEvidenceProfile {
            Objects.requireNonNull(teamId, "teamId must not be null");
            Objects.requireNonNull(teamName, "teamName must not be null");
            Objects.requireNonNull(age, "age must not be null");
            Objects.requireNonNull(production, "production must not be null");
        }
        public int totalPlayers() { return age.totalPlayers(); }
        public double ageCoveragePercent() { return age.coveragePercent(); }
        public double productionCoveragePercent() { return production.coveragePercent(); }
    }

    private static double percent(int numerator, int denominator) {
        return denominator == 0 ? 0.0 : numerator * 100.0 / denominator;
    }
}
