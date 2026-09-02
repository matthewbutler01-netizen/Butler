package io.butler.bet.cli;

import io.butler.bet.data.Database;
import io.butler.bet.data.DraftPickRepository;
import io.butler.bet.data.DraftPickValueRepository;
import io.butler.bet.data.TeamRepository;
import io.butler.bet.intelligence.DynastyProcessDraftPickValueImporter;
import io.butler.bet.intelligence.DynastyProcessValueImporter;
import io.butler.bet.intelligence.LeagueValueSourceResolver;
import io.butler.bet.intelligence.TradeAssetAnalyzer;
import io.butler.bet.sleeper.SleeperDraftPickImporter;
import io.butler.bet.sleeper.SleeperLeagueImporter;

import java.io.IOException;
import java.nio.file.Path;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Application entry point that preserves the existing Butler CLI while extending trade comparison
 * and draft-pick workflows without duplicating the established command implementation.
 */
public final class ButlerApp {
    private static final Path DATABASE_PATH = Path.of("butler.db");

    private ButlerApp() {}

    public static void main(String[] args) {
        if (args.length == 0) {
            ButlerCli.main(args);
            printExtensionHelp();
            return;
        }
        if (args[0].equalsIgnoreCase("help")) {
            ButlerCli.main(args);
            printExtensionHelp();
            return;
        }

        try {
            if (args[0].equalsIgnoreCase("trade")) {
                handleTrade(args);
                return;
            }
            if (args[0].equalsIgnoreCase("sleeper") && isSleeperExtension(args)) {
                handleSleeperExtension(args);
                return;
            }
            if (args[0].equalsIgnoreCase("league") && isLeagueExtension(args)) {
                handleLeagueExtension(args);
                return;
            }
            ButlerCli.main(args);
        } catch (SQLException e) {
            System.err.println("Database error: " + e.getMessage());
            System.exit(1);
        } catch (IOException e) {
            System.err.println("I/O error: " + e.getMessage());
            System.exit(3);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            System.err.println("Operation interrupted.");
            System.exit(4);
        } catch (IllegalArgumentException e) {
            System.err.println("Error: " + e.getMessage());
            System.exit(2);
        }
    }

    private static boolean isSleeperExtension(String[] args) {
        return args.length >= 2 && (args[1].equalsIgnoreCase("sync-picks")
            || args[1].equalsIgnoreCase("sync-all"));
    }

    private static boolean isLeagueExtension(String[] args) {
        return args.length >= 2 && (args[1].equalsIgnoreCase("draft-picks")
            || args[1].equalsIgnoreCase("draft-pick-values"));
    }

    static void handleTrade(String[] args) throws SQLException {
        if ((args.length == 5 || args.length == 6) && args[1].equalsIgnoreCase("compare")) {
            Database database = initializedDatabase();
            TradeAssetAnalyzer analyzer = new TradeAssetAnalyzer(database);
            TradeAssetAnalyzer.TradePackage sideA = parseTradePackage(args[3], "side-a-assets");
            TradeAssetAnalyzer.TradePackage sideB = parseTradePackage(args[4], "side-b-assets");
            var report = args.length == 6
                ? analyzer.analyze(args[2], sideA, sideB, args[5])
                : analyzer.analyze(args[2], sideA, sideB);
            printTradeReport(report);
            return;
        }
        printTradeUsage();
    }

    private static void handleSleeperExtension(String[] args)
        throws SQLException, IOException, InterruptedException {
        if (args.length == 3 && args[1].equalsIgnoreCase("sync-all")) {
            runFullLeagueSync(args[2]);
            return;
        }
        if (args.length == 3 && args[1].equalsIgnoreCase("sync-picks")) {
            var result = new SleeperDraftPickImporter(initializedDatabase()).importLeague(args[2]);
            System.out.println("Synchronized Sleeper draft-pick ownership.");
            System.out.println("League ID: " + result.leagueId());
            System.out.println("Supported year/round coordinates: " + result.supportedCoordinates());
            System.out.println("Teams: " + result.teams());
            System.out.println("Draft-pick assets: " + result.picksImported());
            System.out.println("Traded ownership records applied: " + result.tradedOwnershipApplied());
            System.out.println("Unsupported traded-pick records skipped: " + result.unsupportedTradedPicks());
            System.out.println("Stale draft-pick assets removed: " + result.stalePicksRemoved());
            return;
        }
        printDraftPickUsage();
    }

