package io.butler.bet.intelligence;

import io.butler.bet.data.Database;

import java.sql.SQLException;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Composes neutral age, raw-production context, and governed supporting-evidence flags by team.
 * The dimensions stay separate and are never reduced to a blended score, grade, roster posture,
 * strategy label, dynasty adjustment, or recommendation.
 */
public final class LeaguePlayerEvidenceProfileAnalyzer {
    private final LeagueAgeContextAnalyzer age;
    private final LeagueProductionContextAnalyzer production;
    private final LeagueAgeOutlookSupportingEvidenceAnalyzer supportingEvidence;

    public LeaguePlayerEvidenceProfileAnalyzer(Database database) {
        Objects.requireNonNull(database, "database must not be null");
        this.age = new LeagueAgeContextAnalyzer(database);
        this.production = new LeagueProductionContextAnalyzer(database);
        this.supportingEvidence = new LeagueAgeOutlookSupportingEvidenceAnalyzer(database);
    }

    public PlayerEvidenceProfileReport analyze(String leagueId) throws SQLException {
        var productionReport = production.analyze(leagueId);
        return compose(age.analyze(leagueId), productionReport,
            supportingEvidence.analyze(leagueId, productionReport.season()));
    }

    public PlayerEvidenceProfileReport analyze(String leagueId, int season) throws SQLException {
        return compose(age.analyze(leagueId), production.analyze(leagueId, season),
            supportingEvidence.analyze(leagueId, season));
    }

    public PlayerEvidenceProfileReport analyze(String leagueId, int season, LocalDate ageAsOf,
                                               LocalDate minimumProfileAsOf) throws SQLException {
        Objects.requireNonNull(ageAsOf, "ageAsOf must not be null");
        return compose(
            age.analyze(leagueId, ageAsOf, LeaguePlayerProfileCoverageAnalyzer.DEFAULT_PROVIDER_SOURCE,
                minimumProfileAsOf),
            production.analyze(leagueId, season),
            supportingEvidence.analyze(leagueId, season));
    }

    public PlayerEvidenceProfileReport analyze(String leagueId, LocalDate ageAsOf,
                                               LocalDate minimumProfileAsOf) throws SQLException {
        Objects.requireNonNull(ageAsOf, "ageAsOf must not be null");
        var productionReport = production.analyze(leagueId);
        return compose(
            age.analyze(leagueId, ageAsOf, LeaguePlayerProfileCoverageAnalyzer.DEFAULT_PROVIDER_SOURCE,
                minimumProfileAsOf),
            productionReport,
            supportingEvidence.analyze(leagueId, productionReport.season()));
    }

    static PlayerEvidenceProfileReport compose(
        LeagueAgeContextAnalyzer.AgeContextReport ageReport,
        LeagueProductionContextAnalyzer.ProductionContextReport productionReport,
        LeagueAgeOutlookSupportingEvidenceAnalyzer.SupportingEvidenceReport supportingReport) {
        Objects.requireNonNull(ageReport, "ageReport must not be null");
        Objects.requireNonNull(productionReport, "productionReport must not be null");
        Objects.requireNonNull(supportingReport, "supportingReport must not be null");
        if (!ageReport.leagueId().equals(productionReport.leagueId())
            || !ageReport.leagueId().equals(supportingReport.leagueId())) {
            throw new IllegalStateException("evidence dimensions reference different leagues");
        }
        if (productionReport.season() != supportingReport.season()) {
            throw new IllegalStateException("production and supporting evidence reference different seasons");
        }

        Map<String, LeagueProductionContextAnalyzer.TeamProductionContext> productionByTeam = new LinkedHashMap<>();
        for (var team : productionReport.teams()) productionByTeam.put(team.teamId(), team);

        Map<String, List<LeagueAgeOutlookSupportingEvidenceAnalyzer.PlayerSupportingEvidence>> supportingByTeam =
            new LinkedHashMap<>();
        for (var player : supportingReport.players()) {
            supportingByTeam.computeIfAbsent(player.teamId(), ignored -> new ArrayList<>()).add(player);
        }
        supportingByTeam.values().forEach(players -> players.sort(
            Comparator.comparing(LeagueAgeOutlookSupportingEvidenceAnalyzer.PlayerSupportingEvidence::playerName,
                    String.CASE_INSENSITIVE_ORDER)
                .thenComparing(LeagueAgeOutlookSupportingEvidenceAnalyzer.PlayerSupportingEvidence::playerId)));

        List<TeamPlayerEvidenceProfile> teams = new ArrayList<>();
        for (var ageTeam : ageReport.teams()) {
            var productionTeam = productionByTeam.remove(ageTeam.teamId());
            if (productionTeam == null) {
                throw new IllegalStateException("production context missing team: " + ageTeam.teamId());
            }
            if (ageTeam.totalPlayers() != productionTeam.totalPlayers()) {
                throw new IllegalStateException("age/production roster counts differ for team: " + ageTeam.teamId());
            }
            var supportingPlayers = supportingByTeam.remove(ageTeam.teamId());
            if (supportingPlayers == null) supportingPlayers = List.of();
            if (!supportingPlayers.isEmpty() && supportingPlayers.size() != ageTeam.totalPlayers()) {
                throw new IllegalStateException("supporting-evidence roster count differs for team: " + ageTeam.teamId());
            }
            teams.add(new TeamPlayerEvidenceProfile(ageTeam.teamId(), ageTeam.teamName(), ageTeam, productionTeam,
                List.copyOf(supportingPlayers)));
        }
        if (!productionByTeam.isEmpty()) {
            throw new IllegalStateException("age context missing teams: " + productionByTeam.keySet());
        }
        if (!supportingByTeam.isEmpty()) {
            throw new IllegalStateException("age context missing supporting-evidence teams: " + supportingByTeam.keySet());
        }
        teams.sort(Comparator.comparing(TeamPlayerEvidenceProfile::teamName, String.CASE_INSENSITIVE_ORDER)
            .thenComparing(TeamPlayerEvidenceProfile::teamId));

        return new PlayerEvidenceProfileReport(
            ageReport.leagueId(), productionReport.season(), ageReport.ageAsOf(), ageReport.providerSource(),
            ageReport.minimumProviderAsOf(), productionReport.source(), supportingReport.modelAgeAsOf(),
            supportingReport.supportPolicyId(), supportingReport.outlookPolicyId(),
            supportingReport.modelProfileSource(), supportingReport.modelProductionSource(), List.copyOf(teams));
    }

