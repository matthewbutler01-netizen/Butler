package io.butler.bet.cli;

import io.butler.bet.data.Database;
import io.butler.bet.intelligence.TradeMarketEdgeAnalyzer;
import io.butler.bet.intelligence.TradeSupportingEvidenceAnalyzer;

import java.nio.file.Path;
import java.sql.SQLException;
import java.util.Arrays;
import java.util.List;

/** Read-only CLI for player-only trade value plus governed supporting evidence and market fairness. */
public final class ButlerTradeSupportingEvidenceCli {
    private static final Path DATABASE_PATH = Path.of("butler.db");

    private ButlerTradeSupportingEvidenceCli() {}

    public static void main(String[] args) {
        Options options;
        try {
            options = parse(args);
        } catch (IllegalArgumentException e) {
            System.err.println("Error: " + e.getMessage());
            printUsage();
            return;
        }

        try {
            var analyzer = new TradeSupportingEvidenceAnalyzer(initializedDatabase());
            var report = options.source() == null
                ? analyzer.analyze(options.leagueId(), options.season(), options.sideAPlayerIds(), options.sideBPlayerIds())
                : analyzer.analyze(options.leagueId(), options.season(), options.sideAPlayerIds(), options.sideBPlayerIds(), options.source());
            print(report);
        } catch (SQLException e) {
            System.err.println("Database error while building trade supporting evidence: " + e.getMessage());
            System.exit(1);
        } catch (IllegalArgumentException | IllegalStateException e) {
            System.err.println("Error: " + e.getMessage());
            System.exit(2);
        }
    }

    static Options parse(String[] args) {
        if (!isCommand(args) || (args.length != 6 && args.length != 7)) {
            throw new IllegalArgumentException("trade supporting-evidence requires league id, season, and two comma-separated player-id lists");
        }
        return new Options(
            requireText(args[2], "league-id"),
            parseSeason(args[3]),
            parsePlayerIds(args[4], "side-a-player-ids"),
            parsePlayerIds(args[5], "side-b-player-ids"),
            args.length == 7 ? requireText(args[6], "source") : null);
    }

    static boolean isCommand(String[] args) {
        return args != null && args.length >= 2
            && "trade".equalsIgnoreCase(args[0])
            && "supporting-evidence".equalsIgnoreCase(args[1]);
    }

    static void print(TradeSupportingEvidenceAnalyzer.TradeEvidencePackage report) {
        if (report == null) throw new IllegalArgumentException("report must not be null");
        var trade = report.tradeValue();
        var marketEdge = new TradeMarketEdgeAnalyzer().analyze(report);
        System.out.println("Trade supporting evidence");
        System.out.println("League ID: " + trade.leagueId());
        System.out.println("Season: " + report.season());
        System.out.println("Value source: " + trade.source());
        System.out.println("Model age as-of: " + report.modelAgeAsOf());
        System.out.println("Support policy: " + report.supportPolicyId());
        System.out.println("Outlook policy: " + report.outlookPolicyId());
        System.out.println("Model sources: " + report.modelProfileSource() + "+" + report.modelProductionSource());
        System.out.printf("Market-value coverage: %d/%d (%.1f%%) complete=%s%n",
            trade.valuedPlayers(), trade.totalPlayers(), trade.coveragePercent(), trade.complete());
        System.out.println("Market-value difference A-B: " + (trade.valueDifference() == null ? "unavailable" : trade.valueDifference()));
        System.out.println("Fairness measurement policy: " + report.fairnessMeasurementPolicyId());
        System.out.println("Fairness policy: " + report.fairnessPolicyId());
        System.out.println("Symmetric market-value gap: " + formatGap(report.fairnessGapPercent()));
        System.out.println("Market fairness: " + report.fairnessClassification());
        System.out.println("Market-edge policy: " + marketEdge.policyId());
        System.out.println("Market-value edge: " + marketEdge.direction());
        System.out.printf("Supporting flags: %d directional=%d%n", report.supportingFlags(), report.directionalSupportingFlags());
        System.out.println("Market fairness and market edge are based only on market values. Market edge is not a winner. Supporting evidence is descriptive only and does not modify values, fairness, edge, or create an accept/reject recommendation.");
        printSide("A", report.sideA());
        printSide("B", report.sideB());
    }

    static String formatGap(Double symmetricGapPercent) {
        return symmetricGapPercent == null ? "unavailable" : String.format("%.3f%%", symmetricGapPercent);
    }

    private static void printSide(String label, TradeSupportingEvidenceAnalyzer.TradeEvidenceSide side) {
        System.out.printf("Side %s: value=%.2f coverage=%d/%d (%.1f%%) supporting-flags=%d directional=%d%n",
            label, side.value().totalValue(), side.value().valuedPlayers(), side.value().players().size(),
            side.value().coveragePercent(), side.supportingFlags(), side.directionalSupportingFlags());
        for (var evidence : side.players()) {
            var player = evidence.player();
            System.out.printf("  %s %s [%s] value=%s as-of=%s team=%s flags=%d favorable=%d inconclusive=%d unfavorable=%d%n",
                player.position(), player.playerName(), player.playerId(),
                player.valued() ? Double.toString(player.value()) : "unavailable",
                player.asOfDate() == null ? "-" : player.asOfDate(), player.teamName(),
                evidence.supportingFlags().size(), evidence.favorableFlags(), evidence.inconclusiveFlags(), evidence.unfavorableFlags());
            for (var flag : evidence.supportingFlags()) {
                System.out.printf("    %s %s  policy=%s  source=%s  %s%n",
                    flag.signal(), flag.dimension(), flag.policyId(), flag.evidenceSource(), flag.summary());
            }
        }
    }

    static void printUsage() {
        System.out.println("  butler trade supporting-evidence <league-id> <season> <side-a-player-ids> <side-b-player-ids> [source]");
        System.out.println("  player-id lists are comma-separated and player-only; draft picks are not accepted by this evidence surface");
    }

    private static List<String> parsePlayerIds(String value, String field) {
        String normalized = requireText(value, field);
        List<String> ids = Arrays.stream(normalized.split(",", -1)).map(String::trim).toList();
        if (ids.isEmpty() || ids.stream().anyMatch(String::isBlank)) {
            throw new IllegalArgumentException(field + " must contain nonblank comma-separated player ids");
        }
        return ids;
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
        if (value == null || value.isBlank()) throw new IllegalArgumentException(field + " must not be blank");
        return value.trim();
    }

    private static Database initializedDatabase() throws SQLException {
        Database database = new Database(DATABASE_PATH);
        database.initialize();
        return database;
    }

    record Options(String leagueId, int season, List<String> sideAPlayerIds,
                   List<String> sideBPlayerIds, String source) {}
}
