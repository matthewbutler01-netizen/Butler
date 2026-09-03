package io.butler.bet.intelligence;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class TradeTeamPerspectiveRecommendationPolicyTest {
    @Test
    void sideATeamAcceptsWhenSideBPackageIsPreferred() {
        assertEquals(TradeTeamPerspectiveRecommendationPolicy.Action.ACCEPT,
            TradeTeamPerspectiveRecommendationPolicy.classify(
                TradeRecommendationPolicy.Recommendation.SIDE_B_PACKAGE_PREFERRED,
                TradeTeamPerspectiveRecommendationPolicy.Perspective.SIDE_A_TEAM));
    }

    @Test
    void sideATeamRejectsWhenItsOwnPackageIsPreferred() {
        assertEquals(TradeTeamPerspectiveRecommendationPolicy.Action.REJECT,
            TradeTeamPerspectiveRecommendationPolicy.classify(
                TradeRecommendationPolicy.Recommendation.SIDE_A_PACKAGE_PREFERRED,
                TradeTeamPerspectiveRecommendationPolicy.Perspective.SIDE_A_TEAM));
    }

    @Test
    void sideBTeamUsesMirrorMapping() {
        assertEquals(TradeTeamPerspectiveRecommendationPolicy.Action.ACCEPT,
            TradeTeamPerspectiveRecommendationPolicy.classify(
                TradeRecommendationPolicy.Recommendation.SIDE_A_PACKAGE_PREFERRED,
                TradeTeamPerspectiveRecommendationPolicy.Perspective.SIDE_B_TEAM));
        assertEquals(TradeTeamPerspectiveRecommendationPolicy.Action.REJECT,
            TradeTeamPerspectiveRecommendationPolicy.classify(
                TradeRecommendationPolicy.Recommendation.SIDE_B_PACKAGE_PREFERRED,
                TradeTeamPerspectiveRecommendationPolicy.Perspective.SIDE_B_TEAM));
    }

    @Test
    void preservesHoldAndInconclusiveForEitherPerspective() {
        for (var perspective : TradeTeamPerspectiveRecommendationPolicy.Perspective.values()) {
            assertEquals(TradeTeamPerspectiveRecommendationPolicy.Action.HOLD,
                TradeTeamPerspectiveRecommendationPolicy.classify(
                    TradeRecommendationPolicy.Recommendation.HOLD, perspective));
            assertEquals(TradeTeamPerspectiveRecommendationPolicy.Action.INCONCLUSIVE,
                TradeTeamPerspectiveRecommendationPolicy.classify(
                    TradeRecommendationPolicy.Recommendation.INCONCLUSIVE, perspective));
        }
    }
}
