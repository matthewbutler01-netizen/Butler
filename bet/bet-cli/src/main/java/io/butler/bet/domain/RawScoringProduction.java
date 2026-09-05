package io.butler.bet.domain;

/** Raw production dimensions that the exact fantasy-scoring registry can consume. */
public interface RawScoringProduction {
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
}
