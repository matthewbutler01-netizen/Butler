package io.butler.bet.domain;

import java.time.LocalDate;
import java.util.Objects;
import java.util.UUID;

/** Raw season production evidence independent of any fantasy scoring system. */
public record PlayerSeasonProduction(
    String id,
    String playerId,
    int season,
    int gamesPlayed,
    int passingYards,
    int passingTouchdowns,
    int interceptions,
    int rushingYards,
    int rushingTouchdowns,
    int receptions,
    int receivingYards,
    int receivingTouchdowns,
    int fumblesLost,
    String source,
    LocalDate asOfDate) {

    public PlayerSeasonProduction {
        id = requireText(id, "id");
        playerId = requireText(playerId, "playerId");
        source = requireText(source, "source");
        Objects.requireNonNull(asOfDate, "asOfDate must not be null");
        if (season <= 0) throw new IllegalArgumentException("season must be positive");
        requireNonNegative(gamesPlayed, "gamesPlayed");
        requireNonNegative(passingYards, "passingYards");
        requireNonNegative(passingTouchdowns, "passingTouchdowns");
        requireNonNegative(interceptions, "interceptions");
        requireNonNegative(rushingYards, "rushingYards");
        requireNonNegative(rushingTouchdowns, "rushingTouchdowns");
        requireNonNegative(receptions, "receptions");
        requireNonNegative(receivingYards, "receivingYards");
        requireNonNegative(receivingTouchdowns, "receivingTouchdowns");
        requireNonNegative(fumblesLost, "fumblesLost");
    }

    public static PlayerSeasonProduction create(
        String playerId, int season, int gamesPlayed,
        int passingYards, int passingTouchdowns, int interceptions,
        int rushingYards, int rushingTouchdowns,
        int receptions, int receivingYards, int receivingTouchdowns,
        int fumblesLost, String source, LocalDate asOfDate) {
        return new PlayerSeasonProduction(UUID.randomUUID().toString(), playerId, season, gamesPlayed,
            passingYards, passingTouchdowns, interceptions, rushingYards, rushingTouchdowns,
            receptions, receivingYards, receivingTouchdowns, fumblesLost, source, asOfDate);
    }

    private static String requireText(String value, String field) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(field + " must not be blank");
        return value.trim();
    }

    private static void requireNonNegative(int value, String field) {
        if (value < 0) throw new IllegalArgumentException(field + " must not be negative");
    }
}
