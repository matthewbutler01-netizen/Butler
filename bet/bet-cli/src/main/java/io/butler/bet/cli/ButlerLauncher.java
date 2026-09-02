package io.butler.bet.cli;

import io.butler.bet.data.Database;
import io.butler.bet.intelligence.LeagueCompositeTeamProfileAnalyzer;

import java.nio.file.Path;
import java.sql.SQLException;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;

/**
 * Thin application launcher for newly composed CLI workflows. Established commands continue to
 * delegate unchanged to ButlerMain.
 */
public final class ButlerLauncher {
    private static final Path DATABASE_PATH = Path.of("butler.db");

    private ButlerLauncher() {}

    public static void main(String[] args) {
        if (isTeamProfileCommand(args)) {
            if (!isSupportedTeamProfile(args)) {
                printTeamProfileUsage();
                return;
            }
            try {
                printTeamProfile(analyzeTeamProfile(args));
            } catch (SQLException e) {
                System.err.println("Database error while building league team profile: " + e.getMessage());
                System.exit(1);
            } catch (IllegalArgumentException e) {
                System.err.println("Error: " + e.getMessage());
                System.exit(2);
            }
            return;
        }

        ButlerMain.main(args);
        if (args == null || args.length == 0 || (args.length == 1 && args[0].equalsIgnoreCase("help"))) {
            printTeamProfileUsage();
        }
    }

    static boolean isSupportedTeamProfile(String[] args) {
        if (!isTeamProfileCommand(args) || args.length < 3) return false;
        if (args.length == 3 || args.length == 4) return true;
        if (args.length == 5) return args[3].equalsIgnoreCase("--minimum-as-of");
        return args.length == 6 && args[4].equalsIgnoreCase("--minimum-as-of");
    }

    private static boolean isTeamProfileCommand(String[] args) {
        return args != null && args.length >= 2
            && args[0].equalsIgnoreCase("league")
            && args[1].equalsIgnoreCase("team-profile");
    }

    private static LeagueCompositeTeamProfileAnalyzer.CompositeProfileReport analyzeTeamProfile(String[] args)
        throws SQLException {
        Database database = new Database(DATABASE_PATH);
        database.initialize();
        LeagueCompositeTeamProfileAnalyzer analyzer = new LeagueCompositeTeamProfileAnalyzer(database);
        if (args.length == 3) return analyzer.analyze(args[2]);
        if (args.length == 4) return analyzer.analyze(args[2], args[3]);
        if (args.length == 5) return analyzer.analyze(args[2], parseDate(args[4]));
        return analyzer.analyze(args[2], args[3], parseDate(args[5]));
    }

    static void printTeamProfile(LeagueCompositeTeamProfileAnalyzer.CompositeProfileReport report) {
        if (report == null) throw new IllegalArgumentException("report must not be null");
        System.out.println("League composite team profile");
        System.out.println("League ID: " + report.leagueId());
        System.out.println("Source: " + report.source());
        if (report.minimumAsOfDate() != null) System.out.println("Minimum as-of: " + report.minimumAsOfDate());

        for (var team : report.teams()) {
            var concentration = team.concentration();
            var slots = team.rosterSlots();
            var picks = team.draftCapital();
            System.out.printf(
                "%s  assets=%.2f  players=%.2f  picks=%.2f  starter-share=%.1f%%  top1=%.1f%%  top3=%.1f%%  hhi=%.4f  asset-coverage=%d/%d (%.1f%%)  [%s]%n",
                team.teamName(), team.usableAssetValue(), team.usablePlayerValue(), team.usableDraftPickValue(),
                team.starterValueSharePercent(), team.topAssetSharePercent(), team.topThreeAssetSharePercent(),
                team.concentrationIndex(), concentration.valuedAssets(), concentration.totalAssets(),
                concentration.coveragePercent(), team.teamId());

            System.out.printf("  roster-slots: valued=%d/%d  stale=%d  missing=%d%n",
                slots.valuedPlayers(), slots.totalPlayers(), slots.stalePlayers(), slots.missingPlayers());
            System.out.printf("  draft-capital: valued=%d/%d  stale=%d  missing=%d  seasons=%d%n",
                picks.valuedPicks(), picks.totalPicks(), picks.stalePicks(), picks.missingPicks(), picks.seasons().size());

            team.positionalDepth().positions().values().stream()
                .sorted(java.util.Comparator.comparing(io.butler.bet.intelligence.LeaguePositionalDepthAnalyzer.PositionDepth::position))
                .forEach(position -> System.out.printf(
                    "  %s: players=%d  usable-value=%.2f  coverage=%d/%d (%.1f%%)  top1=%.1f%%  top3=%.1f%%%n",
                    position.position(), position.totalPlayers(), position.totalUsableValue(), position.valuedPlayers(),
                    position.totalPlayers(), position.coveragePercent(), position.topOneSharePercent(),
                    position.topThreeSharePercent()));
        }
    }

    static void printTeamProfileUsage() {
        System.out.println("  butler league team-profile <league-id> [source] [--minimum-as-of YYYY-MM-DD]");
    }

    private static LocalDate parseDate(String value) {
        try {
            return LocalDate.parse(value);
        } catch (DateTimeParseException e) {
            throw new IllegalArgumentException("minimum-as-of must use YYYY-MM-DD: " + value);
        }
    }
}
