package io.butler.bet.domain;

import java.time.LocalDate;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Dated provider observation tying one league season to lineup slots and scoring settings. */
public record LeagueConfigurationObservation(
    String leagueId,
    String source,
    LocalDate asOfDate,
    Integer providerSeason,
    List<String> lineupSlots,
    Map<String, Double> scoringSettings) {

    public LeagueConfigurationObservation {
        leagueId = requireText(leagueId, "leagueId").trim();
        source = requireText(source, "source").trim();
        Objects.requireNonNull(asOfDate, "asOfDate must not be null");
        if (providerSeason != null && (providerSeason < 1999 || providerSeason > 2100)) {
            throw new IllegalArgumentException("providerSeason must be between 1999 and 2100");
        }
        lineupSlots = List.copyOf(Objects.requireNonNull(lineupSlots, "lineupSlots must not be null"));
        for (String slot : lineupSlots) requireText(slot, "lineupSlot");

        Objects.requireNonNull(scoringSettings, "scoringSettings must not be null");
        LinkedHashMap<String, Double> copiedScoring = new LinkedHashMap<>();
        scoringSettings.forEach((key, value) -> {
            requireText(key, "scoringStatKey");
            if (value == null || !Double.isFinite(value)) {
                throw new IllegalArgumentException("scoring value must be finite for " + key);
            }
            copiedScoring.put(key, value);
        });
        scoringSettings = Collections.unmodifiableMap(copiedScoring);
    }

    private static String requireText(String value, String field) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(field + " must not be blank");
        return value;
    }
}
