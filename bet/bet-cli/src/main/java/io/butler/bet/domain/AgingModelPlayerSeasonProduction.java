package io.butler.bet.domain;

import java.time.LocalDate;
import java.util.Objects;

/** Versioned raw season production keyed directly by provider GSIS identity for model training. */
public record AgingModelPlayerSeasonProduction(String gsisId, int season, String position, int gamesPlayed,
                                                int passingYards, int passingTouchdowns, int interceptions,
                                                int rushingYards, int rushingTouchdowns, int receptions,
                                                int receivingYards, int receivingTouchdowns, int fumblesLost,
                                                String source, LocalDate asOfDate) {
    public AgingModelPlayerSeasonProduction {
        gsisId = requireText(gsisId, "gsisId");
        position = requireText(position, "position");
        source = requireText(source, "source");
        Objects.requireNonNull(asOfDate, "asOfDate must not be null");
        if (season <= 0) throw new IllegalArgumentException("season must be positive");
        if (gamesPlayed < 0 || passingTouchdowns < 0 || interceptions < 0
            || rushingTouchdowns < 0 || receptions < 0
            || receivingTouchdowns < 0 || fumblesLost < 0) {
            throw new IllegalArgumentException("count production values must not be negative");
        }
        // Yardage is intentionally signed. Legitimate NFL season rows can contain negative
        // passing, rushing, or receiving yards; those are evidence, not malformed data.
    }

    private static String requireText(String value, String field) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(field + " must not be blank");
        return value.trim();
    }
}
