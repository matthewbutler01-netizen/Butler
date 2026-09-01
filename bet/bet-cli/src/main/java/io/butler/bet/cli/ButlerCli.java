package io.butler.bet.cli;

import io.butler.bet.data.Database;
import io.butler.bet.data.LeagueRepository;
import io.butler.bet.data.PlayerRepository;
import io.butler.bet.data.RosterRepository;
import io.butler.bet.data.TeamRepository;
import io.butler.bet.domain.League;
import io.butler.bet.domain.Player;
import io.butler.bet.domain.Roster;
import io.butler.bet.domain.Team;

import java.nio.file.Path;
import java.sql.SQLException;

public final class ButlerCli {

    private static final String VERSION = "0.1.0-SNAPSHOT";
    private static final Path DATABASE_PATH = Path.of("butler.db");

    private ButlerCli() {}

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
                case "team" -> handleTeam(args);
                case "player" -> handlePlayer(args);
                case "roster" -> handleRoster(args);
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
        if (args.length == 2 && args[1].equalsIgnoreCase("seed")) {
            seedDemoData();
            return;
        }
        System.out.println("Usage: butler db <init|seed>");
    }

    private static void handleLeague(String[] args) throws SQLException {
        Database database = initializedDatabase();
        LeagueRepository leagues = new LeagueRepository(database);

        if (args.length >= 3 && args[1].equalsIgnoreCase("add")) {
            League league = League.create(joinArgs(args, 2));
            leagues.save(league);
            System.out.println("Created league: " + league.getName());
            System.out.println("ID: " + league.getId());
            return;
        }
        if (args.length == 2 && args[1].equalsIgnoreCase("list")) {
            var all = leagues.findAll();
            if (all.isEmpty()) System.out.println("No leagues found.");
            else all.forEach(league -> System.out.println(league.getId() + "  " + league.getName()));
            return;
        }
        System.out.println("Usage: butler league <add <name>|list>");
    }

    private static void handleTeam(String[] args) throws SQLException {
        if (args.length == 3 && args[1].equalsIgnoreCase("list")) {
            TeamRepository teams = new TeamRepository(initializedDatabase());
            var all = teams.findByLeagueId(args[2]);
            if (all.isEmpty()) System.out.println("No teams found for league " + args[2]);
            else all.forEach(team -> System.out.println(team.getId() + "  " + team.getName()));
            return;
        }
        System.out.println("Usage: butler team list <league-id>");
    }

    private static void handlePlayer(String[] args) throws SQLException {
        if (args.length == 2 && args[1].equalsIgnoreCase("list")) {
            PlayerRepository players = new PlayerRepository(initializedDatabase());
            var all = players.findAll();
            if (all.isEmpty()) System.out.println("No players found.");
            else all.forEach(player -> System.out.println(player.getId() + "  " + player.getPosition() + "  " + player.getDisplayName() + formatTeam(player.getNflTeam())));
            return;
        }
        System.out.println("Usage: butler player list");
    }

    private static void handleRoster(String[] args) throws SQLException {
        if (args.length == 3 && args[1].equalsIgnoreCase("list")) {
            Database database = initializedDatabase();
            RosterRepository rosters = new RosterRepository(database);
            PlayerRepository players = new PlayerRepository(database);
            var memberships = rosters.findByTeamId(args[2]);
            if (memberships.isEmpty()) {
                System.out.println("No roster entries found for team " + args[2]);
                return;
            }
            for (Roster membership : memberships) {
                Player player = players.findById(membership.getPlayerId()).orElseThrow();
                System.out.println(membership.getSlot() + "  " + player.getPosition() + "  " + player.getDisplayName() + formatTeam(player.getNflTeam()));
            }
            return;
        }
        System.out.println("Usage: butler roster list <team-id>");
    }

    private static void seedDemoData() throws SQLException {
        Database database = initializedDatabase();
        LeagueRepository leagues = new LeagueRepository(database);
        TeamRepository teams = new TeamRepository(database);
        PlayerRepository players = new PlayerRepository(database);
        RosterRepository rosters = new RosterRepository(database);

        League league = new League("demo-league", "demo-sleeper-league", "Butler Demo Dynasty");
        Team team = new Team("demo-team", "demo-sleeper-roster", league.getId(), "The Butler");
        Player quarterback = new Player("demo-player-qb", "demo-sleeper-qb", "Demo Quarterback", "QB", "CHI");
        Player receiver = new Player("demo-player-wr", "demo-sleeper-wr", "Demo Receiver", "WR", "KC");

        leagues.save(league);
        teams.save(team);
        players.save(quarterback);
        players.save(receiver);
        rosters.save(new Roster("demo-roster-qb", null, team.getId(), quarterback.getId(), "STARTER"));
        rosters.save(new Roster("demo-roster-wr", null, team.getId(), receiver.getId(), "STARTER"));

        System.out.println("Seeded demo fantasy data.");
        System.out.println("League ID: " + league.getId());
        System.out.println("Team ID: " + team.getId());
    }

    private static Database initializedDatabase() throws SQLException {
        Database database = database();
        database.initialize();
        return database;
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

    private static String formatTeam(String nflTeam) {
        return nflTeam == null ? "" : "  " + nflTeam;
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
        System.out.println("  butler db seed");
        System.out.println("  butler league add <name>");
        System.out.println("  butler league list");
        System.out.println("  butler team list <league-id>");
        System.out.println("  butler player list");
        System.out.println("  butler roster list <team-id>");
    }
}
