package io.butler.bet.cli;

import io.butler.bet.data.Database;
import io.butler.bet.intelligence.DecisionSupportingEvidenceFlag;
import io.butler.bet.intelligence.LeagueAgeProductionContextAnalyzer;
import io.butler.bet.intelligence.LeagueAssetInventoryAnalyzer;
import io.butler.bet.intelligence.LeaguePlayerEvidenceProfileAnalyzer;

import java.nio.file.Path;
import java.sql.SQLException;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

/**
 * Read-only side-by-side comparison of two exact rostered players using Butler's existing evidence.
 *
 * No winner, score, rank, grade, value adjustment, or recommendation is produced.
 */
public final class ButlerLeaguePlayerCompareCli {
    private static final Path DATABASE_PATH = Path.of("butler.db");

    private ButlerLeaguePlayerCompareCli() {}

    public static void main(String[] args) {
        int exitCode = runEmbedded(args);
        if (exitCode != 0) {
            System.exit(exitCode);
        }
    }

    static int runEmbedded(String[] args) {
        try {
            Options options = parse(args);
            Database database = initializedDatabase();

            var profiles = new LeaguePlayerEvidenceProfileAnalyzer(database);
            var ageProduction = new LeagueAgeProductionContextAnalyzer(database);
            var inventoryAnalyzer = new LeagueAssetInventoryAnalyzer(database);
            LocalDate ageAsOf = LocalDate.now(ZoneOffset.UTC);

            var profileReport = options.season() == null
                ? profiles.analyze(options.leagueId(), ageAsOf, null)
                : profiles.analyze(options.leagueId(), options.season(), ageAsOf, null);
            var ageReport = ageProduction.analyze(profileReport);
            var inventory = inventoryAnalyzer.analyze(options.leagueId());

            var left = player(options.leftPlayerId(), ageReport, profileReport, inventory);
            var right = player(options.rightPlayerId(), ageReport, profileReport, inventory);
            print(new PlayerCompareReport(
                options.leagueId(),
                ageReport.season(),
                ageReport.ageAsOf(),
                inventory.source(),
                left,
                right));
            return 0;
        } catch (SQLException e) {
            System.err.println("Database error while building player comparison: " + e.getMessage());
            return 1;
        } catch (IllegalArgumentException | IllegalStateException e) {
            System.err.println("Error: " + e.getMessage());
            return 2;
        }
    }

    static Options parse(String[] args) {
        if (!isCommand(args) || (args.length != 5 && args.length != 6)) {
            throw new IllegalArgumentException(
                "Usage: butler league player-compare <league-id> <left-player-id> <right-player-id> [season]");
        }
        String leagueId = requireText(args[2], "league-id");
        String left = requireText(args[3], "left-player-id");
        String right = requireText(args[4], "right-player-id");
        if (left.equals(right)) {
            throw new IllegalArgumentException("player comparison requires two different exact player ids");
        }
        return new Options(
            leagueId,
            left,
            right,
            args.length == 6 ? parseSeason(args[5]) : null);
    }

    static boolean isCommand(String[] args) {
        return args != null && args.length >= 2
            && "league".equalsIgnoreCase(args[0])
            && "player-compare".equalsIgnoreCase(args[1]);
    }

    static ComparablePlayer player(
        String playerId,
        LeagueAgeProductionContextAnalyzer.AgeProductionReport ageReport,
        LeaguePlayerEvidenceProfileAnalyzer.PlayerEvidenceProfileReport profileReport,
        LeagueAssetInventoryAnalyzer.InventoryReport inventory) {
        Objects.requireNonNull(inventory, "inventory must not be null");
        var detail = ButlerLeaguePlayerDetailCli.select(playerId, ageReport, profileReport);
        if (!detail.leagueId().equals(inventory.leagueId())) {
            throw new IllegalStateException("player comparison evidence references different leagues");
        }

        List<SelectedAsset> matches = new ArrayList<>();
        for (var team : inventory.teams()) {
            for (var asset : team.players()) {
                if (asset.playerId().equals(playerId)) {
                    matches.add(new SelectedAsset(team.teamId(), team.teamName(), asset));
                }
            }
        }
        if (matches.size() != 1) {
            throw new IllegalArgumentException(
                "player must resolve exactly once in current league inventory: " + playerId
                    + " (matches=" + matches.size() + ")");
        }

        var selected = matches.getFirst();
        var player = detail.player();
        if (!detail.teamId().equals(selected.teamId())
            || !detail.teamName().equals(selected.teamName())
            || !player.playerName().equals(selected.asset().playerName())
            || !player.position().equals(selected.asset().position())
            || !Objects.equals(player.rosterSlot(), selected.asset().slot())) {
            throw new IllegalStateException(
                "player comparison inventory identity does not match neutral evidence: " + playerId);
        }
        return new ComparablePlayer(detail, selected.asset());
    }

