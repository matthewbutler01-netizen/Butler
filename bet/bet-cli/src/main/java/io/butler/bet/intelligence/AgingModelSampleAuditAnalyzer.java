package io.butler.bet.intelligence;

import io.butler.bet.data.AgingModelPlayerProfileRepository;
import io.butler.bet.data.AgingModelPlayerSeasonProductionRepository;
import io.butler.bet.data.Database;
import io.butler.bet.domain.AgingModelPlayerProfile;
import io.butler.bet.domain.AgingModelPlayerSeasonProduction;

import java.sql.SQLException;
import java.time.LocalDate;
import java.time.Period;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** Builds a deterministic, model-free audit of exact-age year-over-year raw production-rate observations. */
public final class AgingModelSampleAuditAnalyzer {
    public static final String PROFILE_SOURCE = NflverseAgingModelPlayerImporter.SOURCE;
    public static final String PRODUCTION_SOURCE = NflverseAgingModelProductionImporter.SOURCE;

    private final AgingModelPlayerProfileRepository profiles;
    private final AgingModelPlayerSeasonProductionRepository production;

    public AgingModelSampleAuditAnalyzer(Database database) {
        Objects.requireNonNull(database, "database must not be null");
        this.profiles = new AgingModelPlayerProfileRepository(database);
        this.production = new AgingModelPlayerSeasonProductionRepository(database);
    }

    public SampleAuditReport analyze() throws SQLException {
        Map<String, AgingModelPlayerProfile> profileByGsis = new HashMap<>();
        for (var profile : profiles.findLatestBySource(PROFILE_SOURCE)) profileByGsis.put(profile.gsisId(), profile);

        List<AgingModelPlayerSeasonProduction> seasons = production.findLatestBySource(PRODUCTION_SOURCE);
        Map<String, List<AgingModelPlayerSeasonProduction>> byPlayer = new LinkedHashMap<>();
        for (var value : seasons) byPlayer.computeIfAbsent(value.gsisId(), ignored -> new ArrayList<>()).add(value);
        byPlayer.values().forEach(list -> list.sort(Comparator.comparingInt(AgingModelPlayerSeasonProduction::season)));

        int profilePlayers = profileByGsis.size();
        int exactBirthDatePlayers = (int) profileByGsis.values().stream().filter(p -> p.birthDate() != null).count();
        int productionPlayerSeasons = seasons.size();
        int zeroGamePlayerSeasons = (int) seasons.stream().filter(s -> s.gamesPlayed() == 0).count();
        int productionPlayersWithoutProfile = (int) byPlayer.keySet().stream().filter(id -> !profileByGsis.containsKey(id)).count();
        int profilePlayersWithoutProduction = (int) profileByGsis.keySet().stream().filter(id -> !byPlayer.containsKey(id)).count();

        int consecutivePairs = 0;
        int exactDobRatePairs = 0;
        int zeroGameExcludedPairs = 0;
        int missingBirthDatePairs = 0;
        int positionChangeExcludedPairs = 0;
        int unsupportedPositionPairs = 0;
        List<AgingObservation> observations = new ArrayList<>();

        for (var entry : byPlayer.entrySet()) {
            String gsis = entry.getKey();
            AgingModelPlayerProfile profile = profileByGsis.get(gsis);
            List<AgingModelPlayerSeasonProduction> playerSeasons = entry.getValue();
            for (int i = 0; i + 1 < playerSeasons.size(); i++) {
                var start = playerSeasons.get(i);
                var end = playerSeasons.get(i + 1);
                if (end.season() != start.season() + 1) continue;
                consecutivePairs++;
                if (start.gamesPlayed() <= 0 || end.gamesPlayed() <= 0) {
                    zeroGameExcludedPairs++;
                    continue;
                }
                if (profile == null || profile.birthDate() == null) {
                    missingBirthDatePairs++;
                    continue;
                }
                String startPosition = normalizePosition(start.position());
                String endPosition = normalizePosition(end.position());
                if (!startPosition.equals(endPosition)) {
                    positionChangeExcludedPairs++;
                    continue;
                }
                Set<Metric> metrics = metricsFor(startPosition);
                if (metrics.isEmpty()) {
                    unsupportedPositionPairs++;
                    continue;
                }
                int age = Period.between(profile.birthDate(), LocalDate.of(start.season(), 9, 1)).getYears();
                if (age < 0) continue;
                exactDobRatePairs++;
                for (Metric metric : metrics) {
                    double startRate = metric.rate(start);
                    double endRate = metric.rate(end);
                    observations.add(new AgingObservation(gsis, profile.displayName(), startPosition, metric,
                        age, start.season(), end.season(), startRate, endRate, endRate - startRate));
                }
            }
        }

        observations.sort(Comparator.comparing(AgingObservation::position)
            .thenComparing(o -> o.metric().name())
            .thenComparingInt(AgingObservation::age)
            .thenComparingInt(AgingObservation::startSeason)
            .thenComparing(AgingObservation::gsisId));

        Map<GroupKey, List<AgingObservation>> grouped = new LinkedHashMap<>();
        for (var observation : observations) {
            grouped.computeIfAbsent(new GroupKey(observation.position(), observation.metric(), observation.age()),
                ignored -> new ArrayList<>()).add(observation);
        }
        List<SampleCell> cells = grouped.entrySet().stream()
            .map(entry -> summarize(entry.getKey(), entry.getValue()))
            .sorted(Comparator.comparing(SampleCell::position)
                .thenComparing(cell -> cell.metric().name())
                .thenComparingInt(SampleCell::age))
            .toList();

        return new SampleAuditReport(PROFILE_SOURCE, PRODUCTION_SOURCE,
            profilePlayers, exactBirthDatePlayers, productionPlayerSeasons, zeroGamePlayerSeasons,
            productionPlayersWithoutProfile, profilePlayersWithoutProduction, consecutivePairs, exactDobRatePairs,
            zeroGameExcludedPairs, missingBirthDatePairs, positionChangeExcludedPairs, unsupportedPositionPairs,
            List.copyOf(observations), List.copyOf(cells));
    }

