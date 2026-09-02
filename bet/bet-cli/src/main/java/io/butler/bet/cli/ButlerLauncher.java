package io.butler.bet.cli;

import io.butler.bet.data.Database;
import io.butler.bet.intelligence.LeagueCompositeTeamProfileAnalyzer;
import io.butler.bet.intelligence.LeagueEvidenceOverviewAnalyzer;
import io.butler.bet.intelligence.LeaguePlayerEvidenceReadinessAnalyzer;
import io.butler.bet.intelligence.NflversePlayerSeasonProductionImporter;

import java.io.IOException;
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

        if (isEvidenceOverviewCommand(args)) {
            if (!isSupportedEvidenceOverview(args)) {
                printEvidenceOverviewUsage();
                return;
            }
            try {
                printEvidenceOverview(analyzeEvidenceOverview(args));
            } catch (SQLException e) {
                System.err.println("Database error while building league evidence overview: " + e.getMessage());
                System.exit(1);
            } catch (IllegalArgumentException | IllegalStateException e) {
                System.err.println("Error: " + e.getMessage());
                System.exit(2);
            }
            return;
        }

        if (isPlayerEvidenceReadinessCommand(args)) {
            if (!isSupportedPlayerEvidenceReadiness(args)) {
                printPlayerEvidenceReadinessUsage();
                return;
            }
            try {
                printPlayerEvidenceReadiness(analyzePlayerEvidenceReadiness(args));
            } catch (SQLException e) {
                System.err.println("Database error while building player evidence readiness: " + e.getMessage());
                System.exit(1);
            } catch (IllegalArgumentException | IllegalStateException e) {
                System.err.println("Error: " + e.getMessage());
                System.exit(2);
            }
            return;
        }

        if (isNflverseProductionCommand(args)) {
            if (!isSupportedNflverseProduction(args)) {
                printNflverseProductionUsage();
                return;
            }
            try {
                boolean persist = args[1].equalsIgnoreCase("production-refresh");
                printNflverseProduction(runNflverseProduction(parseSeason(args[2]), persist));
            } catch (IOException e) {
                System.err.println("nflverse download error: " + e.getMessage());
                System.exit(1);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                System.err.println("nflverse download interrupted");
                System.exit(1);
            } catch (SQLException e) {
                System.err.println("Database error while processing nflverse production: " + e.getMessage());
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
            printEvidenceOverviewUsage();
            printPlayerEvidenceReadinessUsage();
            printNflverseProductionUsage();
        }
    }

    static boolean isSupportedTeamProfile(String[] args) {
        if (!isTeamProfileCommand(args) || args.length < 3) return false;
        if (args.length == 3 || args.length == 4) return true;
        if (args.length == 5) return args[3].equalsIgnoreCase("--minimum-as-of");
        return args.length == 6 && args[4].equalsIgnoreCase("--minimum-as-of");
    }

    static boolean isSupportedEvidenceOverview(String[] args) {
        return isEvidenceOverviewCommand(args) && (args.length == 3 || args.length == 4);
    }

    static boolean isSupportedPlayerEvidenceReadiness(String[] args) {
        if (!isPlayerEvidenceReadinessCommand(args)) return false;
        if (args.length == 3 || args.length == 4) return true;
        if (args.length == 5) return args[3].equalsIgnoreCase("--minimum-profile-as-of");
        return args.length == 6 && args[4].equalsIgnoreCase("--minimum-profile-as-of");
    }

    static boolean isSupportedNflverseProduction(String[] args) {
        return isNflverseProductionCommand(args) && args.length == 3;
    }

    private static boolean isTeamProfileCommand(String[] args) {
        return args != null && args.length >= 2
            && args[0].equalsIgnoreCase("league")
            && args[1].equalsIgnoreCase("team-profile");
    }

    private static boolean isEvidenceOverviewCommand(String[] args) {
        return args != null && args.length >= 2
            && args[0].equalsIgnoreCase("league")
            && args[1].equalsIgnoreCase("evidence-overview");
    }

    private static boolean isPlayerEvidenceReadinessCommand(String[] args) {
        return args != null && args.length >= 2
            && args[0].equalsIgnoreCase("league")
            && args[1].equalsIgnoreCase("player-evidence-readiness");
    }

    private static boolean isNflverseProductionCommand(String[] args) {
        return args != null && args.length >= 2
            && args[0].equalsIgnoreCase("nflverse")
            && (args[1].equalsIgnoreCase("production-preview")
                || args[1].equalsIgnoreCase("production-refresh"));
    }

    private static LeagueCompositeTeamProfileAnalyzer.CompositeProfileReport analyzeTeamProfile(String[] args)
        throws SQLException {
        Database database = initializedDatabase();
        LeagueCompositeTeamProfileAnalyzer analyzer = new LeagueCompositeTeamProfileAnalyzer(database);
        if (args.length == 3) return analyzer.analyze(args[2]);
        if (args.length == 4) return analyzer.analyze(args[2], args[3]);
        if (args.length == 5) return analyzer.analyze(args[2], parseDate(args[4]));
        return analyzer.analyze(args[2], args[3], parseDate(args[5]));
    }

    private static LeagueEvidenceOverviewAnalyzer.EvidenceOverviewReport analyzeEvidenceOverview(String[] args)
        throws SQLException {
        LeagueEvidenceOverviewAnalyzer analyzer = new LeagueEvidenceOverviewAnalyzer(initializedDatabase());
        return args.length == 3 ? analyzer.analyze(args[2]) : analyzer.analyze(args[2], parseSeason(args[3]));
    }

    private static LeaguePlayerEvidenceReadinessAnalyzer.ReadinessReport analyzePlayerEvidenceReadiness(String[] args)
        throws SQLException {
        LeaguePlayerEvidenceReadinessAnalyzer analyzer =
            new LeaguePlayerEvidenceReadinessAnalyzer(initializedDatabase());
        if (args.length == 3) return analyzer.analyze(args[2]);
        if (args.length == 4) return analyzer.analyze(args[2], parseSeason(args[3]));
        if (args.length == 5) return analyzer.analyze(args[2], parseDate(args[4]));
        return analyzer.analyze(args[2], parseSeason(args[3]), parseDate(args[5]));
    }

    private static NflversePlayerSeasonProductionImporter.ImportResult runNflverseProduction(int season, boolean persist)
        throws IOException, InterruptedException, SQLException {
        NflversePlayerSeasonProductionImporter importer =
            new NflversePlayerSeasonProductionImporter(initializedDatabase());
        return persist ? importer.refresh(season) : importer.preview(season);
    }

    static void printEvidenceOverview(LeagueEvidenceOverviewAnalyzer.EvidenceOverviewReport report) {
        if (report == null) throw new IllegalArgumentException("report must not be null");
        var decisions = report.decisionReadiness();
        var players = report.playerEvidenceReadiness();
        System.out.println("League evidence overview");
        System.out.println("League ID: " + report.leagueId());
        System.out.println("Player season: " + report.playerSeason());
        System.out.println("Decision readiness: " + decisions.readiness());
        System.out.printf("Current-value decisions: %s  trend-aware decisions: %s%n",
            report.currentValueDecisionsReady() ? "READY" : "BLOCKED",
            report.trendAwareDecisionsReady() ? "READY" : "BLOCKED");
        System.out.println("Player-evidence readiness: " + players.readiness());
        System.out.printf("Age evidence: %d/%d (%.1f%%)  production evidence: %d/%d (%.1f%%)%n",
            players.ageEvidencePlayers(), players.totalPlayers(), players.ageCoveragePercent(),
            players.productionEvidencePlayers(), players.totalPlayers(), players.productionCoveragePercent());
        System.out.println("Readiness dimensions are independent; this command does not combine them into a score.");
    }

    static void printPlayerEvidenceReadiness(LeaguePlayerEvidenceReadinessAnalyzer.ReadinessReport report) {
        if (report == null) throw new IllegalArgumentException("report must not be null");
        System.out.println("League player evidence readiness");
        System.out.println("League ID: " + report.leagueId());
        System.out.println("Season: " + report.season());
        System.out.println("Profile source: " + report.profileSource());
        System.out.println("Production source: " + report.productionSource());
        if (report.minimumProfileAsOf() != null) {
            System.out.println("Minimum profile as-of: " + report.minimumProfileAsOf());
        }
        System.out.println("Readiness: " + report.readiness());
        System.out.printf("Age evidence: %d/%d (%.1f%%)%n",
            report.ageEvidencePlayers(), report.totalPlayers(), report.ageCoveragePercent());
        System.out.printf("Production evidence: %d/%d (%.1f%%)%n",
            report.productionEvidencePlayers(), report.totalPlayers(), report.productionCoveragePercent());
        for (var team : report.teams()) {
            System.out.printf("%s  readiness=%s  age=%d/%d (%.1f%%)  production=%d/%d (%.1f%%)  exact-birth=%d  reported-age=%d  experience=%d  [%s]%n",
                team.teamName(), team.readiness(), team.ageEvidencePlayers(), team.totalPlayers(), team.ageCoveragePercent(),
                team.productionEvidencePlayers(), team.totalPlayers(), team.productionCoveragePercent(),
                team.exactBirthDatePlayers(), team.reportedAgePlayers(), team.experienceEvidencePlayers(), team.teamId());
        }
    }

    static void printNflverseProduction(NflversePlayerSeasonProductionImporter.ImportResult result) {
        if (result == null) throw new IllegalArgumentException("result must not be null");
        System.out.println(result.persisted() ? "nflverse production refresh" : "nflverse production preview");
        System.out.println("Season: " + result.season());
        System.out.println("As-of: " + result.asOfDate());
        System.out.printf("Provider rows: %d  requested-season=%d  crosswalk=%d  mapped=%d%n",
            result.providerRows(), result.providerRowsForSeason(), result.crosswalkEntries(), result.providerRowsMapped());
        System.out.printf("Butler players: eligible=%d  matched=%d  unmatched=%d%n",
            result.eligiblePlayers(), result.matchedPlayers(), result.unmatchedPlayers());
        System.out.println(result.persisted()
            ? "Production snapshots written: " + result.snapshotsWritten()
            : "Production snapshots written: 0 (preview only)");
        if (!result.unmatched().isEmpty()) {
            System.out.println("Unmatched Butler players:");
            result.unmatched().stream().limit(20).forEach(player ->
                System.out.printf("  %s  sleeper=%s  [%s]%n", player.playerName(), player.sleeperId(), player.playerId()));
            if (result.unmatched().size() > 20) {
                System.out.println("  ... " + (result.unmatched().size() - 20) + " more");
            }
        }
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
            int slotValued = slots.slots().values().stream().mapToInt(slot -> slot.valuedPlayers()).sum();
            int slotStale = slots.slots().values().stream().mapToInt(slot -> slot.stalePlayers()).sum();
            int slotMissing = slots.slots().values().stream().mapToInt(slot -> slot.missingPlayers()).sum();
            int slotTotal = slots.slots().values().stream().mapToInt(slot -> slot.totalPlayers()).sum();

            System.out.printf(
                "%s  assets=%.2f  players=%.2f  picks=%.2f  starter-share=%.1f%%  top1=%.1f%%  top3=%.1f%%  hhi=%.4f  asset-coverage=%d/%d (%.1f%%)  [%s]%n",
                team.teamName(), team.usableAssetValue(), team.usablePlayerValue(), team.usableDraftPickValue(),
                team.starterValueSharePercent(), team.topAssetSharePercent(), team.topThreeAssetSharePercent(),
                team.concentrationIndex(), concentration.valuedAssets(), concentration.totalAssets(),
                concentration.coveragePercent(), team.teamId());

            System.out.printf("  roster-slots: valued=%d/%d  stale=%d  missing=%d%n",
                slotValued, slotTotal, slotStale, slotMissing);
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

    static void printEvidenceOverviewUsage() {
        System.out.println("  butler league evidence-overview <league-id> [season]");
    }

    static void printPlayerEvidenceReadinessUsage() {
        System.out.println("  butler league player-evidence-readiness <league-id> [season] [--minimum-profile-as-of YYYY-MM-DD]");
    }

    static void printNflverseProductionUsage() {
        System.out.println("  butler nflverse production-preview <season>");
        System.out.println("  butler nflverse production-refresh <season>");
    }

    private static Database initializedDatabase() throws SQLException {
        Database database = new Database(DATABASE_PATH);
        database.initialize();
        return database;
    }

    private static int parseSeason(String value) {
        try {
            int season = Integer.parseInt(value);
            if (season < 1999 || season > 2100) throw new NumberFormatException();
            return season;
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("season must be a year between 1999 and 2100: " + value);
        }
    }

    private static LocalDate parseDate(String value) {
        try {
            return LocalDate.parse(value);
        } catch (DateTimeParseException e) {
            throw new IllegalArgumentException("minimum-as-of must use YYYY-MM-DD: " + value);
        }
    }
}
