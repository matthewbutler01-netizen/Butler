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
 * Composes profile/age and season-production coverage into evidence readiness only. It does not
 * score players, infer career arcs, or recommend strategy.
 */
public final class LeaguePlayerEvidenceReadinessAnalyzer {
    private final LeaguePlayerProfileCoverageAnalyzer profiles;
    private final LeagueProductionCoverageAnalyzer production;

    public LeaguePlayerEvidenceReadinessAnalyzer(Database database) {
        Objects.requireNonNull(database, "database must not be null");
        this.profiles = new LeaguePlayerProfileCoverageAnalyzer(database);
        this.production = new LeagueProductionCoverageAnalyzer(database);
    }

    public ReadinessReport analyze(String leagueId, int season) throws SQLException {
        return analyze(leagueId, season,
            LeaguePlayerProfileCoverageAnalyzer.DEFAULT_PROVIDER_SOURCE,
            NflversePlayerSeasonProductionImporter.SOURCE, null);
    }

    public ReadinessReport analyze(String leagueId, int season, LocalDate minimumProfileAsOf) throws SQLException {
        return analyze(leagueId, season,
            LeaguePlayerProfileCoverageAnalyzer.DEFAULT_PROVIDER_SOURCE,
            NflversePlayerSeasonProductionImporter.SOURCE, minimumProfileAsOf);
    }

    public ReadinessReport analyze(String leagueId, int season, String profileSource,
                                   String productionSource, LocalDate minimumProfileAsOf) throws SQLException {
        var profileReport = profiles.analyze(leagueId, profileSource, minimumProfileAsOf);
        var productionReport = production.analyze(leagueId, season, productionSource);

        Map<String, LeagueProductionCoverageAnalyzer.TeamCoverage> productionByTeam = new LinkedHashMap<>();
        for (var team : productionReport.teams()) productionByTeam.put(team.teamId(), team);

        List<TeamReadiness> teams = new ArrayList<>();
        for (var profileTeam : profileReport.teams()) {
            var productionTeam = productionByTeam.remove(profileTeam.teamId());
            if (productionTeam == null) {
                throw new IllegalStateException("production coverage missing team: " + profileTeam.teamId());
            }
            int totalPlayers = profileTeam.counts().totalPlayers();
            if (totalPlayers != productionTeam.totalPlayers()) {
                throw new IllegalStateException("profile/production roster counts differ for team: " + profileTeam.teamId());
            }
            teams.add(new TeamReadiness(
                profileTeam.teamId(), profileTeam.teamName(), totalPlayers,
                profileTeam.counts().ageEvidencePlayers(), profileTeam.counts().exactBirthDatePlayers(),
                profileTeam.counts().reportedAgePlayers(), profileTeam.counts().experienceEvidencePlayers(),
                productionTeam.coveredPlayers(),
                readiness(totalPlayers, profileTeam.counts().ageEvidencePlayers(), productionTeam.coveredPlayers())));
        }
        if (!productionByTeam.isEmpty()) {
            throw new IllegalStateException("profile coverage missing teams: " + productionByTeam.keySet());
        }
        teams.sort(Comparator.comparing(TeamReadiness::teamName, String.CASE_INSENSITIVE_ORDER)
            .thenComparing(TeamReadiness::teamId));

        return new ReadinessReport(profileReport.leagueId(), season, profileReport.providerSource(),
            productionReport.source(), minimumProfileAsOf, List.copyOf(teams));
    }

    private static Readiness readiness(int totalPlayers, int ageEvidencePlayers, int productionPlayers) {
        if (totalPlayers == 0) return Readiness.EMPTY;
        if (ageEvidencePlayers == totalPlayers && productionPlayers == totalPlayers) return Readiness.READY;
        if (ageEvidencePlayers == 0 || productionPlayers == 0) return Readiness.BLOCKED;
        return Readiness.PARTIAL;
    }

    public enum Readiness { EMPTY, BLOCKED, PARTIAL, READY }

    public record ReadinessReport(String leagueId, int season, String profileSource,
                                  String productionSource, LocalDate minimumProfileAsOf,
                                  List<TeamReadiness> teams) {
        public ReadinessReport { teams = List.copyOf(Objects.requireNonNull(teams)); }
        public int totalPlayers() { return teams.stream().mapToInt(TeamReadiness::totalPlayers).sum(); }
        public int ageEvidencePlayers() { return teams.stream().mapToInt(TeamReadiness::ageEvidencePlayers).sum(); }
        public int productionEvidencePlayers() { return teams.stream().mapToInt(TeamReadiness::productionEvidencePlayers).sum(); }
        public double ageCoveragePercent() { return percent(ageEvidencePlayers(), totalPlayers()); }
        public double productionCoveragePercent() { return percent(productionEvidencePlayers(), totalPlayers()); }
        public Readiness readiness() { return readiness(totalPlayers(), ageEvidencePlayers(), productionEvidencePlayers()); }
        public boolean ready() { return readiness() == Readiness.READY; }
    }

    public record TeamReadiness(String teamId, String teamName, int totalPlayers,
                                int ageEvidencePlayers, int exactBirthDatePlayers,
                                int reportedAgePlayers, int experienceEvidencePlayers,
                                int productionEvidencePlayers, Readiness readiness) {
        public double ageCoveragePercent() { return percent(ageEvidencePlayers, totalPlayers); }
        public double productionCoveragePercent() { return percent(productionEvidencePlayers, totalPlayers); }
        public boolean ready() { return readiness == Readiness.READY; }
    }

    private static double percent(int numerator, int denominator) {
        return denominator == 0 ? 0.0 : numerator * 100.0 / denominator;
    }
}
