package io.butler.bet.intelligence;

import io.butler.bet.data.Database;
import io.butler.bet.data.PlayerProfileRepository;
import io.butler.bet.data.PlayerProfileSnapshotRepository;
import io.butler.bet.data.PlayerRepository;
import io.butler.bet.data.RosterRepository;

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

/** Reports age/profile evidence availability without attaching age-based strategy or quality labels. */
public final class LeaguePlayerProfileCoverageAnalyzer {
    public static final String DEFAULT_PROVIDER_SOURCE = "sleeper";

    private final LeagueAnalyzer leagues;
    private final RosterRepository rosters;
    private final PlayerRepository players;
    private final PlayerProfileRepository profiles;
    private final PlayerProfileSnapshotRepository snapshots;

    public LeaguePlayerProfileCoverageAnalyzer(Database database) {
        Objects.requireNonNull(database, "database must not be null");
        this.leagues = new LeagueAnalyzer(database);
        this.rosters = new RosterRepository(database);
        this.players = new PlayerRepository(database);
        this.profiles = new PlayerProfileRepository(database);
        this.snapshots = new PlayerProfileSnapshotRepository(database);
    }

    public CoverageReport analyze(String leagueId) throws SQLException {
        return analyze(leagueId, DEFAULT_PROVIDER_SOURCE, null);
    }

    public CoverageReport analyze(String leagueId, String providerSource) throws SQLException {
        return analyze(leagueId, providerSource, null);
    }

    public CoverageReport analyze(String leagueId, String providerSource, LocalDate minimumSnapshotAsOf) throws SQLException {
        String normalizedLeagueId = requireText(leagueId, "leagueId");
        String normalizedSource = requireText(providerSource, "providerSource");
        var league = leagues.analyze(normalizedLeagueId);
        List<TeamCoverage> teams = new ArrayList<>();

        for (var team : league.teams()) {
            MutableCounts teamCounts = new MutableCounts();
            Map<String, MutableCounts> positions = new TreeMap<>();
            List<PlayerEvidence> playerEvidence = new ArrayList<>();

            for (var roster : rosters.findByTeamId(team.teamId())) {
                var player = players.findById(roster.getPlayerId())
                    .orElseThrow(() -> new IllegalArgumentException("player not found: " + roster.getPlayerId()));
                String position = normalizePosition(player.getPosition());
                MutableCounts positionCounts = positions.computeIfAbsent(position, ignored -> new MutableCounts());
                teamCounts.totalPlayers++;
                positionCounts.totalPlayers++;

                var canonical = profiles.findByPlayerId(player.getId()).orElse(null);
                var provider = snapshots.findLatest(player.getId(), normalizedSource).orElse(null);
                boolean providerFresh = provider != null
                    && (minimumSnapshotAsOf == null || !provider.asOfDate().isBefore(minimumSnapshotAsOf));
                boolean exactBirthDate = canonical != null && canonical.birthDate() != null;
                boolean reportedAge = !exactBirthDate && providerFresh && provider.reportedAge() != null;
                boolean ageEvidence = exactBirthDate || reportedAge;
                boolean canonicalExperience = canonical != null && canonical.yearsExperience() != null;
                boolean reportedExperience = providerFresh && provider.yearsExperience() != null;
                boolean experienceEvidence = canonicalExperience || reportedExperience;

                if (exactBirthDate) {
                    teamCounts.exactBirthDatePlayers++;
                    positionCounts.exactBirthDatePlayers++;
                } else if (reportedAge) {
                    teamCounts.reportedAgePlayers++;
                    positionCounts.reportedAgePlayers++;
                }
                if (ageEvidence) {
                    teamCounts.ageEvidencePlayers++;
                    positionCounts.ageEvidencePlayers++;
                }
                if (experienceEvidence) {
                    teamCounts.experienceEvidencePlayers++;
                    positionCounts.experienceEvidencePlayers++;
                }
                if (!ageEvidence && experienceEvidence) {
                    teamCounts.experienceOnlyPlayers++;
                    positionCounts.experienceOnlyPlayers++;
                }
                if (!ageEvidence && !experienceEvidence) {
                    teamCounts.noProfileEvidencePlayers++;
                    positionCounts.noProfileEvidencePlayers++;
                }
                if (provider != null && !providerFresh) {
                    teamCounts.staleProviderSnapshotPlayers++;
                    positionCounts.staleProviderSnapshotPlayers++;
                }

                playerEvidence.add(new PlayerEvidence(
                    player.getId(), player.getDisplayName(), position, roster.getSlot(),
                    exactBirthDate ? canonical.birthDate() : null,
                    reportedAge ? provider.reportedAge() : null,
                    canonicalExperience ? canonical.yearsExperience()
                        : reportedExperience ? provider.yearsExperience() : null,
                    exactBirthDate ? "canonical-birth-date" : reportedAge ? normalizedSource : null,
                    provider == null ? null : provider.asOfDate(),
                    provider != null && !providerFresh));
            }

            Map<String, PositionCoverage> frozenPositions = new LinkedHashMap<>();
            positions.forEach((position, counts) -> frozenPositions.put(position, freezePosition(position, counts)));
            playerEvidence.sort(Comparator.comparing(PlayerEvidence::playerName, String.CASE_INSENSITIVE_ORDER)
                .thenComparing(PlayerEvidence::playerId));
            teams.add(new TeamCoverage(team.teamId(), team.teamName(), freezeCounts(teamCounts),
                Map.copyOf(frozenPositions), List.copyOf(playerEvidence)));
        }

        teams.sort(Comparator.comparing(TeamCoverage::teamName, String.CASE_INSENSITIVE_ORDER)
            .thenComparing(TeamCoverage::teamId));
        return new CoverageReport(normalizedLeagueId, normalizedSource, minimumSnapshotAsOf, List.copyOf(teams));
    }

