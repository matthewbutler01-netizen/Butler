package io.butler.bet.intelligence;

import io.butler.bet.data.Database;
import io.butler.bet.data.PlayerSeasonProductionRepository;
import io.butler.bet.domain.PlayerSeasonProduction;

import java.sql.SQLException;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;

/**
 * Composes player age evidence with raw per-game season production. This is intentionally not an
 * aging curve or age-adjusted score: no peak-age assumptions, career-arc labels, cross-position
 * scoring, or strategy recommendations are introduced here.
 */
public final class LeagueAgeProductionContextAnalyzer {
    private final LeaguePlayerEvidenceProfileAnalyzer profiles;
    private final PlayerSeasonProductionRepository production;

    public LeagueAgeProductionContextAnalyzer(Database database) {
        Objects.requireNonNull(database, "database must not be null");
        this.profiles = new LeaguePlayerEvidenceProfileAnalyzer(database);
        this.production = new PlayerSeasonProductionRepository(database);
    }

    public AgeProductionReport analyze(String leagueId) throws SQLException {
        return build(profiles.analyze(leagueId));
    }

    public AgeProductionReport analyze(String leagueId, int season) throws SQLException {
        return build(profiles.analyze(leagueId, season));
    }

    public AgeProductionReport analyze(String leagueId, int season, LocalDate ageAsOf,
                                       LocalDate minimumProfileAsOf) throws SQLException {
        return build(profiles.analyze(leagueId, season, ageAsOf, minimumProfileAsOf));
    }

    public AgeProductionReport analyze(String leagueId, LocalDate ageAsOf,
                                       LocalDate minimumProfileAsOf) throws SQLException {
        return build(profiles.analyze(leagueId, ageAsOf, minimumProfileAsOf));
    }

    private AgeProductionReport build(LeaguePlayerEvidenceProfileAnalyzer.PlayerEvidenceProfileReport profile)
        throws SQLException {
        List<TeamAgeProductionContext> teams = new ArrayList<>();
        for (var team : profile.teams()) {
            List<PlayerAgeProductionContext> players = new ArrayList<>();
            for (var age : team.age().players()) {
                var snapshot = production.findLatest(age.playerId(), profile.season(), profile.productionSource());
                players.add(toPlayer(age, snapshot.orElse(null)));
            }
            players.sort(Comparator.comparing(PlayerAgeProductionContext::position)
                .thenComparing(PlayerAgeProductionContext::playerName, String.CASE_INSENSITIVE_ORDER)
                .thenComparing(PlayerAgeProductionContext::playerId));
            teams.add(new TeamAgeProductionContext(team.teamId(), team.teamName(), List.copyOf(players)));
        }
        teams.sort(Comparator.comparing(TeamAgeProductionContext::teamName, String.CASE_INSENSITIVE_ORDER)
            .thenComparing(TeamAgeProductionContext::teamId));
        return new AgeProductionReport(profile.leagueId(), profile.season(), profile.ageAsOf(),
            profile.profileSource(), profile.minimumProfileAsOf(), profile.productionSource(), List.copyOf(teams));
    }

    private static PlayerAgeProductionContext toPlayer(LeagueAgeContextAnalyzer.PlayerAgeContext age,
                                                        PlayerSeasonProduction production) {
        if (production == null) {
            return new PlayerAgeProductionContext(age.playerId(), age.playerName(), age.position(), age.rosterSlot(),
                age.age(), age.provenance(), false, 0, null, null, null, null, null, null, null, null, null);
        }
        int games = production.gamesPlayed();
        return new PlayerAgeProductionContext(age.playerId(), age.playerName(), age.position(), age.rosterSlot(),
            age.age(), age.provenance(), true, games,
            rate(production.passingYards(), games), rate(production.passingTouchdowns(), games),
            rate(production.interceptions(), games), rate(production.rushingYards(), games),
            rate(production.rushingTouchdowns(), games), rate(production.receptions(), games),
            rate(production.receivingYards(), games), rate(production.receivingTouchdowns(), games),
            rate(production.fumblesLost(), games));
    }