    private static SampleCell summarize(GroupKey key, List<AgingObservation> values) {
        Set<String> players = new HashSet<>();
        Set<String> transitions = new HashSet<>();
        int minSeason = Integer.MAX_VALUE;
        int maxSeason = Integer.MIN_VALUE;
        List<Double> starts = new ArrayList<>();
        List<Double> deltas = new ArrayList<>();
        for (var value : values) {
            players.add(value.gsisId());
            transitions.add(value.startSeason() + "-" + value.endSeason());
            minSeason = Math.min(minSeason, value.startSeason());
            maxSeason = Math.max(maxSeason, value.startSeason());
            starts.add(value.startRate());
            deltas.add(value.delta());
        }
        return new SampleCell(key.position(), key.metric(), key.age(), values.size(), players.size(),
            transitions.size(), minSeason, maxSeason, percentile(starts, .5), percentile(deltas, .25),
            percentile(deltas, .5), percentile(deltas, .75));
    }

    static double percentile(List<Double> values, double percentile) {
        if (values == null || values.isEmpty()) throw new IllegalArgumentException("values must not be empty");
        if (percentile < 0 || percentile > 1) throw new IllegalArgumentException("percentile must be between 0 and 1");
        List<Double> sorted = values.stream().sorted().toList();
        if (sorted.size() == 1) return sorted.getFirst();
        double index = (sorted.size() - 1) * percentile;
        int low = (int) Math.floor(index);
        int high = (int) Math.ceil(index);
        if (low == high) return sorted.get(low);
        double fraction = index - low;
        return sorted.get(low) + (sorted.get(high) - sorted.get(low)) * fraction;
    }

