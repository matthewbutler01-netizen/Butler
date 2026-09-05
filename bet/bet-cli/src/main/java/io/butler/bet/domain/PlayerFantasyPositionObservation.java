package io.butler.bet.domain;

import java.time.LocalDate;
import java.util.List;
import java.util.Objects;

/** Provider-declared fantasy-position eligibility observed for one player on one date. */
public record PlayerFantasyPositionObservation(
    String playerId,
    String source,
    LocalDate asOfDate,
    List<String> providerFantasyPositions) {

    public PlayerFantasyPositionObservation {
        playerId = requireText(playerId, "playerId").trim();
        source = requireText(source, "source").trim();
        Objects.requireNonNull(asOfDate, "asOfDate must not be null");
        providerFantasyPositions = List.copyOf(Objects.requireNonNull(
            providerFantasyPositions, "providerFantasyPositions must not be null"));
        for (String position : providerFantasyPositions) {
            requireText(position, "providerFantasyPosition");
        }
    }

    private static String requireText(String value, String field) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(field + " must not be blank");
        return value;
    }
}