    public record PlayerEvidenceProfileReport(String leagueId, int season, LocalDate ageAsOf,
                                              String profileSource, LocalDate minimumProfileAsOf,
                                              String productionSource, LocalDate modelAgeAsOf,
                                              String supportPolicyId, String outlookPolicyId,
                                              String modelProfileSource, String modelProductionSource,
                                              List<TeamPlayerEvidenceProfile> teams) {
        public PlayerEvidenceProfileReport {
            Objects.requireNonNull(leagueId, "leagueId must not be null");
            Objects.requireNonNull(ageAsOf, "ageAsOf must not be null");
            Objects.requireNonNull(profileSource, "profileSource must not be null");
            Objects.requireNonNull(productionSource, "productionSource must not be null");
            Objects.requireNonNull(modelAgeAsOf, "modelAgeAsOf must not be null");
            Objects.requireNonNull(supportPolicyId, "supportPolicyId must not be null");
            Objects.requireNonNull(outlookPolicyId, "outlookPolicyId must not be null");
            Objects.requireNonNull(modelProfileSource, "modelProfileSource must not be null");
            Objects.requireNonNull(modelProductionSource, "modelProductionSource must not be null");
            teams = List.copyOf(Objects.requireNonNull(teams, "teams must not be null"));
        }
        public int totalPlayers() { return teams.stream().mapToInt(TeamPlayerEvidenceProfile::totalPlayers).sum(); }
        public int ageCoveredPlayers() { return teams.stream().mapToInt(team -> team.age().coveredPlayers()).sum(); }
        public int productionCoveredPlayers() { return teams.stream().mapToInt(team -> team.production().coveredPlayers()).sum(); }
        public int supportingFlags() { return teams.stream().mapToInt(TeamPlayerEvidenceProfile::supportingFlags).sum(); }
        public int directionalSupportingFlags() {
            return teams.stream().mapToInt(TeamPlayerEvidenceProfile::directionalSupportingFlags).sum();
        }
        public double ageCoveragePercent() { return percent(ageCoveredPlayers(), totalPlayers()); }
        public double productionCoveragePercent() { return percent(productionCoveredPlayers(), totalPlayers()); }
    }

    public record TeamPlayerEvidenceProfile(
        String teamId,
        String teamName,
        LeagueAgeContextAnalyzer.TeamAgeContext age,
        LeagueProductionContextAnalyzer.TeamProductionContext production,
        List<LeagueAgeOutlookSupportingEvidenceAnalyzer.PlayerSupportingEvidence> supportingEvidence) {
        public TeamPlayerEvidenceProfile {
            Objects.requireNonNull(teamId, "teamId must not be null");
            Objects.requireNonNull(teamName, "teamName must not be null");
            Objects.requireNonNull(age, "age must not be null");
            Objects.requireNonNull(production, "production must not be null");
            supportingEvidence = List.copyOf(Objects.requireNonNull(supportingEvidence, "supportingEvidence must not be null"));
        }
        public int totalPlayers() { return age.totalPlayers(); }
        public int supportingFlags() { return supportingEvidence.stream().mapToInt(player -> player.flags().size()).sum(); }
        public int directionalSupportingFlags() {
            return supportingEvidence.stream().mapToInt(player -> player.favorableFlags() + player.unfavorableFlags()).sum();
        }
        public double ageCoveragePercent() { return age.coveragePercent(); }
        public double productionCoveragePercent() { return production.coveragePercent(); }
    }

    private static double percent(int numerator, int denominator) {
        return denominator == 0 ? 0.0 : numerator * 100.0 / denominator;
    }
}