    static void print(PlayerCompareReport report) {
        Objects.requireNonNull(report, "report must not be null");
        System.out.println("League player compare evidence");
        System.out.println("League ID: " + report.leagueId());
        System.out.println("Season: " + report.season());
        System.out.println("Age as-of: " + report.ageAsOf());
        System.out.println("Value source: " + report.valueSource());
        printPlayer("LEFT", report.left());
        printPlayer("RIGHT", report.right());
        System.out.println(
            "Side-by-side neutral evidence only; this comparison does not select a winner or produce a score, "
                + "rank, grade, buy/sell label, dynasty adjustment, start/sit, trade, or waiver recommendation.");
    }

    private static void printPlayer(String side, ComparablePlayer comparable) {
        var report = comparable.detail();
        var player = report.player();
        var asset = comparable.asset();

        System.out.println("===BUTLER_PLAYER_COMPARE:" + side + ":BEGIN===");
        System.out.println("Player ID: " + player.playerId());
        System.out.println("Player name: " + player.playerName());
        System.out.println("Position: " + player.position());
        System.out.println("NFL team: " + value(asset.nflTeam()));
        System.out.println("Butler team ID: " + report.teamId());
        System.out.println("Butler team name: " + report.teamName());
        System.out.println("Roster slot: " + value(player.rosterSlot()));
        System.out.println("Value: " + (asset.valued()
            ? String.format(Locale.ROOT, "%.2f", asset.value())
            : "UNAVAILABLE"));
        System.out.println("Value as-of: " + (asset.asOfDate() == null ? "UNAVAILABLE" : asset.asOfDate()));
        System.out.println("Age: " + value(player.age()));
        System.out.println("Age provenance: " + player.ageProvenance());
        System.out.println("Production snapshot: " + (player.productionAvailable() ? "AVAILABLE" : "MISSING"));
        System.out.println("Games played: " + (player.productionAvailable() ? player.gamesPlayed() : "UNAVAILABLE"));
        System.out.println("Passing yards/game: " + rate(player.passingYardsPerGame()));
        System.out.println("Passing TD/game: " + rate(player.passingTouchdownsPerGame()));
        System.out.println("Interceptions/game: " + rate(player.interceptionsPerGame()));
        System.out.println("Rushing yards/game: " + rate(player.rushingYardsPerGame()));
        System.out.println("Rushing TD/game: " + rate(player.rushingTouchdownsPerGame()));
        System.out.println("Receptions/game: " + rate(player.receptionsPerGame()));
        System.out.println("Receiving yards/game: " + rate(player.receivingYardsPerGame()));
        System.out.println("Receiving TD/game: " + rate(player.receivingTouchdownsPerGame()));
        System.out.println("Fumbles lost/game: " + rate(player.fumblesLostPerGame()));
        System.out.println("Supporting flags: " + report.supportingFlags().size());
        for (DecisionSupportingEvidenceFlag flag : report.supportingFlags()) {
            System.out.println("Supporting flag: " + flag.signal() + " | " + flag.category() + " | "
                + flag.dimension() + " | " + flag.summary());
        }
        System.out.println("===BUTLER_PLAYER_COMPARE:" + side + ":END===");
    }

    private static String value(Object value) {
        if (value == null) return "UNAVAILABLE";
        String text = value.toString();
        return text.isBlank() ? "UNAVAILABLE" : text;
    }

    private static String rate(Double value) {
        return value == null ? "UNAVAILABLE" : String.format(Locale.ROOT, "%.3f", value);
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

    private static String requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
        return value.trim();
    }

    private static Database initializedDatabase() throws SQLException {
        Database database = new Database(DATABASE_PATH);
        database.initialize();
        return database;
    }

    record Options(String leagueId, String leftPlayerId, String rightPlayerId, Integer season) {}

    private record SelectedAsset(
        String teamId,
        String teamName,
        LeagueAssetInventoryAnalyzer.PlayerAsset asset) {}

    record ComparablePlayer(
        ButlerLeaguePlayerDetailCli.PlayerDetailReport detail,
        LeagueAssetInventoryAnalyzer.PlayerAsset asset) {}

    record PlayerCompareReport(
        String leagueId,
        int season,
        LocalDate ageAsOf,
        String valueSource,
        ComparablePlayer left,
        ComparablePlayer right) {
        PlayerCompareReport {
            leagueId = requireText(leagueId, "leagueId");
            valueSource = requireText(valueSource, "valueSource");
            Objects.requireNonNull(ageAsOf, "ageAsOf must not be null");
            Objects.requireNonNull(left, "left must not be null");
            Objects.requireNonNull(right, "right must not be null");
            if (left.detail().player().playerId().equals(right.detail().player().playerId())) {
                throw new IllegalArgumentException("player comparison requires two different players");
            }
        }
    }
}
