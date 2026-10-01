package io.butler.bet.sleeper;

import io.butler.bet.data.Database;
import io.butler.bet.data.PlayerWeekProductionRepository;
import io.butler.bet.domain.PlayerWeekProduction;
import io.butler.bet.intelligence.AutoFillLineupOptimizer;
import io.butler.bet.intelligence.NflversePlayerWeekProductionImporter;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Separates observed completed-week production from the projection that proposes a swap. */
final class LineupDecisionEvidence {
    static List<String> describe(Database database,
        SleeperLiveWaiverTargetRosterContextAudit.AuditReport roster,
        AutoFillLineupOptimizer.Recommendation recommendation) throws SQLException {
        Map<String, SleeperLiveWaiverTargetRosterContextAudit.TargetPlayer> targets = new LinkedHashMap<>();
        for (var player : roster.targetPlayers()) targets.put(player.sleeperPlayerId(), player);
        var repository = new PlayerWeekProductionRepository(database);
        List<String> evidence = new ArrayList<>();
        for (var assignment : recommendation.assignments()) {
            if (!assignment.changed()) continue;
            var current = targets.get(assignment.currentPlayerId());
            var proposed = targets.get(assignment.recommendedPlayerId());
            evidence.add(assignment.slot() + ": " + assignment.currentPlayerName() + " -> "
                + assignment.recommendedPlayerName() + ". Projection proposes the change; observed production is separate evidence. "
                + describePlayer(repository, current, roster.providerSeason(), roster.providerLeg()) + " "
                + describePlayer(repository, proposed, roster.providerSeason(), roster.providerLeg())
                + " NFL defensive matchup and expert start/sit advice: not verified. "
                + "Lower past production alone does not establish a worse future role.");
        }
        return List.copyOf(evidence);
    }

    private static String describePlayer(PlayerWeekProductionRepository repository,
        SleeperLiveWaiverTargetRosterContextAudit.TargetPlayer player, int season, int week) throws SQLException {
        if (player == null || player.butlerPlayerId() == null) return "Recent production: identity unavailable.";
        int firstWeek = Math.max(1, week - 3);
        if (week <= 1) return player.displayName() + ": no completed regular-season weeks yet.";
        var observations = repository.findByPlayerSeason(player.butlerPlayerId(), season,
            NflversePlayerWeekProductionImporter.SOURCE);
        Map<Integer, PlayerWeekProduction> latest = new LinkedHashMap<>();
        for (var observation : observations) {
            if (observation.week() < firstWeek || observation.week() >= week
                || observation.asOfDate().isAfter(LocalDate.now(java.time.ZoneOffset.UTC))) continue;
            latest.putIfAbsent(observation.week(), observation);
        }
        if (latest.isEmpty()) return player.displayName() + ": recent production unavailable for weeks "
            + firstWeek + "-" + (week - 1) + "; missing data is not zero usage.";
        List<String> summaries = new ArrayList<>();
        for (int evidenceWeek = firstWeek; evidenceWeek < week; evidenceWeek++) {
            var observation = latest.get(evidenceWeek);
            if (observation == null) { summaries.add("week " + evidenceWeek + " missing"); continue; }
            String attempts = observation.rawScoringSchemaVersion() >= 2
                ? Integer.toString(observation.rushingAttempts()) : "unavailable in this schema";
            summaries.add("week " + evidenceWeek + ": carries=" + attempts
                + ", receptions=" + observation.receptions() + ", rushing yards=" + observation.rushingYards()
                + ", receiving yards=" + observation.receivingYards()
                + ("QB".equals(player.position()) ? ", passing yards=" + observation.passingYards()
                    + ", passing TDs=" + observation.passingTouchdowns() + ", interceptions=" + observation.interceptions() : "")
                + "; as of=" + observation.asOfDate());
        }
        return player.displayName() + " [" + player.sleeperPlayerId() + "]: " + String.join("; ", summaries)
            + "; source=" + NflversePlayerWeekProductionImporter.SOURCE
            + ". Snaps, targets, and depth-chart role are not established by these rows.";
    }
}