    private static PositionCoverage freezePosition(String position, MutableCounts c) {
        return new PositionCoverage(position, c.totalPlayers, c.ageEvidencePlayers, c.exactBirthDatePlayers,
            c.reportedAgePlayers, c.experienceEvidencePlayers, c.experienceOnlyPlayers,
            c.noProfileEvidencePlayers, c.staleProviderSnapshotPlayers);
    }

    private static Counts freezeCounts(MutableCounts c) {
        return new Counts(c.totalPlayers, c.ageEvidencePlayers, c.exactBirthDatePlayers,
            c.reportedAgePlayers, c.experienceEvidencePlayers, c.experienceOnlyPlayers,
            c.noProfileEvidencePlayers, c.staleProviderSnapshotPlayers);
    }

    private static String normalizePosition(String position) {
        if (position == null || position.isBlank()) return "UNKNOWN";
        return position.trim().toUpperCase(Locale.ROOT);
    }

    private static String requireText(String value, String field) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(field + " must not be blank");
        return value.trim();
    }

    private static final class MutableCounts {
        int totalPlayers;
        int ageEvidencePlayers;
        int exactBirthDatePlayers;
        int reportedAgePlayers;
        int experienceEvidencePlayers;
        int experienceOnlyPlayers;
        int noProfileEvidencePlayers;
        int staleProviderSnapshotPlayers;
    }

    public record CoverageReport(String leagueId, String providerSource, LocalDate minimumSnapshotAsOf,
                                 List<TeamCoverage> teams) {
        public CoverageReport { teams = List.copyOf(Objects.requireNonNull(teams)); }
        public int totalPlayers() { return teams.stream().mapToInt(t -> t.counts().totalPlayers()).sum(); }
        public int ageEvidencePlayers() { return teams.stream().mapToInt(t -> t.counts().ageEvidencePlayers()).sum(); }
        public int exactBirthDatePlayers() { return teams.stream().mapToInt(t -> t.counts().exactBirthDatePlayers()).sum(); }
        public int reportedAgePlayers() { return teams.stream().mapToInt(t -> t.counts().reportedAgePlayers()).sum(); }
        public int experienceEvidencePlayers() { return teams.stream().mapToInt(t -> t.counts().experienceEvidencePlayers()).sum(); }
        public int noProfileEvidencePlayers() { return teams.stream().mapToInt(t -> t.counts().noProfileEvidencePlayers()).sum(); }
        public int staleProviderSnapshotPlayers() { return teams.stream().mapToInt(t -> t.counts().staleProviderSnapshotPlayers()).sum(); }
        public double ageCoveragePercent() { return percent(ageEvidencePlayers(), totalPlayers()); }
        public double exactBirthDateCoveragePercent() { return percent(exactBirthDatePlayers(), totalPlayers()); }
        public boolean ageComplete() { return totalPlayers() > 0 && ageEvidencePlayers() == totalPlayers(); }
    }

    public record Counts(int totalPlayers, int ageEvidencePlayers, int exactBirthDatePlayers,
                         int reportedAgePlayers, int experienceEvidencePlayers, int experienceOnlyPlayers,
                         int noProfileEvidencePlayers, int staleProviderSnapshotPlayers) {
        public double ageCoveragePercent() { return percent(ageEvidencePlayers, totalPlayers); }
        public double exactBirthDateCoveragePercent() { return percent(exactBirthDatePlayers, totalPlayers); }
    }

    public record TeamCoverage(String teamId, String teamName, Counts counts,
                               Map<String, PositionCoverage> positions, List<PlayerEvidence> players) {
        public TeamCoverage {
            Objects.requireNonNull(counts);
            positions = Map.copyOf(Objects.requireNonNull(positions));
            players = List.copyOf(Objects.requireNonNull(players));
        }
    }

    public record PositionCoverage(String position, int totalPlayers, int ageEvidencePlayers,
                                   int exactBirthDatePlayers, int reportedAgePlayers,
                                   int experienceEvidencePlayers, int experienceOnlyPlayers,
                                   int noProfileEvidencePlayers, int staleProviderSnapshotPlayers) {
        public double ageCoveragePercent() { return percent(ageEvidencePlayers, totalPlayers); }
    }

    public record PlayerEvidence(String playerId, String playerName, String position, String rosterSlot,
                                 LocalDate birthDate, Integer reportedAge, Integer yearsExperience,
                                 String ageEvidenceSource, LocalDate providerSnapshotAsOf,
                                 boolean providerSnapshotStale) {
        public boolean hasAgeEvidence() { return birthDate != null || reportedAge != null; }
        public boolean hasExperienceEvidence() { return yearsExperience != null; }
    }

    private static double percent(int numerator, int denominator) {
        return denominator == 0 ? 0.0 : numerator * 100.0 / denominator;
    }
}
