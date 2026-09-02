package io.butler.bet.domain;

import java.time.LocalDate;
import java.util.Objects;
import java.util.UUID;

/** Versioned provider-reported player profile facts such as age and experience. */
public record PlayerProfileSnapshot(
    String id,
    String playerId,
    Integer reportedAge,
    Integer yearsExperience,
    String source,
    LocalDate asOfDate) {

    public PlayerProfileSnapshot {
        id = requireText(id, "id");
        playerId = requireText(playerId, "playerId");
        source = requireText(source, "source");
        Objects.requireNonNull(asOfDate, "asOfDate must not be null");
        if (reportedAge != null && reportedAge < 0) throw new IllegalArgumentException("reportedAge must not be negative");
        if (yearsExperience != null && yearsExperience < 0) throw new IllegalArgumentException("yearsExperience must not be negative");
    }

    public static PlayerProfileSnapshot create(String playerId, Integer reportedAge,
                                               Integer yearsExperience, String source,
                                               LocalDate asOfDate) {
        return new PlayerProfileSnapshot(UUID.randomUUID().toString(), playerId, reportedAge,
            yearsExperience, source, asOfDate);
    }

    public boolean hasAnyProfileFact() {
        return reportedAge != null || yearsExperience != null;
    }

    private static String requireText(String value, String field) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(field + " must not be blank");
        return value.trim();
    }
}
