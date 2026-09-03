package io.butler.bet.intelligence;

import io.butler.bet.data.Database;

import java.sql.SQLException;
import java.time.LocalDate;
import java.time.Period;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;

/**
 * Composes league roster profile evidence with the governed published aging model.
 * Model age is always exact-DOB age on September 1 of the requested season. Provider-reported
 * age is never substituted, and no score, career-stage label, dynasty adjustment, or recommendation
 * is produced here.
 */
public final class LeagueAgingModelEvidenceAnalyzer {
    private static final List<String> SUPPORTED_POSITIONS = List.of("QB", "RB", "WR", "TE");

    private final LeaguePlayerProfileCoverageAnalyzer profiles;
    private final AgingModelLocalSmootherAnalyzer smoother;

    public LeagueAgingModelEvidenceAnalyzer(Database database) {
        Objects.requireNonNull(database, "database must not be null");
        this.profiles = new LeaguePlayerProfileCoverageAnalyzer(database);
        this.smoother = new AgingModelLocalSmootherAnalyzer(database);
    }

    public LeagueAgingModelEvidenceReport analyze(String leagueId, int season) throws SQLException {
        if (season < 1999 || season > 2100) {
            throw new IllegalArgumentException("season must be between 1999 and 2100");
        }
        return compose(profiles.analyze(leagueId), smoother.analyze(), season);
    }

    static LeagueAgingModelEvidenceReport compose(
        LeaguePlayerProfileCoverageAnalyzer.CoverageReport profileReport,
        AgingModelLocalSmootherAnalyzer.LocalSmootherReport smootherReport,
        int season) {
        Objects.requireNonNull(profileReport, "profileReport must not be null");
        Objects.requireNonNull(smootherReport, "smootherReport must not be null");
        if (season < 1999 || season > 2100) {
            throw new IllegalArgumentException("season must be between 1999 and 2100");
        }

        LocalDate ageAsOf = LocalDate.of(season, 9, 1);
        List<TeamAgingModelEvidence> teams = new ArrayList<>();
        for (var team : profileReport.teams()) {
            List<PlayerAgingModelEvidence> players = new ArrayList<>();
            for (var player : team.players()) {
                players.add(toPlayer(player, smootherReport, ageAsOf));
            }
            players.sort(Comparator.comparing(PlayerAgingModelEvidence::position)
                .thenComparing(PlayerAgingModelEvidence::playerName, String.CASE_INSENSITIVE_ORDER)
                .thenComparing(PlayerAgingModelEvidence::playerId));
            teams.add(new TeamAgingModelEvidence(team.teamId(), team.teamName(), List.copyOf(players)));
        }
        teams.sort(Comparator.comparing(TeamAgingModelEvidence::teamName, String.CASE_INSENSITIVE_ORDER)
            .thenComparing(TeamAgingModelEvidence::teamId));

        return new LeagueAgingModelEvidenceReport(
            profileReport.leagueId(), season, ageAsOf, profileReport.providerSource(),
            AgingModelSupportPolicy.POLICY_ID,
            AgingModelSupportPolicy.MINIMUM_DISTINCT_SEASON_TRANSITIONS,
            smootherReport.profileSource(), smootherReport.productionSource(), List.copyOf(teams));
    }

    private static PlayerAgingModelEvidence toPlayer(
        LeaguePlayerProfileCoverageAnalyzer.PlayerEvidence player,
        AgingModelLocalSmootherAnalyzer.LocalSmootherReport smootherReport,
        LocalDate ageAsOf) {
        if (!SUPPORTED_POSITIONS.contains(player.position())) {
            return new PlayerAgingModelEvidence(player.playerId(), player.playerName(), player.position(),
                player.rosterSlot(), null, Status.UNSUPPORTED_POSITION, null);
        }
        if (player.birthDate() == null) {
            return new PlayerAgingModelEvidence(player.playerId(), player.playerName(), player.position(),
                player.rosterSlot(), null, Status.EXACT_AGE_UNAVAILABLE, null);
        }
        if (ageAsOf.isBefore(player.birthDate())) {
            throw new IllegalArgumentException("season age date predates birth date for player: " + player.playerId());
        }
        int modelAge = Period.between(player.birthDate(), ageAsOf).getYears();
        var evidence = AgingModelPositionAgeEvidenceAnalyzer.resolve(smootherReport, player.position(), modelAge);
        Status status = classify(evidence);
        return new PlayerAgingModelEvidence(player.playerId(), player.playerName(), player.position(),
            player.rosterSlot(), modelAge, status, evidence);
    }

    private static Status classify(AgingModelPositionAgeEvidenceAnalyzer.PositionAgeEvidenceReport evidence) {
        if (evidence.publishedMetrics() == evidence.metrics().size()) return Status.FULL;
        if (evidence.publishedMetrics() > 0) return Status.PARTIAL;
        if (evidence.belowSupportMetrics() > 0) return Status.BELOW_SUPPORT;
        return Status.NOT_OBSERVED;
    }

