package io.butler.bet.cli;

import io.butler.bet.data.Database;
import io.butler.bet.data.LeagueRepository;
import io.butler.bet.data.PlayerRepository;
import io.butler.bet.data.PlayerValueRepository;
import io.butler.bet.data.RosterRepository;
import io.butler.bet.data.TeamRepository;
import io.butler.bet.domain.League;
import io.butler.bet.domain.Player;
import io.butler.bet.domain.Roster;
import io.butler.bet.domain.Team;
import io.butler.bet.intelligence.LeagueAnalyzer;
import io.butler.bet.intelligence.PlayerValueImporter;
import io.butler.bet.intelligence.TeamStrengthAnalyzer;
import io.butler.bet.sleeper.SleeperLeagueImporter;

import java.io.IOException;
import java.nio.file.Path;
import java.sql.SQLException;
import java.util.Map;
import java.util.TreeMap;

public final class ButlerCli {
    private static final String VERSION = "0.1.0-SNAPSHOT";
    private static final Path DATABASE_PATH = Path.of("butler.db");
    private ButlerCli() {}

    public static void main(String[] args) {
        if (args.length == 0) { printHelp(); return; }
        try {
            switch (args[0].toLowerCase()) {
                case "version" -> printVersion();
                case "help" -> printHelp();
                case "db" -> handleDatabase(args);
                case "league" -> handleLeague(args);
                case "team" -> handleTeam(args);
                case "player" -> handlePlayer(args);
                case "roster" -> handleRoster(args);
                case "sleeper" -> handleSleeper(args);
                default -> { System.out.println("Unknown command: " + args[0]); System.out.println(); printHelp(); }
            }
        } catch (SQLException e) { System.err.println("Database error: " + e.getMessage()); System.exit(1);
        } catch (IOException e) { System.err.println("I/O error: " + e.getMessage()); System.exit(3);
        } catch (InterruptedException e) { Thread.currentThread().interrupt(); System.err.println("Sleeper import interrupted."); System.exit(4);
        } catch (IllegalArgumentException e) { System.err.println("Error: " + e.getMessage()); System.exit(2); }
    }

    private static void handleDatabase(String[] args) throws SQLException {
        if (args.length == 2 && args[1].equalsIgnoreCase("init")) { database().initialize(); System.out.println("Initialized Butler database: " + DATABASE_PATH.toAbsolutePath()); return; }
        if (args.length == 2 && args[1].equalsIgnoreCase("seed")) { seedDemoData(); return; }
        System.out.println("Usage: butler db <init|seed>");
    }

    private static void handleSleeper(String[] args) throws SQLException, IOException, InterruptedException {
        if (args.length == 3 && args[1].equalsIgnoreCase("import")) {
            SleeperLeagueImporter.ImportResult result = new SleeperLeagueImporter(initializedDatabase()).importLeague(args[2]);
            System.out.println("Imported Sleeper league.");
            System.out.println("League ID: " + result.leagueId());
            System.out.println("Teams: " + result.teamsImported());
            System.out.println("Players: " + result.playersImported());
            System.out.println("Roster entries: " + result.rosterEntriesImported());
            return;
        }
        System.out.println("Usage: butler sleeper import <sleeper-league-id>");
    }

    private static void handleLeague(String[] args) throws SQLException {
        Database database = initializedDatabase();
        LeagueRepository leagues = new LeagueRepository(database);
        if (args.length >= 3 && args[1].equalsIgnoreCase("add")) {
            League league = League.create(joinArgs(args, 2)); leagues.save(league);
            System.out.println("Created league: " + league.getName()); System.out.println("ID: " + league.getId()); return;
        }
        if (args.length == 2 && args[1].equalsIgnoreCase("list")) {
            var all = leagues.findAll(); if (all.isEmpty()) System.out.println("No leagues found."); else all.forEach(league -> System.out.println(league.getId() + "  " + league.getName())); return;
        }
        if (args.length == 3 && args[1].equalsIgnoreCase("analyze")) { printLeagueReport(new LeagueAnalyzer(database).analyze(args[2])); return; }
        if (args.length == 4 && args[1].equalsIgnoreCase("rank")) { printStrengthReport(new TeamStrengthAnalyzer(database).rank(args[2], args[3])); return; }
        System.out.println("Usage: butler league <add <name>|list|analyze <league-id>|rank <league-id> <source>>");
    }

