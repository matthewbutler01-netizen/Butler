package io.butler.bet.cli;

import io.butler.bet.data.Database;
import io.butler.bet.data.DraftPickRepository;
import io.butler.bet.data.DraftPickValueRepository;
import io.butler.bet.data.TeamRepository;
import io.butler.bet.intelligence.DynastyProcessDraftPickValueImporter;
import io.butler.bet.intelligence.DynastyProcessValueImporter;
import io.butler.bet.intelligence.FranchiseValueRankingAnalyzer;
import io.butler.bet.intelligence.FranchiseValueReadinessAnalyzer;
import io.butler.bet.intelligence.LeagueAssetInventoryAnalyzer;
import io.butler.bet.intelligence.LeagueAssetSearchAnalyzer;
import io.butler.bet.intelligence.LeagueValueSourceResolver;
import io.butler.bet.intelligence.TeamAssetPortfolioAnalyzer;
import io.butler.bet.intelligence.TradeAssetAnalyzer;
import io.butler.bet.sleeper.SleeperDraftPickImporter;
import io.butler.bet.sleeper.SleeperLeagueImporter;

import java.io.IOException;
import java.nio.file.Path;
import java.sql.SQLException;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
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
            || args[1].equalsIgnoreCase("draft-pick-values")
            || args[1].equalsIgnoreCase("portfolio")
            || args[1].equalsIgnoreCase("franchise-rank")
            || args[1].equalsIgnoreCase("franchise-readiness")
            || args[1].equalsIgnoreCase("assets")
            || args[1].equalsIgnoreCase("asset-search"));
    }

    static void handleTrade(String[] args) throws SQLException {
        if (args.length < 5 || !args[1].equalsIgnoreCase("compare")) {
            printTradeUsage();
            return;
        }

        Database database = initializedDatabase();
        TradeAssetAnalyzer analyzer = new TradeAssetAnalyzer(database);
        TradeAssetAnalyzer.TradePackage sideA = parseTradePackage(args[3], "side-a-assets");
        TradeAssetAnalyzer.TradePackage sideB = parseTradePackage(args[4], "side-b-assets");

        TradeAssetAnalyzer.TradeReport report;
        if (args.length == 5) {
            report = analyzer.analyze(args[2], sideA, sideB);
        } else if (args.length == 6) {
            report = analyzer.analyze(args[2], sideA, sideB, args[5]);
        } else if (args.length == 7 && args[5].equalsIgnoreCase("--minimum-as-of")) {
            report = analyzer.analyze(args[2], sideA, sideB, parseMinimumAsOfDate(args[6]));
        } else if (args.length == 8 && args[6].equalsIgnoreCase("--minimum-as-of")) {
            report = analyzer.analyze(args[2], sideA, sideB, args[5], parseMinimumAsOfDate(args[7]));
        } else {
            printTradeUsage();
            return;
        }
        printTradeReport(report);
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
        if ((args.length == 4 || args.length == 5) && args[1].equalsIgnoreCase("asset-search")) {
            printAssetSearch(args[2], args[3], args.length == 5 ? args[4] : null);
            return;
        }
        if ((args.length == 3 || args.length == 4) && args[1].equalsIgnoreCase("assets")) {
            printLeagueAssets(args[2], args.length == 4 ? args[3] : null);
            return;
        }
        if (args[1].equalsIgnoreCase("franchise-readiness")) {
            handleFranchiseReadiness(args);
            return;
        }
        if (args[1].equalsIgnoreCase("franchise-rank")) {
            handleFranchiseRank(args);
            return;
        }
        if ((args.length == 3 || args.length == 4) && args[1].equalsIgnoreCase("portfolio")) {
            printTeamPortfolio(args[2], args.length == 4 ? args[3] : null);
            return;
        }
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

    private static void handleFranchiseReadiness(String[] args) throws SQLException {
        if (args.length == 3) {
            printFranchiseReadiness(args[2], null, null);
            return;
        }
        if (args.length == 4) {
            printFranchiseReadiness(args[2], args[3], null);
            return;
        }
        if (args.length == 5 && args[3].equalsIgnoreCase("--minimum-as-of")) {
            printFranchiseReadiness(args[2], null, parseMinimumAsOfDate(args[4]));
            return;
        }
        if (args.length == 6 && args[4].equalsIgnoreCase("--minimum-as-of")) {
            printFranchiseReadiness(args[2], args[3], parseMinimumAsOfDate(args[5]));
            return;
        }
        printDraftPickUsage();
    }

    private static void handleFranchiseRank(String[] args) throws SQLException {
        if (args.length == 3) {
            printFranchiseRankings(args[2], null, null);
            return;
        }
        if (args.length == 4) {
            printFranchiseRankings(args[2], args[3], null);
            return;
        }
        if (args.length == 5 && args[3].equalsIgnoreCase("--minimum-as-of")) {
            printFranchiseRankings(args[2], null, parseMinimumAsOfDate(args[4]));
            return;
        }
        if (args.length == 6 && args[4].equalsIgnoreCase("--minimum-as-of")) {
            printFranchiseRankings(args[2], args[3], parseMinimumAsOfDate(args[5]));
            return;
        }
        printDraftPickUsage();
    }

    private static void printFranchiseReadiness(String leagueId, String sourceOverride,
                                                LocalDate minimumAsOfDate) throws SQLException {
        Database database = initializedDatabase();
        FranchiseValueReadinessAnalyzer analyzer = new FranchiseValueReadinessAnalyzer(database);
        var report = minimumAsOfDate == null
            ? (sourceOverride == null ? analyzer.analyze(leagueId) : analyzer.analyze(leagueId, sourceOverride))
            : (sourceOverride == null
                ? analyzer.analyze(leagueId, minimumAsOfDate)
                : analyzer.analyze(leagueId, sourceOverride, minimumAsOfDate));

        System.out.println("Franchise value readiness");
        System.out.println("League ID: " + report.leagueId());
        System.out.println("Source: " + report.source());
        if (report.minimumAsOfDate() != null) {
            System.out.println("Minimum as-of: " + report.minimumAsOfDate());
        }
        System.out.println("Value dates: " + valueDates(report.oldestValueDate(), report.latestValueDate()));
        System.out.println("Status: " + report.status());
        System.out.println("Rankable: " + report.rankable());
        System.out.printf("Coverage: %d/%d assets (%.1f%%)  missing-players=%d  missing-picks=%d  stale=%d%n",
            report.valuedAssets(), report.totalAssets(), report.coveragePercent(),
            report.missingPlayers(), report.missingDraftPicks(), report.staleAssets());

        for (var team : report.teams()) {
            System.out.printf("%s  status=%s  rankable=%s  coverage=%d/%d (%.1f%%)  missing-players=%d  missing-picks=%d  stale=%d  dates=%s  [%s]%n",
                team.teamName(), team.status(), team.rankable(), team.valuedAssets(), team.totalAssets(),
                team.coveragePercent(), team.missingPlayers(), team.missingDraftPicks(), team.staleAssets(),
                valueDates(team.oldestValueDate(), team.latestValueDate()), team.teamId());
        }

        if (report.missingAssets() == 0) {
            System.out.println("No current asset values are missing.");
        } else {
            if (!report.missingPlayerAssets().isEmpty()) {
                System.out.println("Missing player values:");
                for (var player : report.missingPlayerAssets()) {
                    System.out.printf("  %s  %s  %s%s  slot=%s  [%s]%n",
                        player.teamName(), player.position(), player.playerName(), formatTeam(player.nflTeam()),
                        player.slot(), player.playerId());
                }
            }
            if (!report.missingDraftPickAssets().isEmpty()) {
                System.out.println("Missing draft-pick values:");
                for (var pick : report.missingDraftPickAssets()) {
                    String original = pick.originalTeamName().equals(pick.teamName())
                        ? "" : "  original=" + pick.originalTeamName();
                    String slot = pick.pickNumber() == null ? "" : "  slot=" + pick.pickNumber();
                    System.out.printf("  %s  %s%s%s  [%s]%n",
                        pick.teamName(), pick.label(), original, slot, pick.draftPickId());
                }
            }
        }

        if (report.minimumAsOfDate() == null) {
            return;
        }
        if (report.staleAssets() == 0) {
            System.out.println("No valued assets are older than the minimum as-of date.");
            return;
        }
        if (!report.stalePlayerAssets().isEmpty()) {
            System.out.println("Stale player values:");
            for (var player : report.stalePlayerAssets()) {
                System.out.printf("  %s  %s  %s%s  slot=%s  as-of=%s  [%s]%n",
                    player.teamName(), player.position(), player.playerName(), formatTeam(player.nflTeam()),
                    player.slot(), player.asOfDate(), player.playerId());
            }
        }
        if (!report.staleDraftPickAssets().isEmpty()) {
            System.out.println("Stale draft-pick values:");
            for (var pick : report.staleDraftPickAssets()) {
                String original = pick.originalTeamName().equals(pick.teamName())
                    ? "" : "  original=" + pick.originalTeamName();
                String slot = pick.pickNumber() == null ? "" : "  slot=" + pick.pickNumber();
                System.out.printf("  %s  %s%s%s  as-of=%s  [%s]%n",
                    pick.teamName(), pick.label(), original, slot, pick.asOfDate(), pick.draftPickId());
            }
        }
    }

    private static void printAssetSearch(String leagueId, String query, String sourceOverride) throws SQLException {
        Database database = initializedDatabase();
        LeagueAssetSearchAnalyzer analyzer = new LeagueAssetSearchAnalyzer(database);
        var report = sourceOverride == null
            ? analyzer.search(leagueId, query)
            : analyzer.search(leagueId, query, sourceOverride);

        System.out.println("League asset search");
        System.out.println("League ID: " + report.leagueId());
        System.out.println("Source: " + report.source());
        System.out.println("Query: " + report.query());
        System.out.println("Matches: " + report.totalMatches());
        if (report.totalMatches() == 0) {
            System.out.println("No current league assets matched this query.");
            return;
        }
        for (var player : report.players()) {
            if (player.valued()) {
                System.out.printf("PLAYER  %.2f  %s  %s%s  team=%s  slot=%s  as-of=%s  [%s]%n",
                    player.value(), player.position(), player.playerName(), formatTeam(player.nflTeam()),
                    player.teamName(), player.slot(), player.asOfDate(), player.playerId());
            } else {
                System.out.printf("PLAYER  MISSING  %s  %s%s  team=%s  slot=%s  [%s]%n",
                    player.position(), player.playerName(), formatTeam(player.nflTeam()),
                    player.teamName(), player.slot(), player.playerId());
            }
        }
        for (var pick : report.draftPicks()) {
            String original = pick.originalTeamName().equals(pick.teamName())
                ? "" : "  original=" + pick.originalTeamName();
            String slot = pick.pickNumber() == null ? "" : "  slot=" + pick.pickNumber();
            if (pick.valued()) {
                System.out.printf("PICK  %.2f  %s  team=%s%s%s  as-of=%s  [%s]%n",
                    pick.value(), pick.label(), pick.teamName(), original, slot, pick.asOfDate(), pick.draftPickId());
            } else {
                System.out.printf("PICK  MISSING  %s  team=%s%s%s  [%s]%n",
                    pick.label(), pick.teamName(), original, slot, pick.draftPickId());
            }
        }
    }

    private static void printLeagueAssets(String leagueId, String sourceOverride) throws SQLException {
        Database database = initializedDatabase();
        LeagueAssetInventoryAnalyzer analyzer = new LeagueAssetInventoryAnalyzer(database);
        var report = sourceOverride == null
            ? analyzer.analyze(leagueId)
            : analyzer.analyze(leagueId, sourceOverride);

        System.out.println("League asset inventory");
        System.out.println("League ID: " + report.leagueId());
        System.out.println("Source: " + report.source());
        System.out.printf("Coverage: %d/%d assets (%.1f%%)  missing-players=%d  missing-picks=%d%n",
            report.valuedAssets(), report.totalAssets(), report.coveragePercent(),
            report.missingPlayers(), report.missingDraftPicks());
        if (report.teams().isEmpty()) {
            System.out.println("No teams found for this league.");
            return;
        }
        for (var team : report.teams()) {
            System.out.println();
            System.out.printf("%s  assets=%d  [%s]%n", team.teamName(), team.totalAssets(), team.teamId());
            for (var player : team.players()) {
                if (player.valued()) {
                    System.out.printf("  PLAYER  %.2f  %s  %s%s  slot=%s  as-of=%s  [%s]%n",
                        player.value(), player.position(), player.playerName(), formatTeam(player.nflTeam()),
                        player.slot(), player.asOfDate(), player.playerId());
                } else {
                    System.out.printf("  PLAYER  MISSING  %s  %s%s  slot=%s  [%s]%n",
                        player.position(), player.playerName(), formatTeam(player.nflTeam()),
                        player.slot(), player.playerId());
                }
            }
            for (var pick : team.draftPicks()) {
                String original = pick.originalTeamName().equals(team.teamName())
                    ? "" : "  original=" + pick.originalTeamName();
                String slot = pick.pickNumber() == null ? "" : "  slot=" + pick.pickNumber();
                if (pick.valued()) {
                    System.out.printf("  PICK  %.2f  %s%s%s  as-of=%s  [%s]%n",
                        pick.value(), pick.label(), original, slot, pick.asOfDate(), pick.draftPickId());
                } else {
                    System.out.printf("  PICK  MISSING  %s%s%s  [%s]%n",
                        pick.label(), original, slot, pick.draftPickId());
                }
            }
        }
    }

    private static void printFranchiseRankings(String leagueId, String sourceOverride,
                                               LocalDate minimumAsOfDate) throws SQLException {
        Database database = initializedDatabase();
        FranchiseValueRankingAnalyzer analyzer = new FranchiseValueRankingAnalyzer(database);
        var report = minimumAsOfDate == null
            ? (sourceOverride == null ? analyzer.rank(leagueId) : analyzer.rank(leagueId, sourceOverride))
            : (sourceOverride == null
                ? analyzer.rank(leagueId, minimumAsOfDate)
                : analyzer.rank(leagueId, sourceOverride, minimumAsOfDate));

        System.out.println("Franchise value rankings");
        System.out.println("League ID: " + report.leagueId());
        System.out.println("Source: " + report.source());
        if (report.minimumAsOfDate() != null) {
            System.out.println("Minimum as-of: " + report.minimumAsOfDate());
        }
        System.out.printf("League assets: total=%.2f  players=%.2f  picks=%.2f%n",
            report.totalAssetValue(), report.playerValue(), report.draftPickValue());
        if (report.teams().isEmpty()) {
            System.out.println("No teams found for this league.");
            return;
        }
        for (var team : report.teams()) {
            System.out.printf("%d. %s  total=%.2f  players=%.2f  picks=%.2f  assets=%d players + %d picks  dates=%s  [%s]%n",
                team.rank(), team.teamName(), team.totalAssetValue(), team.playerValue(), team.draftPickValue(),
                team.valuedPlayers(), team.valuedDraftPicks(),
                valueDates(team.oldestValueDate(), team.latestValueDate()), team.teamId());
        }
    }

    private static void printTeamPortfolio(String leagueId, String sourceOverride) throws SQLException {
        Database database = initializedDatabase();
        TeamAssetPortfolioAnalyzer analyzer = new TeamAssetPortfolioAnalyzer(database);
        var report = sourceOverride == null
            ? analyzer.analyze(leagueId)
            : analyzer.analyze(leagueId, sourceOverride);

        System.out.println("League team asset portfolios");
        System.out.println("League ID: " + report.leagueId());
        System.out.println("Source: " + report.source());
        System.out.printf("League assets: total=%.2f  players=%.2f  picks=%.2f  coverage=%d/%d (%.1f%%)%n",
            report.totalAssetValue(), report.playerValue(), report.draftPickValue(),
            report.valuedAssets(), report.totalAssets(), report.coveragePercent());
        if (!report.complete()) {
            System.out.printf("Missing values: players=%d  picks=%d%n",
                report.missingPlayers(), report.missingDraftPicks());
        }
        if (report.teams().isEmpty()) {
            System.out.println("No teams found for this league.");
            return;
        }
        for (var team : report.teams()) {
            String dates = valueDates(team.oldestValueDate(), team.latestValueDate());
            System.out.printf("%s  total=%.2f  players=%.2f  picks=%.2f  coverage=%d/%d (%.1f%%)  player-coverage=%.1f%%  pick-coverage=%.1f%%  dates=%s  [%s]%n",
                team.teamName(), team.totalAssetValue(), team.playerValue(), team.draftPickValue(),
                team.valuedAssets(), team.totalAssets(), team.coveragePercent(),
                team.playerCoveragePercent(), team.draftPickCoveragePercent(), dates, team.teamId());
        }
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
                players.add(token);
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
        if (report.minimumAsOfDate() != null) {
            System.out.println("Minimum as-of: " + report.minimumAsOfDate());
        }
        System.out.printf("Coverage: %d/%d assets (%.1f%%)  stale=%d%n",
            report.valuedAssets(), report.totalAssets(), report.coveragePercent(), report.staleAssets());
        printTradeSide("Side A", report.sideA());
        printTradeSide("Side B", report.sideB());
        if (report.comparable()) {
            System.out.printf("Side A - Side B: %+.2f%n", report.valueDifference());
        } else if (report.missingAssets() > 0 && report.staleAssets() > 0) {
            System.out.println("Side A - Side B: unavailable until all trade assets have values and meet the minimum as-of date.");
        } else if (report.missingAssets() > 0) {
            System.out.println("Side A - Side B: unavailable until all trade assets have values.");
        } else {
            System.out.println("Side A - Side B: unavailable because one or more valued assets are older than the minimum as-of date.");
        }
    }

    private static void printTradeSide(String label, TradeAssetAnalyzer.TradeSide side) {
        System.out.printf("%s: value=%.2f  coverage=%d/%d (%.1f%%)  stale=%d%n",
            label, side.totalValue(), side.valuedAssets(), side.totalAssets(), side.coveragePercent(), side.staleAssets());
        for (var player : side.players()) {
            if (player.valued()) {
                String freshness = player.stale() ? "STALE  " : "";
                System.out.printf("  %s%.2f  PLAYER  %s  %s%s  fantasy-team=%s  as-of=%s  [%s]%n",
                    freshness, player.value(), player.position(), player.playerName(), formatTeam(player.nflTeam()),
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
                String freshness = pick.stale() ? "STALE  " : "";
                System.out.printf("  %s%.2f  PICK  %s  owner=%s%s%s  as-of=%s  [%s]%n",
                    freshness, pick.value(), pick.label(), pick.ownerTeamName(), original, slot,
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

    private static LocalDate parseMinimumAsOfDate(String value) {
        try {
            return LocalDate.parse(value);
        } catch (DateTimeParseException e) {
            throw new IllegalArgumentException("minimum-as-of must use YYYY-MM-DD: " + value);
        }
    }

    private static String valueDates(LocalDate oldest, LocalDate latest) {
        if (oldest == null) return "none";
        return oldest.equals(latest) ? oldest.toString() : oldest + " to " + latest;
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
        System.out.println("  butler trade compare <league-id> <side-a-assets> <side-b-assets> [source] [--minimum-as-of YYYY-MM-DD]");
        System.out.println("  Assets are comma-separated. Bare IDs are players; use player:<id> or pick:<draft-pick-id>.");
    }

    private static void printDraftPickUsage() {
        System.out.println("Draft picks, portfolios, rankings, readiness, inventories, search, and full sync:");
        System.out.println("  butler sleeper sync-all <sleeper-league-id>");
        System.out.println("  butler sleeper sync-picks <sleeper-league-id>");
        System.out.println("  butler league draft-pick-values <league-id> dynastyprocess");
        System.out.println("  butler league draft-picks <league-id> [source]");
        System.out.println("  butler league assets <league-id> [source]");
        System.out.println("  butler league asset-search <league-id> <query> [source]");
        System.out.println("  butler league portfolio <league-id> [source]");
        System.out.println("  butler league franchise-readiness <league-id> [source] [--minimum-as-of YYYY-MM-DD]");
        System.out.println("  butler league franchise-rank <league-id> [source] [--minimum-as-of YYYY-MM-DD]");
    }
}