    private static Set<Metric> metricsFor(String position) {
        return switch (position) {
            case "QB" -> EnumSet.of(Metric.PASSING_YARDS_PER_GAME, Metric.PASSING_TOUCHDOWNS_PER_GAME,
                Metric.INTERCEPTIONS_PER_GAME, Metric.RUSHING_YARDS_PER_GAME, Metric.RUSHING_TOUCHDOWNS_PER_GAME);
            case "RB" -> EnumSet.of(Metric.RUSHING_YARDS_PER_GAME, Metric.RUSHING_TOUCHDOWNS_PER_GAME,
                Metric.RECEPTIONS_PER_GAME, Metric.RECEIVING_YARDS_PER_GAME,
                Metric.RECEIVING_TOUCHDOWNS_PER_GAME, Metric.FUMBLES_LOST_PER_GAME);
            case "WR" -> EnumSet.of(Metric.RECEPTIONS_PER_GAME, Metric.RECEIVING_YARDS_PER_GAME,
                Metric.RECEIVING_TOUCHDOWNS_PER_GAME, Metric.RUSHING_YARDS_PER_GAME,
                Metric.RUSHING_TOUCHDOWNS_PER_GAME, Metric.FUMBLES_LOST_PER_GAME);
            case "TE" -> EnumSet.of(Metric.RECEPTIONS_PER_GAME, Metric.RECEIVING_YARDS_PER_GAME,
                Metric.RECEIVING_TOUCHDOWNS_PER_GAME, Metric.FUMBLES_LOST_PER_GAME);
            default -> EnumSet.noneOf(Metric.class);
        };
    }

    private static String normalizePosition(String position) {
        return position == null || position.isBlank() ? "UNKNOWN" : position.trim().toUpperCase(Locale.ROOT);
    }

    public enum Metric {
        PASSING_YARDS_PER_GAME {
            double rate(AgingModelPlayerSeasonProduction p) { return p.passingYards() / (double) p.gamesPlayed(); }
        },
        PASSING_TOUCHDOWNS_PER_GAME {
            double rate(AgingModelPlayerSeasonProduction p) { return p.passingTouchdowns() / (double) p.gamesPlayed(); }
        },
        INTERCEPTIONS_PER_GAME {
            double rate(AgingModelPlayerSeasonProduction p) { return p.interceptions() / (double) p.gamesPlayed(); }
        },
        RUSHING_YARDS_PER_GAME {
            double rate(AgingModelPlayerSeasonProduction p) { return p.rushingYards() / (double) p.gamesPlayed(); }
        },
        RUSHING_TOUCHDOWNS_PER_GAME {
            double rate(AgingModelPlayerSeasonProduction p) { return p.rushingTouchdowns() / (double) p.gamesPlayed(); }
        },
        RECEPTIONS_PER_GAME {
            double rate(AgingModelPlayerSeasonProduction p) { return p.receptions() / (double) p.gamesPlayed(); }
        },
        RECEIVING_YARDS_PER_GAME {
            double rate(AgingModelPlayerSeasonProduction p) { return p.receivingYards() / (double) p.gamesPlayed(); }
        },
        RECEIVING_TOUCHDOWNS_PER_GAME {
            double rate(AgingModelPlayerSeasonProduction p) { return p.receivingTouchdowns() / (double) p.gamesPlayed(); }
        },
        FUMBLES_LOST_PER_GAME {
            double rate(AgingModelPlayerSeasonProduction p) { return p.fumblesLost() / (double) p.gamesPlayed(); }
        };

        abstract double rate(AgingModelPlayerSeasonProduction production);
    }

    private record GroupKey(String position, Metric metric, int age) {}

    public record AgingObservation(String gsisId, String playerName, String position, Metric metric,
                                   int age, int startSeason, int endSeason,
                                   double startRate, double endRate, double delta) {}

    public record SampleCell(String position, Metric metric, int age, int observations, int uniquePlayers,
                             int distinctSeasonTransitions, int minimumStartSeason, int maximumStartSeason,
                             double medianStartRate, double deltaP25, double medianDelta, double deltaP75) {}

    public record SampleAuditReport(String profileSource, String productionSource,
                                    int modelProfilePlayers, int exactBirthDatePlayers,
                                    int productionPlayerSeasons, int zeroGamePlayerSeasons,
                                    int productionPlayersWithoutProfile, int profilePlayersWithoutProduction,
                                    int consecutivePairs, int exactDobRatePairs,
                                    int zeroGameExcludedPairs, int missingBirthDatePairs,
                                    int positionChangeExcludedPairs, int unsupportedPositionPairs,
                                    List<AgingObservation> observations, List<SampleCell> cells) {
        public SampleAuditReport {
            observations = List.copyOf(Objects.requireNonNull(observations, "observations must not be null"));
            cells = List.copyOf(Objects.requireNonNull(cells, "cells must not be null"));
        }
        public int metricObservations() { return observations.size(); }
        public int sampleCells() { return cells.size(); }
    }
}
