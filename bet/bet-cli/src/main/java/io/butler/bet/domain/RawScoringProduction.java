package io.butler.bet.domain;

/** Raw production dimensions that the exact fantasy-scoring registry can consume. */
public interface RawScoringProduction {
    int LEGACY_SCHEMA_VERSION = 1;
    int EXTENDED_SCHEMA_VERSION = 2;

    String id();
    String playerId();
    int season();
    int passingYards();
    int passingTouchdowns();
    int interceptions();
    int rushingYards();
    int rushingTouchdowns();
    int receptions();
    int receivingYards();
    int receivingTouchdowns();
    int fumblesLost();

    /**
     * Version of the raw-scoring dimensions represented by this row. Legacy rows predate BF-548
     * and intentionally default to v1 so newly supported scoring dimensions cannot be inferred as zero.
     */
    default int rawScoringSchemaVersion() { return LEGACY_SCHEMA_VERSION; }

    default int passingTwoPointConversions() { return 0; }
    default int rushingAttempts() { return 0; }
    default int rushingTwoPointConversions() { return 0; }
    default int receivingTwoPointConversions() { return 0; }
    default int fumbleRecoveryTouchdowns() { return 0; }
    default int specialTeamsTouchdowns() { return 0; }
}