    private static void printLeagueReport(LeagueAnalyzer.LeagueReport report) {
        System.out.println("League analysis");
        System.out.println("Teams: " + report.teamCount());
        System.out.println("Rostered players: " + report.rosteredPlayers());
        System.out.println("Positions: " + formatCounts(report.positionCounts()));
        for (LeagueAnalyzer.TeamReport team : report.teams()) {
            System.out.println(); System.out.println(team.teamName() + "  [" + team.teamId() + "]");
            System.out.println("  Roster: " + team.rosterSize());
            System.out.println("  Positions: " + formatCounts(team.positionCounts()));
            System.out.println("  Slots: " + formatCounts(team.slotCounts()));
        }
    }

    private static void printStrengthReport(TeamStrengthAnalyzer.StrengthReport report) {
        System.out.println("Team strength rankings");
        System.out.println("Value source: " + report.source());
        if (report.teams().isEmpty()) { System.out.println("No teams found."); return; }
        System.out.printf("Value coverage: %d/%d (%.1f%%)%n", report.valuedPlayers(), report.totalPlayers(), report.coveragePercent());
        if (report.oldestValueDate().equals(report.latestValueDate())) {
            System.out.println("Value as-of: " + report.latestValueDate());
        } else {
            System.out.println("Value dates: " + report.oldestValueDate() + " to " + report.latestValueDate());
        }
        for (TeamStrengthAnalyzer.TeamStrength team : report.teams()) {
            System.out.printf("%d. %s  value=%.2f  [%s]%n", team.rank(), team.teamName(), team.playerValue(), team.teamId());
            System.out.printf("   Valued players: %d  Missing values: %d  Composition tie-breaker: %.2f%n", team.valuedPlayers(), team.missingValues(), team.compositionScore());
            System.out.println("   Positions: " + formatCounts(team.positionCounts()));
            System.out.println("   Slots: " + formatCounts(team.slotCounts()));
        }
    }

    private static String formatCounts(Map<String, Integer> counts) {
        if (counts.isEmpty()) return "none";
        StringBuilder value = new StringBuilder();
        new TreeMap<>(counts).forEach((key, count) -> { if (!value.isEmpty()) value.append(", "); value.append(key).append('=').append(count); });
        return value.toString();
    }

    private static void handleTeam(String[] args) throws SQLException {
        if (args.length == 3 && args[1].equalsIgnoreCase("list")) {
            TeamRepository teams = new TeamRepository(initializedDatabase()); var all = teams.findByLeagueId(args[2]);
            if (all.isEmpty()) System.out.println("No teams found for league " + args[2]); else all.forEach(team -> System.out.println(team.getId() + "  " + team.getName())); return;
        }
        System.out.println("Usage: butler team list <league-id>");
    }

