package io.butler.bet.sleeper;

import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class LineupSwapReviewPolicyTest {
    @Test void boundaryAndOpposingTrendsAreRequired() {
        assertTrue(LineupSwapReviewPolicy.conflictingUsage(new BigDecimal("1"), usage(4,5), usage(4,2)));
        assertFalse(LineupSwapReviewPolicy.conflictingUsage(new BigDecimal("1.01"), usage(4,5), usage(4,2)));
        assertFalse(LineupSwapReviewPolicy.conflictingUsage(BigDecimal.ZERO, usage(4,5), usage(4,2)));
        assertFalse(LineupSwapReviewPolicy.conflictingUsage(new BigDecimal(".25"), usage(4,4), usage(4,2)));
        assertFalse(LineupSwapReviewPolicy.conflictingUsage(new BigDecimal(".25"), usage(4,5), usage(4,3)));
    }
    @Test void missingAndTinyBaselinesCannotBecomeEvidenceAgainstPromotion() {
        assertFalse(LineupSwapReviewPolicy.conflictingUsage(BigDecimal.ONE, null, usage(4,2)));
        assertFalse(LineupSwapReviewPolicy.conflictingUsage(BigDecimal.ONE, usage(4,5),
            new NflverseRosterUsageProvider.UsageEvidence(false, "missing")));
        assertFalse(LineupSwapReviewPolicy.conflictingUsage(BigDecimal.ONE, usage(1,3), usage(4,2)));
        assertFalse(LineupSwapReviewPolicy.conflictingUsage(BigDecimal.ONE, usage(4,5), usage(2,0)));
    }
    private static NflverseRosterUsageProvider.UsageEvidence usage(int before, int after) {
        return new NflverseRosterUsageProvider.UsageEvidence(false, "observed", List.of(
            new NflverseRosterUsageProvider.WeekUsage(2, 0, before, 50, .8),
            new NflverseRosterUsageProvider.WeekUsage(3, 0, after, 50, .8)), "fixture");
    }
}
