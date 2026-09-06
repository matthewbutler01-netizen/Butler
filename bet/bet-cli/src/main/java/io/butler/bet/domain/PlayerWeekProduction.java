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
    int sacksSuffered,
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
        requireNonNegative(sacksSuffered, "sacksSuffered");
        if (rawScoringSchemaVersion < LEGACY_SCHEMA_VERSION
            || rawScoringSchemaVersion > SACKS_SUFFERED_SCHEMA_VERSION) {
            throw new IllegalArgumentException("rawScoringSchemaVersion must be 1, 2, or 3");
        }
        if (rawScoringSchemaVersion < SACKS_SUFFERED_SCHEMA_VERSION && sacksSuffered != 0) {
            throw new IllegalArgumentException("sacksSuffered requires raw scoring schema v3");
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
            0, 0, 0, 0, 0, 0, 0, LEGACY_SCHEMA_VERSION, source, asOfDate);
    }

    /** Compatibility constructor for BF-548/BF-563 v1-v2 call sites. Schema v3 requires explicit sacks. */
    public PlayerWeekProduction(
        String id, String playerId, int season, int week,
        int passingYards, int passingTouchdowns, int interceptions,
        int rushingYards, int rushingTouchdowns,
        int receptions, int receivingYards, int receivingTouchdowns,
        int fumblesLost, int passingTwoPointConversions, int rushingAttempts,
        int rushingTwoPointConversions, int receivingTwoPointConversions,
        int fumbleRecoveryTouchdowns, int specialTeamsTouchdowns,
        int rawScoringSchemaVersion, String source, LocalDate asOfDate) {
        this(id, playerId, season, week, passingYards, passingTouchdowns, interceptions,
            rushingYards, rushingTouchdowns, receptions, receivingYards, receivingTouchdowns,
            fumblesLost, passingTwoPointConversions, rushingAttempts, rushingTwoPointConversions,
            receivingTwoPointConversions, fumbleRecoveryTouchdowns, specialTeamsTouchdowns,
            compatibilitySacks(rawScoringSchemaVersion), rawScoringSchemaVersion, source, asOfDate);
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
            0, EXTENDED_SCHEMA_VERSION, source, asOfDate);
    }

    public static PlayerWeekProduction createExactScoringV3(
        String playerId, int season, int week,
        int passingYards, int passingTouchdowns, int interceptions,
        int rushingYards, int rushingTouchdowns,
        int receptions, int receivingYards, int receivingTouchdowns,
        int fumblesLost, int passingTwoPointConversions, int rushingAttempts,
        int rushingTwoPointConversions, int receivingTwoPointConversions,
        int fumbleRecoveryTouchdowns, int specialTeamsTouchdowns, int sacksSuffered,
        String source, LocalDate asOfDate) {
        return new PlayerWeekProduction(UUID.randomUUID().toString(), playerId, season, week,
            passingYards, passingTouchdowns, interceptions, rushingYards, rushingTouchdowns,
            receptions, receivingYards, receivingTouchdowns, fumblesLost,
            passingTwoPointConversions, rushingAttempts, rushingTwoPointConversions,
            receivingTwoPointConversions, fumbleRecoveryTouchdowns, specialTeamsTouchdowns,
            sacksSuffered, SACKS_SUFFERED_SCHEMA_VERSION, source, asOfDate);
    }

    private static int compatibilitySacks(int rawScoringSchemaVersion) {
        if (rawScoringSchemaVersion > EXTENDED_SCHEMA_VERSION) {
            throw new IllegalArgumentException(
                "schema v3 requires explicit sacksSuffered; use the v3 constructor/factory");
        }
        return 0;
    }

    private static String requireText(String value, String field) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(field + " must not be blank");
        return value.trim();
    }

    private static void requireNonNegative(int value, String field) {
        if (value < 0) throw new IllegalArgumentException(field + " must not be negative");
    }
}
