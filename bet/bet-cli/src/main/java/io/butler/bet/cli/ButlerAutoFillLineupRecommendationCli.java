package io.butler.bet.cli;

import io.butler.bet.sleeper.SleeperLiveAutoFillLineupRecommendation;

import java.math.BigDecimal;

/** BF-800/BF-825/BF-826 deterministic text renderer for the read-only My Team AutoFill bundle section. */
public final class ButlerAutoFillLineupRecommendationCli {
    private ButlerAutoFillLineupRecommendationCli() {}

    static void print(SleeperLiveAutoFillLineupRecommendation.RecommendationReport report) {
        if (report == null) throw new IllegalArgumentException("report must not be null");
        System.out.println("AutoFill roster recommendation");
        System.out.println("State: " + (report.ready() ? "READY" : "UNAVAILABLE"));
        System.out.println("Season/week: " + report.season() + "/" + value(report.week()));
        System.out.println("Scoring basis: " + value(report.scoringBasis()));

        if (!report.ready()) {
            System.out.println("Reason: " + report.reason());
            System.out.println("Boundary: AutoFill is preview-only. No Butler or Sleeper lineup write was executed.");
            return;
        }

        var recommendation = report.recommendation();
        System.out.println("Projection source: " + report.sourceName());
        System.out.println("Projection source surface: " + report.sourceSurface());
        System.out.println("Projection provenance: " + report.projectionProvenance());
        System.out.println("Projection coverage: " + (report.projectionHolds().isEmpty() ? "FULL" : "PARTIAL"));
        System.out.println("Mapped active roster players: " + report.mappedActivePlayers());
        System.out.println("Current projected starter total: " + points(report.currentProjectedTotal()));
        System.out.println("Recommended projected starter total: " + points(recommendation.projectedTotal()));
        System.out.println("Projected gain: " + signedPoints(report.projectedGain()));
        System.out.println("Recommended lineup:");
        for (var assignment : recommendation.assignments()) {
            System.out.println("  #" + assignment.starterOrdinal() + " " + assignment.slot()
                + " | current=" + assignment.currentPlayerName() + " [" + assignment.currentPlayerId() + "]"
                + " | recommended=" + assignment.recommendedPlayerName() + " [" + assignment.recommendedPlayerId() + "]"
                + " | projected=" + points(assignment.projectedPoints())
                + " | action=" + (assignment.changed() ? "CHANGE" : "KEEP"));
        }
        System.out.println("Moves to bench:");
        if (recommendation.movesToBench().isEmpty()) {
            System.out.println("  none");
        } else {
            for (var player : recommendation.movesToBench()) {
                System.out.println("  " + player.displayName() + " [" + player.playerId() + "]");
            }
        }
        System.out.println("Promotions to starting lineup:");
        if (recommendation.promotions().isEmpty()) {
            System.out.println("  none");
        } else {
            for (var player : recommendation.promotions()) {
                System.out.println("  " + player.displayName() + " [" + player.playerId() + "]");
            }
        }
        System.out.println("Availability exclusions:");
        if (report.availabilityExclusions().isEmpty()) {
            System.out.println("  none");
        } else {
            for (var exclusion : report.availabilityExclusions()) {
                System.out.println("  " + exclusion.displayName() + " [" + exclusion.sleeperPlayerId() + "]"
                    + " | status=" + value(exclusion.status())
                    + " | injury_status=" + value(exclusion.injuryStatus())
                    + " | reason=" + exclusion.reason());
            }
        }
        System.out.println("Projection holds:");
        if (report.projectionHolds().isEmpty()) {
            System.out.println("  none");
        } else {
            for (var hold : report.projectionHolds()) {
                System.out.println("  " + hold.displayName() + " [" + hold.sleeperPlayerId() + "]"
                    + " | roster_slot=" + hold.rosterSlot()
                    + " | lineup_slot=" + value(hold.lineupSlot())
                    + " | status=" + value(hold.status())
                    + " | injury_status=" + value(hold.injuryStatus())
                    + " | reason=" + hold.reason());
            }
        }
        System.out.println("Boundary: AutoFill is preview-only. Projection holds preserve current lineup state and are not assigned synthetic points. No Butler or Sleeper lineup write was executed.");
    }

    private static String points(BigDecimal value) {
        return value.stripTrailingZeros().toPlainString();
    }

    private static String signedPoints(BigDecimal value) {
        String points = points(value);
        return value.signum() > 0 ? "+" + points : points;
    }

    private static String value(Object value) {
        return value == null || value.toString().isBlank() ? "none" : value.toString();
    }
}
