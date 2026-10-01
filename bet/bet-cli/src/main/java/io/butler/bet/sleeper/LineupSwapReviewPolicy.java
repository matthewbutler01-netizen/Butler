package io.butler.bet.sleeper;

import java.math.BigDecimal;

/** Conservative close-call qualification; does not add synthetic forecast points. */
final class LineupSwapReviewPolicy {
    static final BigDecimal CLOSE_GAIN = BigDecimal.ONE;

    static boolean conflictingUsage(BigDecimal gain, NflverseRosterUsageProvider.UsageEvidence current,
        NflverseRosterUsageProvider.UsageEvidence proposed) {
        if (gain == null || gain.signum() <= 0 || gain.compareTo(CLOSE_GAIN) > 0
            || current == null || proposed == null || !current.complete() || !proposed.complete()) return false;
        var oldCurrent = current.weeks().getFirst();
        var newCurrent = current.weeks().getLast();
        var oldProposed = proposed.weeks().getFirst();
        var newProposed = proposed.weeks().getLast();
        return oldCurrent.opportunities() >= 4 && oldProposed.opportunities() >= 4
            && newCurrent.opportunities() * 4 >= oldCurrent.opportunities() * 5
            && newProposed.opportunities() * 2 <= oldProposed.opportunities();
    }
}