    private static void runFullLeagueSync(String sleeperLeagueId)
        throws SQLException, IOException, InterruptedException {
        Database database = initializedDatabase();
        var league = new SleeperLeagueImporter(database).importLeague(sleeperLeagueId);
        var picks = new SleeperDraftPickImporter(database).importLeague(sleeperLeagueId);
        var playerValues = new DynastyProcessValueImporter(database).refresh();
        var pickValues = new DynastyProcessDraftPickValueImporter(database).refresh(league.leagueId());

        System.out.println("Full league sync completed.");
        System.out.println("League ID: " + league.leagueId());
        System.out.println("Value format: " + league.valueFormat());
        System.out.printf("Sleeper: teams=%d  players=%d  roster-entries=%d%n",
            league.teamsImported(), league.playersImported(), league.rosterEntriesImported());
        System.out.printf("Draft picks: assets=%d  traded-ownership=%d  unsupported-trades=%d  stale-removed=%d%n",
            picks.picksImported(), picks.tradedOwnershipApplied(), picks.unsupportedTradedPicks(), picks.stalePicksRemoved());
        System.out.printf("Player values: matched=%d/%d  unmatched=%d  snapshots=%d  as-of=%s%n",
            playerValues.matchedPlayers(), playerValues.eligiblePlayers(), playerValues.unmatchedPlayers(),
            playerValues.valuesImported(), playerValues.asOfDate());
        System.out.printf("Draft-pick values: matched=%d/%d (%.1f%%)  missing=%d  snapshots=%d  as-of=%s%n",
            pickValues.matchedPicks(), pickValues.draftPicks(), pickValues.coveragePercent(),
            pickValues.missingPicks(), pickValues.valuesImported(), pickValues.asOfDate());
        System.out.println("Player value sources remain permissive: mapping gaps are reported and league analysis keeps its existing coverage guards.");
    }

    private static void handleLeagueExtension(String[] args)
        throws SQLException, IOException, InterruptedException {
        if ((args.length == 3 || args.length == 4) && args[1].equalsIgnoreCase("draft-picks")) {
            printDraftPicks(args[2], args.length == 4 ? args[3] : null);
            return;
        }
        if (args.length == 4 && args[1].equalsIgnoreCase("draft-pick-values")) {
            requireDynastyProcess(args[3]);
            var result = new DynastyProcessDraftPickValueImporter(initializedDatabase()).refresh(args[2]);
            System.out.println("Refreshed draft-pick values from DynastyProcess.");
            System.out.println("League ID: " + result.leagueId());
            System.out.println("As-of: " + result.asOfDate());
            System.out.printf("Coverage: %d/%d (%.1f%%)%n",
                result.matchedPicks(), result.draftPicks(), result.coveragePercent());
            System.out.println("Missing generic values: " + result.missingPicks());
            System.out.println("Snapshots imported: " + result.valuesImported());
            if (!result.missing().isEmpty()) {
                System.out.println("Missing draft-pick coordinates:");
                for (var missing : result.missing()) {
                    System.out.printf("  %d round %d  [%s]%n",
                        missing.season(), missing.round(), missing.draftPickId());
                }
            }
            return;
        }
        printDraftPickUsage();
    }

    private static void printDraftPicks(String leagueId, String sourceOverride) throws SQLException {
        Database database = initializedDatabase();
        String source = new LeagueValueSourceResolver(database).resolve(leagueId, sourceOverride);
        var picks = new DraftPickRepository(database).findByLeagueId(leagueId);
        System.out.println("League draft picks");
        System.out.println("League ID: " + leagueId.trim());
        System.out.println("Source: " + source);
        if (picks.isEmpty()) {
            System.out.println("No persisted draft-pick assets found. Run `butler sleeper sync-picks <sleeper-league-id>` first.");
            return;
        }

        Map<String, String> teamNames = new HashMap<>();
        for (var team : new TeamRepository(database).findByLeagueId(leagueId)) {
            teamNames.put(team.getId(), team.getName());
        }
        DraftPickValueRepository values = new DraftPickValueRepository(database);
        for (var pick : picks) {
            String owner = teamNames.getOrDefault(pick.getOwnerTeamId(), pick.getOwnerTeamId());
            String original = teamNames.getOrDefault(pick.getOriginalTeamId(), pick.getOriginalTeamId());
            String ownership = owner.equals(original) ? "owner=" + owner : "owner=" + owner + "  original=" + original;
            String slot = pick.getPickNumber() == null ? "" : "  slot=" + pick.getPickNumber();
            var value = values.findLatestByDraftPickIdAndSource(pick.getId(), source).orElse(null);
            if (value == null) {
                System.out.printf("MISSING  %s  %s%s  [%s]%n",
                    pickLabel(pick.getSeason(), pick.getRound()), ownership, slot, pick.getId());
            } else {
                System.out.printf("%.2f  %s  %s%s  as-of=%s  [%s]%n",
                    value.getValue(), pickLabel(pick.getSeason(), pick.getRound()), ownership, slot,
                    value.getAsOfDate(), pick.getId());
            }
        }
    }

