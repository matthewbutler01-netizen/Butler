package io.butler.bet.intelligence;

import java.util.Objects;

/**
 * Converts package-relative trade recommendations into actions from one explicit team's perspective.
 * Side A owns/gives the side-A package and would receive side B; side B owns/gives side B and would receive side A.
 */
public final class TradeTeamPerspectiveRecommendationPolicy {
    public static final String POLICY_ID = "trade-team-perspective-v1-explicit-owner";

    private TradeTeamPerspectiveRecommendationPolicy() {}

    public enum Perspective {
        SIDE_A_TEAM,
        SIDE_B_TEAM
    }

    public enum Action {
        ACCEPT,
        REJECT,
        HOLD,
        INCONCLUSIVE
    }

    public static Action classify(TradeRecommendationPolicy.Recommendation recommendation, Perspective perspective) {
        Objects.requireNonNull(recommendation, "recommendation must not be null");
        Objects.requireNonNull(perspective, "perspective must not be null");

        return switch (recommendation) {
            case HOLD -> Action.HOLD;
            case INCONCLUSIVE -> Action.INCONCLUSIVE;
            case SIDE_A_PACKAGE_PREFERRED -> perspective == Perspective.SIDE_A_TEAM ? Action.REJECT : Action.ACCEPT;
            case SIDE_B_PACKAGE_PREFERRED -> perspective == Perspective.SIDE_A_TEAM ? Action.ACCEPT : Action.REJECT;
        };
    }
}
