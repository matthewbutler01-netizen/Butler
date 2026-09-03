package io.butler.bet.cli;

import io.butler.bet.data.Database;
import io.butler.bet.intelligence.TradeRosterContextAnalyzer;
import io.butler.bet.intelligence.TradeSupportingEvidenceAnalyzer;
import io.butler.bet.intelligence.TradeTeamPostureContextAnalyzer;

import java.nio.file.Path;
import java.sql.SQLException;
import java.util.Arrays;
import java.util.List;

/** Read-only CLI for player-only trade value, supporting evidence, market context, roster context, and team posture. */
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
            var analyzer = new TradeTeamPostureContextAnalyzer(initializedDatabase());
            var report = options.source() == null
                ? analyzer.analyze(options.leagueId(), options.season(), options.sideAPlayerIds(), options.sideBPlayerIds())
                : analyzer.analyze(options.leagueId(), options.season(), options.sideAPlayerIds(), options.sideBPlayerIds(), options.source());
            print(report);
        } catch (SQLException e) {
            System.err.println("Database error while building trade posture context: " + e.getMessage());
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

    static void print(TradeTeamPostureContextAnalyzer.TradePostureContextReport postureContext) {
        if (postureContext == null) throw new IllegalArgumentException("postureContext must not be null");
        var context = postureContext.trade();
        var report = context.trade();
        var trade = report.tradeValue();
        var marketEdge = context.marketEdge();
        System.out.println("Trade supporting evidence + roster context + governed team posture");
        System.out.println("League ID: " + trade.leagueId());
        System.out.println("Season: " + report.season());
        System.out.println("Value source: " + trade.source());
        System.out.println("Production source: " + context.productionSource());
        System.out.println("Model age as-of: " + report.modelAgeAsOf());
        System.out.println("Support policy: " + report.supportPolicyId());
        System.out.println("Outlook policy: " + report.outlookPolicyId());
        System.out.println("Posture policy: " + postureContext.posturePolicyId());
        System.out.println("Posture available: " + postureContext.postureAvailable());
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
        System.out.println("Market fairness and market edge are based only on market values. Team posture is governed descriptive context only and still does not create a winner or accept/reject/counter recommendation.");
        printTradeSide("A", report.sideA());
        printRosterContext("A", postureContext.sideA());
        printTradeSide("B", report.sideB());
        printRosterContext("B", postureContext.sideB());
    }

    static String formatGap(Double symmetricGapPercent) {
        return symmetricGapPercent == null ? "unavailable" : String.format("%.3f%%", symmetricGapPercent);
    }

    private static void printTradeSide(String label, TradeSupportingEvidenceAnalyzer.TradeEvidenceSide side) {
        System.out.printf("Side %s package: value=%.2f coverage=%d/%d (%.1f%%) supporting-flags=%d directional=%d%n",
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

    private static void printRosterContext(String label, TradeTeamPostureContextAnalyzer.TeamTradePosture team) {
        TradeRosterContextAnalyzer.TeamRosterContext context = team.context();
        var posture = team.posture();
        var profile = context.profile();
        var production = context.production();
        System.out.printf("Side %s roster context: %s [%s]%n", label, context.teamName(), context.teamId());
        System.out.printf("  posture=%s competitive-tier=%s roster-tier=%s%n",
            posture.posture(), posture.competitiveTier(), posture.rosterTier());
        System.out.printf("  usable-values player=%.2f draft-picks=%.2f assets=%.2f starter-share=%.1f%%%n",
            profile.usablePlayerValue(), profile.usableDraftPickValue(), profile.usableAssetValue(),
            profile.starterValueSharePercent());
        System.out.printf("  concentration top-asset=%.1f%% top-three=%.1f%% index=%.4f%n",
            profile.topAssetSharePercent(), profile.topThreeAssetSharePercent(), profile.concentrationIndex());
        System.out.printf("  production coverage=%d/%d (%.1f%%) as-of=%s..%s%n",
            production.coveredPlayers(), production.totalPlayers(), production.coveragePercent(),
            production.earliestAsOf() == null ? "-" : production.earliestAsOf(),
            production.latestAsOf() == null ? "-" : production.latestAsOf());
        profile.positionalDepth().positions().entrySet().stream().sorted(java.util.Map.Entry.comparingByKey())
            .forEach(entry -> {
                var depth = entry.getValue();
                var raw = production.positions().get(entry.getKey());
                System.out.printf("  %s depth: players=%d valued=%d coverage=%.1f%% usable-value=%.2f top1=%.2f top2=%.2f top3=%.2f%n",
                    entry.getKey(), depth.totalPlayers(), depth.valuedPlayers(), depth.coveragePercent(),
                    depth.totalUsableValue(), depth.topOneValue(), depth.topTwoValue(), depth.topThreeValue());
                if (raw != null) {
                    System.out.printf("    raw-production covered=%d/%d games=%d passYds=%d passTD=%d INT=%d rushYds=%d rushTD=%d rec=%d recYds=%d recTD=%d fumblesLost=%d%n",
                        raw.coveredPlayers(), raw.totalPlayers(), raw.playerGames(), raw.passingYards(),
                        raw.passingTouchdowns(), raw.interceptions(), raw.rushingYards(), raw.rushingTouchdowns(),
                        raw.receptions(), raw.receivingYards(), raw.receivingTouchdowns(), raw.fumblesLost());
                }
            });
    }

    static void printUsage() {
        System.out.println("  butler trade supporting-evidence <league-id> <season> <side-a-player-ids> <side-b-player-ids> [source]");
        System.out.println("  player-id lists are comma-separated, must each belong to one fantasy team, and remain player-only; draft picks are not accepted by this evidence surface");
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