    public enum Status {
        FULL,
        PARTIAL,
        BELOW_SUPPORT,
        NOT_OBSERVED,
        EXACT_AGE_UNAVAILABLE,
        UNSUPPORTED_POSITION
    }

    public record PlayerAgingModelEvidence(String playerId,
                                           String playerName,
                                           String position,
                                           String rosterSlot,
                                           Integer modelAge,
                                           Status status,
                                           AgingModelPositionAgeEvidenceAnalyzer.PositionAgeEvidenceReport evidence) {
        public PlayerAgingModelEvidence {
            Objects.requireNonNull(playerId, "playerId must not be null");
            Objects.requireNonNull(playerName, "playerName must not be null");
            Objects.requireNonNull(position, "position must not be null");
            Objects.requireNonNull(status, "status must not be null");
            boolean hasEvidence = evidence != null;
            boolean modelEligibleStatus = status == Status.FULL || status == Status.PARTIAL
                || status == Status.BELOW_SUPPORT || status == Status.NOT_OBSERVED;
            if (modelEligibleStatus != hasEvidence) {
                throw new IllegalArgumentException("model evidence presence must match model-eligible status");
            }
            if (modelEligibleStatus && modelAge == null) {
                throw new IllegalArgumentException("model-eligible player requires model age");
            }
            if (!modelEligibleStatus && modelAge != null) {
                throw new IllegalArgumentException("unavailable/unsupported player must not expose model age");
            }
        }

        public boolean modelEvidenceAvailable() { return status == Status.FULL || status == Status.PARTIAL; }
    }

    public record TeamAgingModelEvidence(String teamId, String teamName,
                                         List<PlayerAgingModelEvidence> players) {
        public TeamAgingModelEvidence {
            Objects.requireNonNull(teamId, "teamId must not be null");
            Objects.requireNonNull(teamName, "teamName must not be null");
            players = List.copyOf(Objects.requireNonNull(players, "players must not be null"));
        }
        public int fullPlayers() { return count(Status.FULL); }
        public int partialPlayers() { return count(Status.PARTIAL); }
        public int belowSupportPlayers() { return count(Status.BELOW_SUPPORT); }
        public int notObservedPlayers() { return count(Status.NOT_OBSERVED); }
        public int exactAgeUnavailablePlayers() { return count(Status.EXACT_AGE_UNAVAILABLE); }
        public int unsupportedPositionPlayers() { return count(Status.UNSUPPORTED_POSITION); }

        private int count(Status status) {
            return (int) players.stream().filter(player -> player.status() == status).count();
        }
    }

    public record LeagueAgingModelEvidenceReport(String leagueId,
                                                 int season,
                                                 LocalDate modelAgeAsOf,
                                                 String leagueProfileSource,
                                                 String supportPolicyId,
                                                 int minimumDistinctSeasonTransitions,
                                                 String modelProfileSource,
                                                 String modelProductionSource,
                                                 List<TeamAgingModelEvidence> teams) {
        public LeagueAgingModelEvidenceReport {
            Objects.requireNonNull(leagueId, "leagueId must not be null");
            Objects.requireNonNull(modelAgeAsOf, "modelAgeAsOf must not be null");
            Objects.requireNonNull(leagueProfileSource, "leagueProfileSource must not be null");
            Objects.requireNonNull(supportPolicyId, "supportPolicyId must not be null");
            Objects.requireNonNull(modelProfileSource, "modelProfileSource must not be null");
            Objects.requireNonNull(modelProductionSource, "modelProductionSource must not be null");
            teams = List.copyOf(Objects.requireNonNull(teams, "teams must not be null"));
        }
        public int totalPlayers() { return teams.stream().mapToInt(team -> team.players().size()).sum(); }
        public int fullPlayers() { return teams.stream().mapToInt(TeamAgingModelEvidence::fullPlayers).sum(); }
        public int partialPlayers() { return teams.stream().mapToInt(TeamAgingModelEvidence::partialPlayers).sum(); }
        public int belowSupportPlayers() { return teams.stream().mapToInt(TeamAgingModelEvidence::belowSupportPlayers).sum(); }
        public int notObservedPlayers() { return teams.stream().mapToInt(TeamAgingModelEvidence::notObservedPlayers).sum(); }
        public int exactAgeUnavailablePlayers() { return teams.stream().mapToInt(TeamAgingModelEvidence::exactAgeUnavailablePlayers).sum(); }
        public int unsupportedPositionPlayers() { return teams.stream().mapToInt(TeamAgingModelEvidence::unsupportedPositionPlayers).sum(); }
    }
}
