package io.butler.bet.cli;

import io.butler.bet.data.Database;
import io.butler.bet.sleeper.SleeperPersonalizedTargetService;

/** Shared fail-closed personalized target gate for live waiver operator surfaces. */
final class ButlerPersonalizedTargetCliSupport {
    private ButlerPersonalizedTargetCliSupport() {}

    static SleeperPersonalizedTargetService.VerifiedTarget verify(Database database, String butlerLeagueId)
        throws Exception {
        return new SleeperPersonalizedTargetService(database).verifyBoundTarget(butlerLeagueId);
    }

    static void printVerified(SleeperPersonalizedTargetService.VerifiedTarget target) {
        System.out.println("BF-623 personalized target LIVE VERIFIED");
        System.out.println("Bound Sleeper account: " + target.sleeperUsername() + " / " + target.sleeperUserId());
        System.out.println("Bound Sleeper league: " + target.sleeperLeagueId() + " | " + target.leagueName());
        System.out.println("Bound roster / role: " + target.rosterId() + " / " + target.membershipRole());
        System.out.println("Bound display/team: " + value(target.displayName()) + " / " + value(target.teamName()));
        System.out.println("Binding gate state: " + target.state());
        System.out.println();
    }

    private static String value(Object value) {
        return value == null || value.toString().isBlank() ? "none" : value.toString();
    }
}
