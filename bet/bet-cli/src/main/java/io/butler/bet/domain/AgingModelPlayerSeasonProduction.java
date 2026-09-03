package io.butler.bet.domain;

import java.time.LocalDate;
import java.util.Objects;

/** Versioned raw season production keyed directly by provider GSIS identity for model training. */
public record AgingModelPlayerSeasonProduction(String gsisId, int season, int gamesPlayed,
                                                int passingYards, int passingTouchdowns, int interceptions,
                                                int rushingYards, int rushingTouchdowns, int receptions,
                                                int receivingYards, int receivingTouchdowns, int fumblesLost,
                                                String source, LocalDate asOfDate) {
    public AgingModelPlayerSeasonProduction {
        gsisId = requireText(gsisId, "gsisId");
        source = requireText(source, "source");
        Objects.requireNonNull(asOfDate, "asOfDate must not be null");
        if (season <= 0) throw new IllegalArgumentException("season must be positive");
        if (gamesPlayed < 0 || passingYards < 0 || passingTouchdowns < 0 || interceptions < 0
            || rushingYards < 0 || rushingTouchdowns < 0 || receptions < 0 || receivingYards < 0
            || receivingTouchdowns < 0 || fumblesLost < 0) {
            throw new IllegalArgumentException("production values must not be negative");
        }
    }

    private static String requireText(String value, String field) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(field + " must not be blank");
        return value.trim();
    }
}
