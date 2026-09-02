package io.butler.bet.intelligence;

import io.butler.bet.data.Database;

import java.sql.SQLException;
import java.time.LocalDate;
import java.time.Period;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;

/**
 * Neutral age context built from existing profile evidence. Exact birth dates are derived on the
 * requested analysis date; provider-reported ages are preserved as reported and never extrapolated.
 */
public final class LeagueAgeContextAnalyzer {
    private final LeaguePlayerProfileCoverageAnalyzer profiles;

    public LeagueAgeContextAnalyzer(Database database) {
        Objects.requireNonNull(database, "database must not be null");
        this.profiles = new LeaguePlayerProfileCoverageAnalyzer(database);
    }

    public AgeContextReport analyze(String leagueId) throws SQLException {
        return analyze(leagueId, LocalDate.now(ZoneOffset.UTC),
            LeaguePlayerProfileCoverageAnalyzer.DEFAULT_PROVIDER_SOURCE, null);
    }

    public AgeContextReport analyze(String leagueId, LocalDate ageAsOf) throws SQLException {
        return analyze(leagueId, ageAsOf,
            LeaguePlayerProfileCoverageAnalyzer.DEFAULT_PROVIDER_SOURCE, null);
    }

    public AgeContextReport analyze(String leagueId, LocalDate ageAsOf,
                                    String providerSource, LocalDate minimumProviderAsOf) throws SQLException {
        Objects.requireNonNull(ageAsOf, "ageAsOf must not be null");
        var coverage = profiles.analyze(leagueId, providerSource, minimumProviderAsOf);
        List<TeamAgeContext> teams = new ArrayList<>();

        for (var team : coverage.teams()) {
            MutableAge teamAge = new MutableAge();
            Map<String, MutableAge> positionAges = new TreeMap<>();
            List<PlayerAgeContext> players = new ArrayList<>();

            for (var evidence : team.players()) {
                Integer age = null;
                AgeProvenance provenance = AgeProvenance.UNAVAILABLE;
                if (evidence.birthDate() != null) {
                    if (ageAsOf.isBefore(evidence.birthDate())) {
                        throw new IllegalArgumentException("age analysis date predates birth date for player: " + evidence.playerId());
                    }
                    age = Period.between(evidence.birthDate(), ageAsOf).getYears();
                    provenance = AgeProvenance.EXACT_BIRTH_DATE;
                } else if (evidence.reportedAge() != null) {
                    age = evidence.reportedAge();
                    provenance = AgeProvenance.PROVIDER_REPORTED;
                }

                MutableAge position = positionAges.computeIfAbsent(evidence.position(), ignored -> new MutableAge());
                teamAge.totalPlayers++;
                position.totalPlayers++;
                if (age != null) {
                    teamAge.add(age, provenance);
                    position.add(age, provenance);
                }

                players.add(new PlayerAgeContext(evidence.playerId(), evidence.playerName(), evidence.position(),
                    evidence.rosterSlot(), age, provenance, evidence.birthDate(), evidence.providerSnapshotAsOf(),
                    evidence.providerSnapshotStale()));
            }

            Map<String, PositionAgeContext> positions = new LinkedHashMap<>();
            positionAges.forEach((position, values) -> positions.put(position, values.freezePosition(position)));
            players.sort(Comparator.comparing(PlayerAgeContext::playerName, String.CASE_INSENSITIVE_ORDER)
                .thenComparing(PlayerAgeContext::playerId));
            teams.add(new TeamAgeContext(team.teamId(), team.teamName(), teamAge.totalPlayers, teamAge.coveredPlayers,
                teamAge.exactPlayers, teamAge.reportedPlayers, teamAge.average(), teamAge.minimumAge(), teamAge.maximumAge(),
                Map.copyOf(positions), List.copyOf(players)));
        }

        teams.sort(Comparator.comparing(TeamAgeContext::teamName, String.CASE_INSENSITIVE_ORDER)
            .thenComparing(TeamAgeContext::teamId));
        return new AgeContextReport(coverage.leagueId(), ageAsOf, coverage.providerSource(),
            coverage.minimumSnapshotAsOf(), List.copyOf(teams));
    }

    public enum AgeProvenance { EXACT_BIRTH_DATE, PROVIDER_REPORTED, UNAVAILABLE }

    public record AgeContextReport(String leagueId, LocalDate ageAsOf, String providerSource,
                                   LocalDate minimumProviderAsOf, List<TeamAgeContext> teams) {
        public AgeContextReport { teams = List.copyOf(Objects.requireNonNull(teams)); }
        public int totalPlayers() { return teams.stream().mapToInt(TeamAgeContext::totalPlayers).sum(); }
        public int coveredPlayers() { return teams.stream().mapToInt(TeamAgeContext::coveredPlayers).sum(); }
        public int exactBirthDatePlayers() { return teams.stream().mapToInt(TeamAgeContext::exactBirthDatePlayers).sum(); }
        public int providerReportedPlayers() { return teams.stream().mapToInt(TeamAgeContext::providerReportedPlayers).sum(); }
        public double coveragePercent() { return percent(coveredPlayers(), totalPlayers()); }
    }

    public record TeamAgeContext(String teamId, String teamName, int totalPlayers, int coveredPlayers,
                                 int exactBirthDatePlayers, int providerReportedPlayers,
                                 Double averageAge, Integer minimumAge, Integer maximumAge,
                                 Map<String, PositionAgeContext> positions,
                                 List<PlayerAgeContext> players) {
        public TeamAgeContext {
            positions = Map.copyOf(Objects.requireNonNull(positions));
            players = List.copyOf(Objects.requireNonNull(players));
        }
        public double coveragePercent() { return percent(coveredPlayers, totalPlayers); }
    }

    public record PositionAgeContext(String position, int totalPlayers, int coveredPlayers,
                                     int exactBirthDatePlayers, int providerReportedPlayers,
                                     Double averageAge, Integer minimumAge, Integer maximumAge) {
        public double coveragePercent() { return percent(coveredPlayers, totalPlayers); }
    }

    public record PlayerAgeContext(String playerId, String playerName, String position, String rosterSlot,
                                   Integer age, AgeProvenance provenance, LocalDate birthDate,
                                   LocalDate providerSnapshotAsOf, boolean providerSnapshotStale) {
        public boolean ageAvailable() { return age != null; }
    }

    private static final class MutableAge {
        int totalPlayers;
        int coveredPlayers;
        int exactPlayers;
        int reportedPlayers;
        int ageSum;
        Integer min;
        Integer max;

        void add(int age, AgeProvenance provenance) {
            coveredPlayers++;
            ageSum += age;
            min = min == null ? age : Math.min(min, age);
            max = max == null ? age : Math.max(max, age);
            if (provenance == AgeProvenance.EXACT_BIRTH_DATE) exactPlayers++;
            if (provenance == AgeProvenance.PROVIDER_REPORTED) reportedPlayers++;
        }

        Double average() { return coveredPlayers == 0 ? null : ageSum / (double) coveredPlayers; }
        Integer minimumAge() { return min; }
        Integer maximumAge() { return max; }

        PositionAgeContext freezePosition(String position) {
            return new PositionAgeContext(position, totalPlayers, coveredPlayers, exactPlayers, reportedPlayers,
                average(), minimumAge(), maximumAge());
        }
    }

    private static double percent(int numerator, int denominator) {
        return denominator == 0 ? 0.0 : numerator * 100.0 / denominator;
    }
}