    private static Double rate(int value, int games) {
        return games <= 0 ? null : value / (double) games;
    }

    public record AgeProductionReport(String leagueId, int season, LocalDate ageAsOf,
                                      String profileSource, LocalDate minimumProfileAsOf,
                                      String productionSource, List<TeamAgeProductionContext> teams) {
        public AgeProductionReport {
            Objects.requireNonNull(leagueId, "leagueId must not be null");
            Objects.requireNonNull(ageAsOf, "ageAsOf must not be null");
            Objects.requireNonNull(profileSource, "profileSource must not be null");
            Objects.requireNonNull(productionSource, "productionSource must not be null");
            teams = List.copyOf(Objects.requireNonNull(teams, "teams must not be null"));
        }
        public int totalPlayers() { return teams.stream().mapToInt(team -> team.players().size()).sum(); }
        public int ageCoveredPlayers() { return teams.stream().mapToInt(TeamAgeProductionContext::ageCoveredPlayers).sum(); }
        public int productionCoveredPlayers() { return teams.stream().mapToInt(TeamAgeProductionContext::productionCoveredPlayers).sum(); }
        public int rateCoveredPlayers() { return teams.stream().mapToInt(TeamAgeProductionContext::rateCoveredPlayers).sum(); }
        public int jointCoveredPlayers() { return teams.stream().mapToInt(TeamAgeProductionContext::jointCoveredPlayers).sum(); }
        public double jointCoveragePercent() { return percent(jointCoveredPlayers(), totalPlayers()); }
    }

    public record TeamAgeProductionContext(String teamId, String teamName,
                                           List<PlayerAgeProductionContext> players) {
        public TeamAgeProductionContext {
            Objects.requireNonNull(teamId, "teamId must not be null");
            Objects.requireNonNull(teamName, "teamName must not be null");
            players = List.copyOf(Objects.requireNonNull(players, "players must not be null"));
        }
        public int ageCoveredPlayers() { return (int) players.stream().filter(PlayerAgeProductionContext::ageAvailable).count(); }
        public int productionCoveredPlayers() { return (int) players.stream().filter(PlayerAgeProductionContext::productionAvailable).count(); }
        public int rateCoveredPlayers() { return (int) players.stream().filter(PlayerAgeProductionContext::ratesAvailable).count(); }
        public int jointCoveredPlayers() { return (int) players.stream().filter(PlayerAgeProductionContext::jointEvidenceAvailable).count(); }
        public double jointCoveragePercent() { return percent(jointCoveredPlayers(), players.size()); }
    }

    public record PlayerAgeProductionContext(String playerId, String playerName, String position, String rosterSlot,
                                             Integer age, LeagueAgeContextAnalyzer.AgeProvenance ageProvenance,
                                             boolean productionSnapshotAvailable, int gamesPlayed,
                                             Double passingYardsPerGame, Double passingTouchdownsPerGame,
                                             Double interceptionsPerGame, Double rushingYardsPerGame,
                                             Double rushingTouchdownsPerGame, Double receptionsPerGame,
                                             Double receivingYardsPerGame, Double receivingTouchdownsPerGame,
                                             Double fumblesLostPerGame) {
        public PlayerAgeProductionContext {
            Objects.requireNonNull(playerId, "playerId must not be null");
            Objects.requireNonNull(playerName, "playerName must not be null");
            Objects.requireNonNull(position, "position must not be null");
            Objects.requireNonNull(ageProvenance, "ageProvenance must not be null");
        }
        public boolean ageAvailable() { return age != null; }
        public boolean productionAvailable() { return productionSnapshotAvailable; }
        public boolean ratesAvailable() { return productionSnapshotAvailable && gamesPlayed > 0; }
        public boolean jointEvidenceAvailable() { return ageAvailable() && productionAvailable(); }
    }

    private static double percent(int numerator, int denominator) {
        return denominator == 0 ? 0.0 : numerator * 100.0 / denominator;
    }
}
