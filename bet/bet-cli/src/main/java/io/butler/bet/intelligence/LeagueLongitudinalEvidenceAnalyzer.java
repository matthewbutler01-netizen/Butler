package io.butler.bet.intelligence;

import io.butler.bet.data.Database;
import io.butler.bet.data.PlayerProfileRepository;
import io.butler.bet.data.PlayerRepository;
import io.butler.bet.data.PlayerSeasonProductionRepository;
import io.butler.bet.data.RosterRepository;
import io.butler.bet.data.TeamRepository;
import io.butler.bet.domain.PlayerSeasonProduction;

import java.sql.SQLException;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;

/**
 * Measures the exact-age and multi-season production evidence available for later longitudinal
 * analysis. This class deliberately does not decide how many observations are "enough" and does
 * not fit an aging curve, grade a player, or infer a career phase.
 */
public final class LeagueLongitudinalEvidenceAnalyzer {
    public static final String DEFAULT_PRODUCTION_SOURCE = NflversePlayerSeasonProductionImporter.SOURCE;

    private final TeamRepository teams;
    private final RosterRepository rosters;
    private final PlayerRepository players;
    private final PlayerProfileRepository profiles;
    private final PlayerSeasonProductionRepository production;

    public LeagueLongitudinalEvidenceAnalyzer(Database database) {
        Objects.requireNonNull(database, "database must not be null");
        this.teams = new TeamRepository(database);
        this.rosters = new RosterRepository(database);
        this.players = new PlayerRepository(database);
        this.profiles = new PlayerProfileRepository(database);
        this.production = new PlayerSeasonProductionRepository(database);
    }

    public LongitudinalEvidenceReport analyze(String leagueId) throws SQLException {
        return analyze(leagueId, DEFAULT_PRODUCTION_SOURCE);
    }

    public LongitudinalEvidenceReport analyze(String leagueId, String productionSource) throws SQLException {
        requireText(leagueId, "leagueId");
        requireText(productionSource, "productionSource");

        List<TeamLongitudinalEvidence> teamReports = new ArrayList<>();
        for (var team : teams.findByLeagueId(leagueId)) {
            List<PlayerLongitudinalEvidence> playerReports = new ArrayList<>();
            Map<String, MutablePosition> positions = new TreeMap<>();

            for (var roster : rosters.findByTeamId(team.getId())) {
                var player = players.findById(roster.getPlayerId())
                    .orElseThrow(() -> new IllegalStateException(
                        "roster references missing player: " + roster.getPlayerId()));
                String position = normalizePosition(player.getPosition());
                LocalDate birthDate = profiles.findByPlayerId(player.getId())
                    .map(profile -> profile.birthDate()).orElse(null);

                Map<Integer, PlayerSeasonProduction> latestBySeason = latestBySeason(
                    production.findByPlayerId(player.getId()), productionSource);
                List<Integer> productionSeasons = latestBySeason.keySet().stream().sorted().toList();
                List<Integer> rateEligibleSeasons = latestBySeason.values().stream()
                    .filter(value -> value.gamesPlayed() > 0)
                    .map(PlayerSeasonProduction::season)
                    .sorted().toList();
                int consecutiveRatePairs = consecutivePairs(rateEligibleSeasons);
                int exactAgeConsecutiveRatePairs = birthDate == null ? 0 : consecutiveRatePairs;

                PlayerLongitudinalEvidence report = new PlayerLongitudinalEvidence(
                    player.getId(), player.getDisplayName(), position, birthDate,
                    productionSeasons, rateEligibleSeasons, consecutiveRatePairs,
                    exactAgeConsecutiveRatePairs,
                    productionSeasons.isEmpty() ? null : productionSeasons.getFirst(),
                    productionSeasons.isEmpty() ? null : productionSeasons.getLast());
                playerReports.add(report);
                positions.computeIfAbsent(position, MutablePosition::new).add(report);
            }

            playerReports.sort(Comparator.comparing(PlayerLongitudinalEvidence::position)
                .thenComparing(PlayerLongitudinalEvidence::playerName, String.CASE_INSENSITIVE_ORDER)
                .thenComparing(PlayerLongitudinalEvidence::playerId));
            Map<String, PositionLongitudinalEvidence> positionReports = new LinkedHashMap<>();
            positions.forEach((position, aggregate) -> positionReports.put(position, aggregate.freeze()));
            teamReports.add(new TeamLongitudinalEvidence(team.getId(), team.getName(),
                List.copyOf(playerReports), Map.copyOf(positionReports)));
        }

        teamReports.sort(Comparator.comparing(TeamLongitudinalEvidence::teamName, String.CASE_INSENSITIVE_ORDER)
            .thenComparing(TeamLongitudinalEvidence::teamId));
        return new LongitudinalEvidenceReport(leagueId, productionSource, List.copyOf(teamReports));
    }

