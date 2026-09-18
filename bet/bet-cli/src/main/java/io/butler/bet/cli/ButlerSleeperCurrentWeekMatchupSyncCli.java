package io.butler.bet.cli;

import io.butler.bet.data.Database;
import io.butler.bet.sleeper.SleeperLiveSeasonOperationalReadinessAudit;
import io.butler.bet.sleeper.SleeperWeeklyMatchupImporter;

import java.nio.file.Path;

/** Explicit Butler evidence-write CLI for the current exact Sleeper matchup pairing. */
public final class ButlerSleeperCurrentWeekMatchupSyncCli {
    private static final Path DATABASE_PATH = Path.of("butler.db");

    private ButlerSleeperCurrentWeekMatchupSyncCli() {}

    public static void main(String[] args) {
        try {
            String leagueId = parse(args);
            Database database = new Database(DATABASE_PATH);
            database.initialize();

            var live = new SleeperLiveSeasonOperationalReadinessAudit(database).audit(leagueId);
            if (!"in_season".equals(live.providerStatus())) {
                throw new IllegalStateException(
                    "Sleeper league is not in season: " + live.providerStatus());
            }
            if (live.providerLeg() == null || live.providerLeg() <= 0) {
                throw new IllegalStateException("Current Sleeper week is unavailable");
            }

            var imported = new SleeperWeeklyMatchupImporter(database)
                .importWeek(live.sleeperLeagueId(), live.providerLeg());

            if (!live.leagueId().equals(imported.leagueId())
                || live.providerSeason() != imported.season()
                || live.providerLeg() != imported.week()) {
                throw new IllegalStateException(
                    "Imported matchup frame does not match the live Butler/Sleeper frame");
            }

            System.out.println("BF-840 current weekly matchup pairing synchronized.");
            System.out.println("Butler league: " + imported.leagueId());
            System.out.println("Sleeper league: " + live.sleeperLeagueId());
            System.out.println("Season/week: " + imported.season() + "/" + imported.week());
            System.out.println("Teams paired: " + imported.teamsImported());
            System.out.println("Source: " + imported.source());
            System.out.println(
                "Boundary: Butler evidence write only; no lineup, waiver, trade, FAAB, or Sleeper transaction write.");
        } catch (Exception e) {
            System.err.println("Error: " + safeMessage(e));
            System.exit(2);
        }
    }

    static String parse(String[] args) {
        if (args == null || args.length != 1 || args[0] == null || args[0].isBlank()) {
            throw new IllegalArgumentException(
                "sleeperCurrentWeekMatchupSync requires <butler-league-id>");
        }
        return args[0].trim();
    }

    private static String safeMessage(Exception e) {
        String message = e.getMessage();
        return message == null || message.isBlank() ? e.getClass().getSimpleName() : message;
    }
}
