package io.butler.bet.cli;

import io.butler.bet.data.Database;
import io.butler.bet.intelligence.LeagueScoredProductionEvidenceAnalyzer;

import java.math.BigDecimal;
import java.nio.file.Path;
import java.sql.SQLException;

/** Read-only league-wide scored-production evidence in roster order, never score rank order. */
public final class ButlerLeagueScoredProductionEvidenceCli {
    private static final Path DATABASE_PATH = Path.of("butler.db");

    private ButlerLeagueScoredProductionEvidenceCli() {}

    public static void main(String[] args) {
        try {
            Options options = parse(args);
            print(new LeagueScoredProductionEvidenceAnalyzer(initializedDatabase()).analyze(
                options.leagueId(), options.season(), options.source()));
        } catch (SQLException e) {
            System.err.println("Database error while building league scored-production evidence: " + e.getMessage());
            System.exit(1);
        } catch (IllegalArgumentException | IllegalStateException e) {
            System.err.println("Error: " + e.getMessage());
            System.exit(2);
        }
    }

    static Options parse(String[] args) {
        if (!isCommand(args) || args.length != 5) {
            throw new IllegalArgumentException(
                "Usage: butler league scored-production-evidence <league-id> <season> <source>");
        }
        return new Options(requireText(args[2], "league-id"), parseSeason(args[3]), requireText(args[4], "source"));
    }

    static boolean isCommand(String[] args) {
        return args != null && args.length >= 2
            && "league".equalsIgnoreCase(args[0])
            && "scored-production-evidence".equalsIgnoreCase(args[1]);
    }

    static void print(LeagueScoredProductionEvidenceAnalyzer.EvidenceReport report) {
        if (report == null) throw new IllegalArgumentException("report must not be null");
        System.out.println("League scored-production evidence");
        System.out.println("Policy: " + report.policyId());
        System.out.println("Coverage policy: " + report.coveragePolicyId());
        System.out.println("Scoring policy: " + report.scoringPolicyId());
        System.out.println("League: " + report.leagueName() + " (" + report.leagueId() + ")");
        System.out.println("Season: " + report.season());
        System.out.println("Source: " + report.source());
        System.out.printf("Coverage: %d/%d (%.1f%%) complete=%s%n",
            report.coveredPlayers(), report.players().size(), report.coveragePercent(), report.complete());
        System.out.println("Team | Slot | Player | Position | Fantasy points | Production snapshot");
        for (var player : report.players()) {
            if (player.available()) {
                System.out.println(player.teamName() + " | " + player.rosterSlot() + " | "
                    + player.playerName() + " [" + player.playerId() + "] | " + player.position() + " | "
                    + format(player.fantasyPoints()) + " | " + player.productionId() + " as-of=" + player.productionAsOf());
            } else {
                System.out.println(player.teamName() + " | " + player.rosterSlot() + " | "
                    + player.playerName() + " [" + player.playerId() + "] | " + player.position()
                    + " | unavailable | " + player.unavailableReason());
            }
        }
        System.out.println("Descriptive roster-order evidence only; players are not sorted by score and no recommendation is inferred.");
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

    private static String format(BigDecimal value) {
        return value.stripTrailingZeros().toPlainString();
    }

    private static Database initializedDatabase() throws SQLException {
        Database database = new Database(DATABASE_PATH);
        database.initialize();
        return database;
    }

    private static String requireText(String value, String field) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(field + " must not be blank");
        return value.trim();
    }

    record Options(String leagueId, int season, String source) {}
}