    private static void handlePlayer(String[] args) throws SQLException, IOException {
        Database database = initializedDatabase();
        PlayerRepository players = new PlayerRepository(database);
        if (args.length == 2 && args[1].equalsIgnoreCase("list")) {
            var all = players.findAll(); if (all.isEmpty()) System.out.println("No players found."); else all.forEach(player -> System.out.println(player.getId() + "  " + player.getPosition() + "  " + player.getDisplayName() + formatTeam(player.getNflTeam()))); return;
        }
        if (args.length == 3 && args[1].equalsIgnoreCase("values")) {
            PlayerValueRepository values = new PlayerValueRepository(database);
            var snapshots = values.findLatestBySource(args[2]);
            if (snapshots.isEmpty()) { System.out.println("No persisted player values found for source " + args[2] + "."); return; }
            for (var snapshot : snapshots) {
                Player player = players.findById(snapshot.getPlayerId()).orElseThrow();
                System.out.printf("%.2f  %s  %s  as-of=%s  [%s]%n", snapshot.getValue(), player.getPosition(), player.getDisplayName(), snapshot.getAsOfDate(), player.getId());
            }
            return;
        }
        if (args.length == 3 && args[1].equalsIgnoreCase("value-import")) {
            PlayerValueImporter.ImportResult result = new PlayerValueImporter(database).importJson(Path.of(args[2]));
            System.out.println("Imported player values.");
            System.out.println("Imported: " + result.valuesImported());
            return;
        }
        System.out.println("Usage: butler player <list|values <source>|value-import <json-file>>");
    }

    private static void handleRoster(String[] args) throws SQLException {
        if (args.length == 3 && args[1].equalsIgnoreCase("list")) {
            Database database = initializedDatabase(); RosterRepository rosters = new RosterRepository(database); PlayerRepository players = new PlayerRepository(database); var memberships = rosters.findByTeamId(args[2]);
            if (memberships.isEmpty()) { System.out.println("No roster entries found for team " + args[2]); return; }
            for (Roster membership : memberships) { Player player = players.findById(membership.getPlayerId()).orElseThrow(); System.out.println(membership.getSlot() + "  " + player.getPosition() + "  " + player.getDisplayName() + formatTeam(player.getNflTeam())); }
            return;
        }
        System.out.println("Usage: butler roster list <team-id>");
    }

    private static void seedDemoData() throws SQLException {
        Database database = initializedDatabase(); LeagueRepository leagues = new LeagueRepository(database); TeamRepository teams = new TeamRepository(database); PlayerRepository players = new PlayerRepository(database); RosterRepository rosters = new RosterRepository(database);
        League league = new League("demo-league", "demo-sleeper-league", "Butler Demo Dynasty"); Team team = new Team("demo-team", "demo-sleeper-roster", league.getId(), "The Butler"); Player quarterback = new Player("demo-player-qb", "demo-sleeper-qb", "Demo Quarterback", "QB", "CHI"); Player receiver = new Player("demo-player-wr", "demo-sleeper-wr", "Demo Receiver", "WR", "KC");
        leagues.save(league); teams.save(team); players.save(quarterback); players.save(receiver); rosters.save(new Roster("demo-roster-qb", null, team.getId(), quarterback.getId(), "STARTER")); rosters.save(new Roster("demo-roster-wr", null, team.getId(), receiver.getId(), "STARTER"));
        System.out.println("Seeded demo fantasy data."); System.out.println("League ID: " + league.getId()); System.out.println("Team ID: " + team.getId());
    }

    private static Database initializedDatabase() throws SQLException { Database database = database(); database.initialize(); return database; }
    private static Database database() { return new Database(DATABASE_PATH); }
    private static String joinArgs(String[] args, int start) { StringBuilder value = new StringBuilder(); for (int i = start; i < args.length; i++) { if (!value.isEmpty()) value.append(' '); value.append(args[i]); } return value.toString(); }
    private static String formatTeam(String nflTeam) { return nflTeam == null ? "" : "  " + nflTeam; }
    private static void printVersion() { System.out.println("Butler Fantasy Football Toolkit"); System.out.println("Version: " + VERSION); System.out.println("Java: " + Runtime.version()); }
    private static void printHelp() {
        System.out.println("Butler Fantasy Football Toolkit\n\nUsage:");
        System.out.println("  butler version\n  butler help\n  butler db init\n  butler db seed\n  butler sleeper import <sleeper-league-id>\n  butler league add <name>\n  butler league list\n  butler league analyze <league-id>\n  butler league rank <league-id> <source>\n  butler team list <league-id>\n  butler player list\n  butler player values <source>\n  butler player value-import <json-file>\n  butler roster list <team-id>");
    }
}
