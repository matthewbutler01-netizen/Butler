package io.butler.bet.cli;

import io.butler.bet.data.Database;
import io.butler.bet.intelligence.TradeAssetAnalyzer;

import java.nio.file.Path;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;

/**
 * Application entry point that preserves the existing Butler CLI while extending trade comparison
 * to mixed player and draft-pick packages.
 */
public final class ButlerApp {
    private static final Path DATABASE_PATH = Path.of("butler.db");

    private ButlerApp() {}

    public static void main(String[] args) {
        if (args.length == 0) {
            ButlerCli.main(args);
            printTradeExtensionHelp();
            return;
        }
        if (args[0].equalsIgnoreCase("help")) {
            ButlerCli.main(args);
            printTradeExtensionHelp();
            return;
        }
        if (!args[0].equalsIgnoreCase("trade")) {
            ButlerCli.main(args);
            return;
        }

        try {
            handleTrade(args);
        } catch (SQLException e) {
            System.err.println("Database error: " + e.getMessage());
            System.exit(1);
        } catch (IllegalArgumentException e) {
            System.err.println("Error: " + e.getMessage());
            System.exit(2);
        }
    }

    static void handleTrade(String[] args) throws SQLException {
        if ((args.length == 5 || args.length == 6) && args[1].equalsIgnoreCase("compare")) {
            Database database = new Database(DATABASE_PATH);
            database.initialize();
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

    private static String formatTeam(String nflTeam) {
        return nflTeam == null ? "" : "  " + nflTeam;
    }

    private static void printTradeExtensionHelp() {
        System.out.println();
        printTradeUsage();
    }

    private static void printTradeUsage() {
        System.out.println("Mixed trade assets:");
        System.out.println("  butler trade compare <league-id> <side-a-assets> <side-b-assets> [source]");
        System.out.println("  Assets are comma-separated. Bare IDs are players; use player:<id> or pick:<draft-pick-id>.");
    }
}