    static TradeAssetAnalyzer.TradePackage parseTradePackage(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
        List<String> players = new ArrayList<>();
        List<String> picks = new ArrayList<>();
        for (String raw : value.split(",", -1)) {
            String token = raw.trim();
            if (token.isEmpty()) {
                throw new IllegalArgumentException(field + " contains a blank asset");
            }
            int separator = token.indexOf(':');
            if (separator < 0) {
                players.add(token); // Backward-compatible player-only syntax.
                continue;
            }
            String type = token.substring(0, separator).trim();
            String id = token.substring(separator + 1).trim();
            if (id.isEmpty()) {
                throw new IllegalArgumentException(field + " contains an asset with a blank ID: " + token);
            }
            if (type.equalsIgnoreCase("player")) {
                players.add(id);
            } else if (type.equalsIgnoreCase("pick")) {
                picks.add(id);
            } else {
                throw new IllegalArgumentException(
                    field + " contains unknown asset type '" + type + "'; use player:<id> or pick:<id>");
            }
        }
        return new TradeAssetAnalyzer.TradePackage(List.copyOf(players), List.copyOf(picks));
    }

    private static void printTradeReport(TradeAssetAnalyzer.TradeReport report) {
        System.out.println("Trade value comparison");
        System.out.println("League ID: " + report.leagueId());
        System.out.println("Source: " + report.source());
        System.out.printf("Coverage: %d/%d assets (%.1f%%)%n",
            report.valuedAssets(), report.totalAssets(), report.coveragePercent());
        printTradeSide("Side A", report.sideA());
        printTradeSide("Side B", report.sideB());
        if (report.complete()) {
            System.out.printf("Side A - Side B: %+.2f%n", report.valueDifference());
        } else {
            System.out.println("Side A - Side B: unavailable until all trade assets have values.");
        }
    }

    private static void printTradeSide(String label, TradeAssetAnalyzer.TradeSide side) {
        System.out.printf("%s: value=%.2f  coverage=%d/%d (%.1f%%)%n",
            label, side.totalValue(), side.valuedAssets(), side.totalAssets(), side.coveragePercent());
        for (var player : side.players()) {
            if (player.valued()) {
                System.out.printf("  %.2f  PLAYER  %s  %s%s  fantasy-team=%s  as-of=%s  [%s]%n",
                    player.value(), player.position(), player.playerName(), formatTeam(player.nflTeam()),
                    player.teamName(), player.asOfDate(), player.playerId());
            } else {
                System.out.printf("  MISSING  PLAYER  %s  %s%s  fantasy-team=%s  [%s]%n",
                    player.position(), player.playerName(), formatTeam(player.nflTeam()),
                    player.teamName(), player.playerId());
            }
        }
        for (var pick : side.draftPicks()) {
            String original = pick.originalTeamName().equals(pick.ownerTeamName())
                ? "" : "  original=" + pick.originalTeamName();
            String slot = pick.pickNumber() == null ? "" : "  slot=" + pick.pickNumber();
            if (pick.valued()) {
                System.out.printf("  %.2f  PICK  %s  owner=%s%s%s  as-of=%s  [%s]%n",
                    pick.value(), pick.label(), pick.ownerTeamName(), original, slot,
                    pick.asOfDate(), pick.draftPickId());
            } else {
                System.out.printf("  MISSING  PICK  %s  owner=%s%s%s  [%s]%n",
                    pick.label(), pick.ownerTeamName(), original, slot, pick.draftPickId());
            }
        }
    }

    private static Database initializedDatabase() throws SQLException {
        Database database = new Database(DATABASE_PATH);
        database.initialize();
        return database;
    }

    private static void requireDynastyProcess(String provider) {
        if (!provider.equalsIgnoreCase("dynastyprocess")) {
            throw new IllegalArgumentException("unknown value provider: " + provider + ". Supported: dynastyprocess");
        }
    }

    private static String pickLabel(int season, int round) {
        return season + " " + switch (round) {
            case 1 -> "1st";
            case 2 -> "2nd";
            case 3 -> "3rd";
            default -> round + "th";
        };
    }

    private static String formatTeam(String nflTeam) {
        return nflTeam == null ? "" : "  " + nflTeam;
    }

    private static void printExtensionHelp() {
        System.out.println();
        printTradeUsage();
        printDraftPickUsage();
    }

    private static void printTradeUsage() {
        System.out.println("Mixed trade assets:");
        System.out.println("  butler trade compare <league-id> <side-a-assets> <side-b-assets> [source]");
        System.out.println("  Assets are comma-separated. Bare IDs are players; use player:<id> or pick:<draft-pick-id>.");
    }

    private static void printDraftPickUsage() {
        System.out.println("Draft picks and full sync:");
        System.out.println("  butler sleeper sync-all <sleeper-league-id>");
        System.out.println("  butler sleeper sync-picks <sleeper-league-id>");
        System.out.println("  butler league draft-pick-values <league-id> dynastyprocess");
        System.out.println("  butler league draft-picks <league-id> [source]");
    }
}
