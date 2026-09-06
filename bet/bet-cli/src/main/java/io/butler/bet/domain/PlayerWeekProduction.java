package io.butler.bet.domain;

import java.time.LocalDate;
import java.util.Objects;
import java.util.UUID;

/** Raw regular-season week production evidence independent of any fantasy scoring system. */
public record PlayerWeekProduction(
    String id,
    String playerId,
    int season,
    int week,
    int passingYards,
    int passingTouchdowns,
    int interceptions,
    int rushingYards,
    int rushingTouchdowns,
    int receptions,
    int receivingYards,
    int receivingTouchdowns,
    int fumblesLost,
    int passingTwoPointConversions,
    int rushingAttempts,
    int rushingTwoPointConversions,
    int receivingTwoPointConversions,
    int fumbleRecoveryTouchdowns,
    int specialTeamsTouchdowns,
    int rawScoringSchemaVersion,
    String source,
    LocalDate asOfDate) implements RawScoringProduction {

    public PlayerWeekProduction {
        id = requireText(id, "id");
        playerId = requireText(playerId, "playerId");
        source = requireText(source, "source");
        Objects.requireNonNull(asOfDate, "asOfDate must not be null");
        if (season <= 0) throw new IllegalArgumentException("season must be positive");
        if (week <= 0) throw new IllegalArgumentException("week must be positive");
        requireNonNegative(passingTouchdowns, "passingTouchdowns");
        requireNonNegative(interceptions, "interceptions");
        requireNonNegative(rushingTouchdowns, "rushingTouchdowns");
        requireNonNegative(receptions, "receptions");
        requireNonNegative(receivingTouchdowns, "receivingTouchdowns");
        requireNonNegative(fumblesLost, "fumblesLost");
        requireNonNegative(passingTwoPointConversions, "passingTwoPointConversions");
        requireNonNegative(rushingAttempts, "rushingAttempts");
        requireNonNegative(rushingTwoPointConversions, "rushingTwoPointConversions");
        requireNonNegative(receivingTwoPointConversions, "receivingTwoPointConversions");
        requireNonNegative(fumbleRecoveryTouchdowns, "fumbleRecoveryTouchdowns");
        requireNonNegative(specialTeamsTouchdowns, "specialTeamsTouchdowns");
        if (rawScoringSchemaVersion < LEGACY_SCHEMA_VERSION || rawScoringSchemaVersion > EXTENDED_SCHEMA_VERSION) {
            throw new IllegalArgumentException("rawScoringSchemaVersion must be 1 or 2");
        }
    }

    /** Compatibility constructor for pre-BF-548 call sites and persisted v1 evidence. */
    public PlayerWeekProduction(
        String id, String playerId, int season, int week,
        int passingYards, int passingTouchdowns, int interceptions,
        int rushingYards, int rushingTouchdowns,
        int receptions, int receivingYards, int receivingTouchdowns,
        int fumblesLost, String source, LocalDate asOfDate) {
        this(id, playerId, season, week, passingYards, passingTouchdowns, interceptions,
            rushingYards, rushingTouchdowns, receptions, receivingYards, receivingTouchdowns, fumblesLost,
            0, 0, 0, 0, 0, 0, LEGACY_SCHEMA_VERSION, source, asOfDate);
    }

    public static PlayerWeekProduction create(
        String playerId, int season, int week,
        int passingYards, int passingTouchdowns, int interceptions,
        int rushingYards, int rushingTouchdowns,
        int receptions, int receivingYards, int receivingTouchdowns,
        int fumblesLost, String source, LocalDate asOfDate) {
        return new PlayerWeekProduction(UUID.randomUUID().toString(), playerId, season, week,
            passingYards, passingTouchdowns, interceptions, rushingYards, rushingTouchdowns,
            receptions, receivingYards, receivingTouchdowns, fumblesLost, source, asOfDate);
    }

    public static PlayerWeekProduction createExactScoringV2(
        String playerId, int season, int week,
        int passingYards, int passingTouchdowns, int interceptions,
        int rushingYards, int rushingTouchdowns,
        int receptions, int receivingYards, int receivingTouchdowns,
        int fumblesLost, int passingTwoPointConversions, int rushingAttempts,
        int rushingTwoPointConversions, int receivingTwoPointConversions,
        int fumbleRecoveryTouchdowns, int specialTeamsTouchdowns,
        String source, LocalDate asOfDate) {
        return new PlayerWeekProduction(UUID.randomUUID().toString(), playerId, season, week,
            passingYards, passingTouchdowns, interceptions, rushingYards, rushingTouchdowns,
            receptions, receivingYards, receivingTouchdowns, fumblesLost,
            passingTwoPointConversions, rushingAttempts, rushingTwoPointConversions,
            receivingTwoPointConversions, fumbleRecoveryTouchdowns, specialTeamsTouchdowns,
            EXTENDED_SCHEMA_VERSION, source, asOfDate);
    }

    private static String requireText(String value, String field) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(field + " must not be blank");
        return value.trim();
    }

    private static void requireNonNegative(int value, String field) {
        if (value < 0) throw new IllegalArgumentException(field + " must not be negative");
    }
}
