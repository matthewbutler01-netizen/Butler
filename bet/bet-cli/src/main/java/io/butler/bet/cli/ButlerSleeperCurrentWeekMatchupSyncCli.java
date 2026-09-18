package io.butler.bet.cli;

import io.butler.bet.data.Database;
import io.butler.bet.sleeper.SleeperLiveWaiverTargetRosterContextAudit;
import io.butler.bet.sleeper.SleeperWeeklyMatchupImporter;

import java.nio.file.Path;

/** BF-840 governed current-week Sleeper matchup evidence sync; Butler-local persistence only. */
public final class ButlerSleeperCurrentWeekMatchupSyncCli {
    private static final Path DATABASE_PATH = Path.of("butler.db");

    private ButlerSleeperCurrentWeekMatchupSyncCli() {}

    public static void main(String[] args) {
        try {
            if (args == null || args.length != 1 || args[0] == null || args[0].isBlank()) {
                throw new IllegalArgumentException(
                    "Usage: sleeperCurrentWeekMatchupSync <butler-league-id>");
            }

            String leagueId = args[0].trim();
            Database database = new Database(DATABASE_PATH);
            database.initialize();

            var target = ButlerPersonalizedTargetCliSupport.verify(database, leagueId);
            var roster = new SleeperLiveWaiverTargetRosterContextAudit(database)
                .audit(leagueId, target.sleeperUserId());

            if (roster.providerLeg() == null || roster.providerLeg() <= 0) {
                throw new IllegalStateException(
                    "BF-840 BLOCKED: current Sleeper week is unavailable from verified target context");
            }

            var result = new SleeperWeeklyMatchupImporter(database)
                .importWeek(roster.sleeperLeagueId(), roster.providerLeg());

            if (!leagueId.equals(result.leagueId())
                || result.season() != roster.providerSeason()
                || result.week() != roster.providerLeg()) {
                throw new IllegalStateException(
                    "BF-840 BLOCKED: imported matchup frame does not reconcile with verified target context");
            }
            if (result.teamsImported() <= 0) {
                throw new IllegalStateException(
                    "BF-840 BLOCKED: Sleeper returned no current-week matchup teams");
            }

            System.out.println("Butler current-week matchup evidence sync");
            System.out.println("Butler league: " + leagueId);
            System.out.println("Sleeper league: " + roster.sleeperLeagueId());
            System.out.println("Season/week: " + result.season() + "/" + result.week());
            System.out.println("Teams imported: " + result.teamsImported());
            System.out.println("Source: " + result.source());
            System.out.println("State: CURRENT_WEEK_MATCHUP_EVIDENCE_SYNCED");
            System.out.println("Boundary: Butler-local matchup/roster evidence only; no Sleeper lineup, roster, waiver, FAAB, trade, or other transaction write was executed.");
        } catch (Exception e) {
            System.err.println("Error: " + e.getMessage());
            System.exit(2);
        }
    }
}
