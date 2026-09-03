package io.butler.bet.intelligence;

import org.junit.jupiter.api.Test;

import static io.butler.bet.intelligence.LeagueCompetitiveTierPolicy.Tier.BACK_TIER;
import static io.butler.bet.intelligence.LeagueCompetitiveTierPolicy.Tier.FRONT_TIER;
import static io.butler.bet.intelligence.LeagueCompetitiveTierPolicy.Tier.INSUFFICIENT_EVIDENCE;
import static io.butler.bet.intelligence.LeagueCompetitiveTierPolicy.Tier.MIDDLE_TIER;
import static io.butler.bet.intelligence.LeagueRosterStrengthTierPolicy.Tier.BACK_ROSTER_TIER;
import static io.butler.bet.intelligence.LeagueRosterStrengthTierPolicy.Tier.FRONT_ROSTER_TIER;
import static io.butler.bet.intelligence.LeagueRosterStrengthTierPolicy.Tier.MIDDLE_ROSTER_TIER;
import static io.butler.bet.intelligence.TeamPosturePolicy.Posture.CONTENDER;
import static io.butler.bet.intelligence.TeamPosturePolicy.Posture.MIDDLE_OR_MIXED;
import static io.butler.bet.intelligence.TeamPosturePolicy.Posture.REBUILDER;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class TeamPosturePolicyTest {
    @Test
    void onlyFrontAgreementProducesContender() {
        assertEquals(CONTENDER, TeamPosturePolicy.classify(FRONT_TIER, FRONT_ROSTER_TIER));
        assertEquals(MIDDLE_OR_MIXED, TeamPosturePolicy.classify(FRONT_TIER, MIDDLE_ROSTER_TIER));
        assertEquals(MIDDLE_OR_MIXED, TeamPosturePolicy.classify(FRONT_TIER, BACK_ROSTER_TIER));
    }

    @Test
    void onlyBackAgreementProducesRebuilder() {
        assertEquals(REBUILDER, TeamPosturePolicy.classify(BACK_TIER, BACK_ROSTER_TIER));
        assertEquals(MIDDLE_OR_MIXED, TeamPosturePolicy.classify(BACK_TIER, MIDDLE_ROSTER_TIER));
        assertEquals(MIDDLE_OR_MIXED, TeamPosturePolicy.classify(BACK_TIER, FRONT_ROSTER_TIER));
    }

    @Test
    void middleAndCrossTierCasesRemainMixed() {
        assertEquals(MIDDLE_OR_MIXED, TeamPosturePolicy.classify(MIDDLE_TIER, FRONT_ROSTER_TIER));
        assertEquals(MIDDLE_OR_MIXED, TeamPosturePolicy.classify(MIDDLE_TIER, MIDDLE_ROSTER_TIER));
        assertEquals(MIDDLE_OR_MIXED, TeamPosturePolicy.classify(MIDDLE_TIER, BACK_ROSTER_TIER));
        assertEquals(MIDDLE_OR_MIXED, TeamPosturePolicy.classify(FRONT_TIER, BACK_ROSTER_TIER));
        assertEquals(MIDDLE_OR_MIXED, TeamPosturePolicy.classify(BACK_TIER, FRONT_ROSTER_TIER));
    }

    @Test
    void eitherInsufficientDimensionFailsClosed() {
        var rosterInsufficient = LeagueRosterStrengthTierPolicy.Tier.INSUFFICIENT_EVIDENCE;
        assertEquals(TeamPosturePolicy.Posture.INSUFFICIENT_EVIDENCE,
            TeamPosturePolicy.classify(INSUFFICIENT_EVIDENCE, FRONT_ROSTER_TIER));
        assertEquals(TeamPosturePolicy.Posture.INSUFFICIENT_EVIDENCE,
            TeamPosturePolicy.classify(FRONT_TIER, rosterInsufficient));
        assertEquals(TeamPosturePolicy.Posture.INSUFFICIENT_EVIDENCE,
            TeamPosturePolicy.classify(INSUFFICIENT_EVIDENCE, rosterInsufficient));
    }

    @Test
    void rejectsNullDimensions() {
        assertThrows(NullPointerException.class, () -> TeamPosturePolicy.classify(null, FRONT_ROSTER_TIER));
        assertThrows(NullPointerException.class, () -> TeamPosturePolicy.classify(FRONT_TIER, null));
    }
}
