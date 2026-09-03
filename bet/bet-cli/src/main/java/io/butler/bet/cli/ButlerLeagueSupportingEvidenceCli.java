package io.butler.bet.cli;

import io.butler.bet.data.Database;
import io.butler.bet.intelligence.LeagueAgeOutlookSupportingEvidenceAnalyzer;

import java.nio.file.Path;
import java.sql.SQLException;

/** CLI surface for neutral supporting-evidence flags intended for future decision packages. */
public final class ButlerLeagueSupportingEvidenceCli {
    private static final Path DATABASE_PATH = Path.of("butler.db");

    private ButlerLeagueSupportingEvidenceCli() {}

    public static void main(String[] args) {
        try {
            Options options = parse(args);
            print(new LeagueAgeOutlookSupportingEvidenceAnalyzer(initializedDatabase()).analyze(
                options.leagueId(), options.season()));
        } catch (IllegalArgumentException e) {
            System.err.println("Error: " + e.getMessage());
            printUsage();
        } catch (SQLException e) {
            System.err.println("Database error while building supporting evidence: " + e.getMessage());
            System.exit(1);
        }
    }

    static boolean isCommand(String[] args) {
        return args != null && args.length >= 2
            && args[0].equalsIgnoreCase("league")
            && args[1].equalsIgnoreCase("supporting-evidence");
    }

    static Options parse(String[] args) {
        if (!isCommand(args) || args.length != 4) {
            throw new IllegalArgumentException("expected league supporting-evidence <league-id> <season>");
        }
        String leagueId = args[2] == null ? "" : args[2].trim();
        if (leagueId.isBlank()) throw new IllegalArgumentException("league-id must not be blank");
        int season;
        try {
            season = Integer.parseInt(args[3]);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("season must be a whole year");
        }
        if (season < 1999 || season > 2100) {
            throw new IllegalArgumentException("season must be between 1999 and 2100");
        }
        return new Options(leagueId, season);
    }

    static void print(LeagueAgeOutlookSupportingEvidenceAnalyzer.SupportingEvidenceReport report) {
        if (report == null) throw new IllegalArgumentException("report must not be null");
        System.out.println("League supporting evidence");
        System.out.println("League ID: " + report.leagueId());
        System.out.println("Season: " + report.season());
        System.out.println("Model age as-of: " + report.modelAgeAsOf());
        System.out.println("Support policy: " + report.supportPolicyId());
        System.out.println("Outlook policy: " + report.outlookPolicyId());
        System.out.println("Model profile source: " + report.modelProfileSource());
        System.out.println("Model production source: " + report.modelProductionSource());
        System.out.printf("Flags: total=%d directional=%d%n", report.totalFlags(), report.directionalFlags());
        System.out.println("Supporting context only: flags have no numeric weight, score contribution, dynasty-value adjustment, or recommendation action.");

        for (var player : report.players()) {
            System.out.printf("%s %s model-age=%s team=%s [%s] flags=%d favorable=%d inconclusive=%d unfavorable=%d%n",
                player.playerName(), player.position(), player.modelAge() == null ? "-" : player.modelAge(),
                player.teamName(), player.playerId(), player.flags().size(), player.favorableFlags(),
                player.inconclusiveFlags(), player.unfavorableFlags());
            for (var flag : player.flags()) {
                System.out.printf("  category=%s dimension=%s signal=%s policy=%s source=%s summary=%s%n",
                    flag.category(), flag.dimension(), flag.signal(), flag.policyId(), flag.evidenceSource(), flag.summary());
            }
        }
    }

    static void printUsage() {
        System.out.println("  butler league supporting-evidence <league-id> <season>");
    }

    private static Database initializedDatabase() throws SQLException {
        Database database = new Database(DATABASE_PATH);
        database.initialize();
        return database;
    }

    record Options(String leagueId, int season) {}
}
