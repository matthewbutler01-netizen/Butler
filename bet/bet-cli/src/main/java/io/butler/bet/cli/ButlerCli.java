package io.butler.bet.cli;

import io.butler.bet.data.Database;
import io.butler.bet.data.LeagueRepository;
import io.butler.bet.domain.League;

import java.nio.file.Path;
import java.sql.SQLException;

public final class ButlerCli {

    private static final String VERSION = "0.1.0-SNAPSHOT";
    private static final Path DATABASE_PATH = Path.of("butler.db");

    private ButlerCli() {
        // Prevent instantiation
    }

    public static void main(String[] args) {
        if (args.length == 0) {
            printHelp();
            return;
        }

        try {
            switch (args[0].toLowerCase()) {
                case "version" -> printVersion();
                case "help" -> printHelp();
                case "db" -> handleDatabase(args);
                case "league" -> handleLeague(args);
                default -> {
                    System.out.println("Unknown command: " + args[0]);
                    System.out.println();
                    printHelp();
                }
            }
        } catch (SQLException e) {
            System.err.println("Database error: " + e.getMessage());
            System.exit(1);
        } catch (IllegalArgumentException e) {
            System.err.println("Error: " + e.getMessage());
            System.exit(2);
        }
    }

    private static void handleDatabase(String[] args) throws SQLException {
        if (args.length == 2 && args[1].equalsIgnoreCase("init")) {
            database().initialize();
            System.out.println("Initialized Butler database: " + DATABASE_PATH.toAbsolutePath());
            return;
        }
        System.out.println("Usage: butler db init");
    }

    private static void handleLeague(String[] args) throws SQLException {
        Database database = database();
        database.initialize();
        LeagueRepository leagues = new LeagueRepository(database);

        if (args.length >= 3 && args[1].equalsIgnoreCase("add")) {
            String name = joinArgs(args, 2);
            League league = League.create(name);
            leagues.save(league);
            System.out.println("Created league: " + league.getName());
            System.out.println("ID: " + league.getId());
            return;
        }

        if (args.length == 2 && args[1].equalsIgnoreCase("list")) {
            var all = leagues.findAll();
            if (all.isEmpty()) {
                System.out.println("No leagues found.");
                return;
            }
            for (League league : all) {
                System.out.println(league.getId() + "  " + league.getName());
            }
            return;
        }

        System.out.println("Usage:");
        System.out.println("  butler league add <name>");
        System.out.println("  butler league list");
    }

    private static Database database() {
        return new Database(DATABASE_PATH);
    }

    private static String joinArgs(String[] args, int start) {
        StringBuilder value = new StringBuilder();
        for (int i = start; i < args.length; i++) {
            if (!value.isEmpty()) value.append(' ');
            value.append(args[i]);
        }
        return value.toString();
    }

    private static void printVersion() {
        System.out.println("Butler Fantasy Football Toolkit");
        System.out.println("Version: " + VERSION);
        System.out.println("Java: " + Runtime.version());
    }

    private static void printHelp() {
        System.out.println("Butler Fantasy Football Toolkit");
        System.out.println();
        System.out.println("Usage:");
        System.out.println("  butler version");
        System.out.println("  butler help");
        System.out.println("  butler db init");
        System.out.println("  butler league add <name>");
        System.out.println("  butler league list");
    }
}
