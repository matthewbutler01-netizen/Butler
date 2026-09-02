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
import io.butler.bet.intelligence.DynastyProcessRefreshReadiness;
import io.butler.bet.intelligence.DynastyProcessValueImporter;
import io.butler.bet.intelligence.LeagueAnalyzer;
import io.butler.bet.intelligence.LeagueValueCoverageAnalyzer;
import io.butler.bet.intelligence.LeagueValueMoverAnalyzer;
import io.butler.bet.intelligence.PlayerValueChangeAnalyzer;
import io.butler.bet.intelligence.PlayerValueImporter;
import io.butler.bet.intelligence.SourceValueMoverAnalyzer;
import io.butler.bet.intelligence.TeamStrengthAnalyzer;
import io.butler.bet.intelligence.TeamValueMovementAnalyzer;
import io.butler.bet.sleeper.SleeperLeagueImporter;

import java.io.IOException;
import java.nio.file.Path;
import java.sql.SQLException;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
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

    private static void handleDatabase(String[] args) throws SQLException {
        if (args.length == 2 && args[1].equalsIgnoreCase("init")) {
            database().initialize();
            System.out.println("Initialized Butler database: " + DATABASE_PATH.toAbsolutePath());
            return;
        }
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
        if (args.length == 3 && args[1].equalsIgnoreCase("analyze")) {
            printLeagueReport(new LeagueAnalyzer(database).analyze(args[2]));
            return;
        }
        if ((args.length == 3 || args.length == 4) && args[1].equalsIgnoreCase("value-sources")) {
            LeagueValueCoverageAnalyzer analyzer = new LeagueValueCoverageAnalyzer(database);
            var report = args.length == 4
                ? analyzer.analyze(args[2], parseDate(args[3], "minimum-as-of-date"))
                : analyzer.analyze(args[2]);
            printLeagueValueCoverage(report);
            return;
        }
        if (args.length == 4 && args[1].equalsIgnoreCase("value-movers")) {
            var report = new LeagueValueMoverAnalyzer(database).analyze(args[2], args[3]);
            System.out.println("League player value movers");
            System.out.println("League ID: " + report.leagueId());
            System.out.println("Source: " + report.source());
            if (report.movers().isEmpty()) {
                System.out.println("No rostered players have at least two persisted snapshots for this source.");
                return;
            }
            for (var mover : report.movers()) {
                System.out.printf("%+.2f  %s  %s%s  team=%s  %.2f@%s -> %.2f@%s  [%s]%n",
                    mover.delta(), mover.position(), mover.playerName(), formatTeam(mover.nflTeam()), mover.teamName(),
                    mover.previousValue(), mover.previousDate(), mover.latestValue(), mover.latestDate(), mover.playerId());
            }
            return;
        }
        if (args.length == 4 && args[1].equalsIgnoreCase("team-movement")) {
            var report = new TeamValueMovementAnalyzer(database).analyze(args[2], args[3]);
            System.out.println("League team value movement");
            System.out.println("League ID: " + report.leagueId());
            System.out.println("Source: " + report.source());
            if (report.teams().isEmpty()) {
                System.out.println("No teams found for this league.");
                return;
            }
            for (var team : report.teams()) {
                System.out.printf("%+.2f  %s  coverage=%d/%d (%.1f%%)  missing-history=%d  risers=%d  fallers=%d  unchanged=%d  [%s]%n",
                    team.delta(), team.teamName(), team.playersWithHistory(), team.rosterSize(), team.historyCoveragePercent(),
                    team.playersWithoutHistory(), team.risers(), team.fallers(), team.unchanged(), team.teamId());
            }
            return;
        }
        if ((args.length == 4 || args.length == 5) && args[1].equalsIgnoreCase("rank")) {
            TeamStrengthAnalyzer analyzer = new TeamStrengthAnalyzer(database);
            var report = args.length == 5
                ? analyzer.rank(args[2], args[3], parseDate(args[4], "minimum-as-of-date"))
                : analyzer.rank(args[2], args[3]);
            printStrengthReport(report);
            return;
        }
        System.out.println("Usage: butler league <add <name>|list|analyze <league-id>|value-sources <league-id> [minimum-as-of-date]|value-movers <league-id> <source>|team-movement <league-id> <source>|rank <league-id> <source> [minimum-as-of-date]>");
    }

    private static void printLeagueReport(LeagueAnalyzer.LeagueReport report) {
        System.out.println("League analysis");
        System.out.println("Teams: " + report.teamCount());
        System.out.println("Rostered players: " + report.rosteredPlayers());
        System.out.println("Positions: " + formatCounts(report.positionCounts()));
        for (LeagueAnalyzer.TeamReport team : report.teams()) {
            System.out.println();
            System.out.println(team.teamName() + "  [" + team.teamId() + "]");
            System.out.println("  Roster: " + team.rosterSize());
            System.out.println("  Positions: " + formatCounts(team.positionCounts()));
            System.out.println("  Slots: " + formatCounts(team.slotCounts()));
        }
    }

    private static void printLeagueValueCoverage(LeagueValueCoverageAnalyzer.CoverageReport report) {
        System.out.println("League player-value source coverage");
        System.out.println("League ID: " + report.leagueId());
        if (report.minimumAsOfDate() != null) System.out.println("Minimum as-of: " + report.minimumAsOfDate());
        if (report.sources().isEmpty()) { System.out.println("No persisted player value sources found."); return; }
        for (var source : report.sources()) {
            String dates = source.oldestValueDate() == null ? "none"
                : source.oldestValueDate().equals(source.latestValueDate())
                    ? source.latestValueDate().toString()
                    : source.oldestValueDate() + " to " + source.latestValueDate();
            System.out.printf("%s  status=%s  coverage=%d/%d (%.1f%%)  uncovered-teams=%d  dates=%s%n",
                source.source(), source.readiness(), source.valuedPlayers(), source.totalPlayers(),
                source.coveragePercent(), source.uncoveredTeams(), dates);
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
            System.out.printf("   Coverage: %d/%d (%.1f%%)  Missing values: %d  Composition tie-breaker: %.2f%n",
                team.valuedPlayers(), team.totalPlayers(), team.coveragePercent(), team.missingValues(), team.compositionScore());
            System.out.println("   Positions: " + formatCounts(team.positionCounts()));
            System.out.println("   Slots: " + formatCounts(team.slotCounts()));
        }
    }

    private static String formatCounts(Map<String, Integer> counts) {
        if (counts.isEmpty()) return "none";
        StringBuilder value = new StringBuilder();
        new TreeMap<>(counts).forEach((key, count) -> {
            if (!value.isEmpty()) value.append(", ");
            value.append(key).append('=').append(count);
        });
        return value.toString();
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

    private static void handlePlayer(String[] args) throws SQLException, IOException, InterruptedException {
        Database database = initializedDatabase();
        PlayerRepository players = new PlayerRepository(database);
        if (args.length == 2 && args[1].equalsIgnoreCase("list")) {
            var all = players.findAll();
            if (all.isEmpty()) System.out.println("No players found.");
            else all.forEach(player -> System.out.println(player.getId() + "  " + player.getPosition() + "  " + player.getDisplayName() + formatTeam(player.getNflTeam())));
            return;
        }
        if (args.length == 2 && args[1].equalsIgnoreCase("value-sources")) {
            var summaries = new PlayerValueRepository(database).findSourceSummaries();
            if (summaries.isEmpty()) { System.out.println("No persisted player value sources found."); return; }
            for (var summary : summaries) {
                String dates = summary.oldestAsOfDate().equals(summary.latestAsOfDate())
                    ? summary.latestAsOfDate().toString()
                    : summary.oldestAsOfDate() + " to " + summary.latestAsOfDate();
                System.out.printf("%s  players=%d  dates=%s%n", summary.source(), summary.playerCount(), dates);
            }
            return;
        }
        if (args.length == 3 && args[1].equalsIgnoreCase("value-movers")) {
            var report = new SourceValueMoverAnalyzer(database).analyze(args[2]);
            System.out.println("Player value movers");
            System.out.println("Source: " + report.source());
            if (report.movers().isEmpty()) {
                System.out.println("No players have at least two persisted snapshots for this source.");
                return;
            }
            for (var mover : report.movers()) {
                System.out.printf("%+.2f  %s  %s%s  %.2f@%s -> %.2f@%s  [%s]%n",
                    mover.delta(), mover.position(), mover.playerName(), formatTeam(mover.nflTeam()),
                    mover.previousValue(), mover.previousDate(), mover.latestValue(), mover.latestDate(), mover.playerId());
            }
            return;
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
        if (args.length == 4 && args[1].equalsIgnoreCase("value-change")) {
            Player player = players.findById(args[2]).orElseThrow(() -> new IllegalArgumentException("player not found: " + args[2]));
            var change = new PlayerValueChangeAnalyzer(database).latestChange(player.getId(), args[3]);
            System.out.println("Player value change");
            System.out.println(player.getDisplayName() + "  [" + player.getId() + "]");
            System.out.println("Source: " + args[3].trim());
            if (change.isEmpty()) {
                System.out.println("Need at least two persisted snapshots for this player and source.");
                return;
            }
            var value = change.orElseThrow();
            System.out.printf("Previous: %.2f  as-of=%s%n", value.previousValue(), value.previousDate());
            System.out.printf("Latest: %.2f  as-of=%s%n", value.latestValue(), value.latestDate());
            System.out.printf("Change: %+.2f%n", value.delta());
            return;
        }
        if ((args.length == 3 || args.length == 4) && args[1].equalsIgnoreCase("value-history")) {
            Player player = players.findById(args[2]).orElseThrow(() -> new IllegalArgumentException("player not found: " + args[2]));
            PlayerValueRepository values = new PlayerValueRepository(database);
            var history = args.length == 4
                ? values.findByPlayerIdAndSource(player.getId(), args[3])
                : values.findByPlayerId(player.getId());
            System.out.println("Player value history");
            System.out.println(player.getDisplayName() + "  [" + player.getId() + "]");
            if (history.isEmpty()) { System.out.println("No persisted player value history found."); return; }
            for (var snapshot : history) {
                System.out.printf("%s  %s  %.2f%n", snapshot.getAsOfDate(), snapshot.getSource(), snapshot.getValue());
            }
            return;
        }
        if (args.length == 3 && args[1].equalsIgnoreCase("value-refresh")) {
            if (!args[2].equalsIgnoreCase("dynastyprocess")) {
                throw new IllegalArgumentException("unknown value provider: " + args[2] + ". Supported: dynastyprocess");
            }
            var result = new DynastyProcessValueImporter(database).refresh();
            var diagnostics = result.diagnostics();
            System.out.println("Refreshed player values from DynastyProcess.");
            System.out.println("As-of: " + result.asOfDate());
            System.out.println("Refresh readiness: " + DynastyProcessRefreshReadiness.classify(diagnostics));
            System.out.printf("Provider rows: %d values, %d player IDs%n", diagnostics.valueRows(), diagnostics.playerIdRows());
            System.out.printf("Crosswalk: %d FantasyPros IDs, %d unique exact identities, %d ambiguous exact identities%n",
                diagnostics.primaryCrosswalkEntries(), diagnostics.uniqueIdentityMappings(), diagnostics.ambiguousIdentityMappings());
            System.out.printf("Provider mapping: %d/%d (%.1f%%)  primary=%d  exact-identity=%d  unmapped=%d%n",
                diagnostics.providerRowsMapped(), diagnostics.valueRows(), diagnostics.providerMappingPercent(),
                diagnostics.providerRowsMappedByPrimaryId(), diagnostics.providerRowsMappedByIdentity(), diagnostics.providerRowsUnmapped());
            System.out.println("Eligible local players: " + result.eligiblePlayers());
            System.out.println("Matched players: " + result.matchedPlayers());
            System.out.println("Exact-identity fallback matches: " + result.identityFallbackMatches());
            System.out.println("Unmatched players: " + result.unmatchedPlayers());
            System.out.println("Snapshots imported: " + result.valuesImported());
            System.out.println("Sources: " + DynastyProcessValueImporter.SOURCE_1QB + ", " + DynastyProcessValueImporter.SOURCE_2QB);
            if (!result.unmatched().isEmpty()) {
                System.out.println("Unmatched local players:");
                for (var unmatched : result.unmatched()) {
                    System.out.println("  " + unmatched.playerName() + "  sleeper=" + unmatched.sleeperId() + "  [" + unmatched.playerId() + "]");
                }
            }
            return;
        }
        if (args.length == 3 && args[1].equalsIgnoreCase("value-import")) {
            PlayerValueImporter.ImportResult result = new PlayerValueImporter(database).importJson(Path.of(args[2]));
            System.out.println("Imported player values.");
            System.out.println("Imported: " + result.valuesImported());
            return;
        }
        System.out.println("Usage: butler player <list|value-sources|values <source>|value-movers <source>|value-change <player-id> <source>|value-history <player-id> [source]|value-refresh dynastyprocess|value-import <json-file>>");
    }

    private static void handleRoster(String[] args) throws SQLException {
        if (args.length == 3 && args[1].equalsIgnoreCase("list")) {
            Database database = initializedDatabase();
            RosterRepository rosters = new RosterRepository(database);
            PlayerRepository players = new PlayerRepository(database);
            var memberships = rosters.findByTeamId(args[2]);
            if (memberships.isEmpty()) { System.out.println("No roster entries found for team " + args[2]); return; }
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

    private static LocalDate parseDate(String value, String field) {
        try {
            return LocalDate.parse(value.trim());
        } catch (DateTimeParseException e) {
            throw new IllegalArgumentException(field + " must be an ISO date (YYYY-MM-DD): " + value, e);
        }
    }

    private static Database initializedDatabase() throws SQLException {
        Database database = database();
        database.initialize();
        return database;
    }

    private static Database database() { return new Database(DATABASE_PATH); }

    private static String joinArgs(String[] args, int start) {
        StringBuilder value = new StringBuilder();
        for (int i = start; i < args.length; i++) {
            if (!value.isEmpty()) value.append(' ');
            value.append(args[i]);
        }
        return value.toString();
    }

    private static String formatTeam(String nflTeam) { return nflTeam == null ? "" : "  " + nflTeam; }

    private static void printVersion() {
        System.out.println("Butler Fantasy Football Toolkit");
        System.out.println("Version: " + VERSION);
        System.out.println("Java: " + Runtime.version());
    }

    private static void printHelp() {
        System.out.println("Butler Fantasy Football Toolkit\n\nUsage:");
        System.out.println("  butler version\n  butler help\n  butler db init\n  butler db seed\n  butler sleeper import <sleeper-league-id>\n  butler league add <name>\n  butler league list\n  butler league analyze <league-id>\n  butler league value-sources <league-id> [minimum-as-of-date]\n  butler league value-movers <league-id> <source>\n  butler league team-movement <league-id> <source>\n  butler league rank <league-id> <source> [minimum-as-of-date]\n  butler team list <league-id>\n  butler player list\n  butler player value-sources\n  butler player values <source>\n  butler player value-movers <source>\n  butler player value-change <player-id> <source>\n  butler player value-history <player-id> [source]\n  butler player value-refresh dynastyprocess\n  butler player value-import <json-file>\n  butler roster list <team-id>");
    }
}