    private static Map<Integer, PlayerSeasonProduction> latestBySeason(List<PlayerSeasonProduction> values,
                                                                        String source) {
        Map<Integer, PlayerSeasonProduction> result = new TreeMap<>();
        for (PlayerSeasonProduction value : values) {
            if (!value.source().equalsIgnoreCase(source)) continue;
            PlayerSeasonProduction existing = result.get(value.season());
            if (existing == null || value.asOfDate().isAfter(existing.asOfDate())) {
                result.put(value.season(), value);
            }
        }
        return result;
    }

    private static int consecutivePairs(List<Integer> seasons) {
        int pairs = 0;
        for (int i = 1; i < seasons.size(); i++) {
            if (seasons.get(i) == seasons.get(i - 1) + 1) pairs++;
        }
        return pairs;
    }

    private static String normalizePosition(String position) {
        return position == null || position.isBlank() ? "UNKNOWN" : position.trim().toUpperCase(Locale.ROOT);
    }

    private static void requireText(String value, String field) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(field + " must not be blank");
    }

    public record LongitudinalEvidenceReport(String leagueId, String productionSource,
                                             List<TeamLongitudinalEvidence> teams) {
        public LongitudinalEvidenceReport {
            Objects.requireNonNull(leagueId, "leagueId must not be null");
            Objects.requireNonNull(productionSource, "productionSource must not be null");
            teams = List.copyOf(Objects.requireNonNull(teams, "teams must not be null"));
        }
        public int totalPlayers() { return teams.stream().mapToInt(TeamLongitudinalEvidence::totalPlayers).sum(); }
        public int exactBirthDatePlayers() { return teams.stream().mapToInt(TeamLongitudinalEvidence::exactBirthDatePlayers).sum(); }
        public int productionPlayerSeasons() { return teams.stream().mapToInt(TeamLongitudinalEvidence::productionPlayerSeasons).sum(); }
        public int rateEligiblePlayerSeasons() { return teams.stream().mapToInt(TeamLongitudinalEvidence::rateEligiblePlayerSeasons).sum(); }
        public int consecutiveRatePairs() { return teams.stream().mapToInt(TeamLongitudinalEvidence::consecutiveRatePairs).sum(); }
        public int exactAgeConsecutiveRatePairs() { return teams.stream().mapToInt(TeamLongitudinalEvidence::exactAgeConsecutiveRatePairs).sum(); }
        public int playersWithExactAgeConsecutiveRatePair() {
            return teams.stream().mapToInt(TeamLongitudinalEvidence::playersWithExactAgeConsecutiveRatePair).sum();
        }
    }

    public record TeamLongitudinalEvidence(String teamId, String teamName,
                                           List<PlayerLongitudinalEvidence> players,
                                           Map<String, PositionLongitudinalEvidence> positions) {
        public TeamLongitudinalEvidence {
            Objects.requireNonNull(teamId, "teamId must not be null");
            Objects.requireNonNull(teamName, "teamName must not be null");
            players = List.copyOf(Objects.requireNonNull(players, "players must not be null"));
            positions = Map.copyOf(Objects.requireNonNull(positions, "positions must not be null"));
        }
        public int totalPlayers() { return players.size(); }
        public int exactBirthDatePlayers() { return (int) players.stream().filter(PlayerLongitudinalEvidence::exactBirthDateAvailable).count(); }
        public int productionPlayerSeasons() { return players.stream().mapToInt(value -> value.productionSeasons().size()).sum(); }
        public int rateEligiblePlayerSeasons() { return players.stream().mapToInt(value -> value.rateEligibleSeasons().size()).sum(); }
        public int consecutiveRatePairs() { return players.stream().mapToInt(PlayerLongitudinalEvidence::consecutiveRatePairs).sum(); }
        public int exactAgeConsecutiveRatePairs() { return players.stream().mapToInt(PlayerLongitudinalEvidence::exactAgeConsecutiveRatePairs).sum(); }
        public int playersWithExactAgeConsecutiveRatePair() {
            return (int) players.stream().filter(PlayerLongitudinalEvidence::exactAgePairAvailable).count();
        }
    }

    public record PositionLongitudinalEvidence(String position, int totalPlayers, int exactBirthDatePlayers,
                                               int productionPlayerSeasons, int rateEligiblePlayerSeasons,
                                               int consecutiveRatePairs, int exactAgeConsecutiveRatePairs,
                                               int playersWithExactAgeConsecutiveRatePair) {}

    public record PlayerLongitudinalEvidence(String playerId, String playerName, String position,
                                             LocalDate birthDate, List<Integer> productionSeasons,
                                             List<Integer> rateEligibleSeasons, int consecutiveRatePairs,
                                             int exactAgeConsecutiveRatePairs,
                                             Integer earliestProductionSeason, Integer latestProductionSeason) {
        public PlayerLongitudinalEvidence {
            Objects.requireNonNull(playerId, "playerId must not be null");
            Objects.requireNonNull(playerName, "playerName must not be null");
            Objects.requireNonNull(position, "position must not be null");
            productionSeasons = List.copyOf(Objects.requireNonNull(productionSeasons));
            rateEligibleSeasons = List.copyOf(Objects.requireNonNull(rateEligibleSeasons));
        }
        public boolean exactBirthDateAvailable() { return birthDate != null; }
        public boolean exactAgePairAvailable() { return exactAgeConsecutiveRatePairs > 0; }
    }

    private static final class MutablePosition {
        private final String position;
        private int totalPlayers;
        private int exactBirthDatePlayers;
        private int productionPlayerSeasons;
        private int rateEligiblePlayerSeasons;
        private int consecutiveRatePairs;
        private int exactAgeConsecutiveRatePairs;
        private int playersWithExactAgeConsecutiveRatePair;

        private MutablePosition(String position) { this.position = position; }

        private void add(PlayerLongitudinalEvidence player) {
            totalPlayers++;
            if (player.exactBirthDateAvailable()) exactBirthDatePlayers++;
            productionPlayerSeasons += player.productionSeasons().size();
            rateEligiblePlayerSeasons += player.rateEligibleSeasons().size();
            consecutiveRatePairs += player.consecutiveRatePairs();
            exactAgeConsecutiveRatePairs += player.exactAgeConsecutiveRatePairs();
            if (player.exactAgePairAvailable()) playersWithExactAgeConsecutiveRatePair++;
        }

        private PositionLongitudinalEvidence freeze() {
            return new PositionLongitudinalEvidence(position, totalPlayers, exactBirthDatePlayers,
                productionPlayerSeasons, rateEligiblePlayerSeasons, consecutiveRatePairs,
                exactAgeConsecutiveRatePairs, playersWithExactAgeConsecutiveRatePair);
        }
    }
}
