package io.butler.bet.cli;

import io.butler.bet.data.Database;
import io.butler.bet.intelligence.LeagueAgingModelEvidenceAnalyzer;

import java.nio.file.Path;
import java.sql.SQLException;

/** CLI leaf for neutral, governed league aging-model evidence. */
public final class ButlerLeagueAgingModelEvidenceCli {
    private static final Path DATABASE_PATH = Path.of("butler.db");

    private ButlerLeagueAgingModelEvidenceCli() {}

    public static void main(String[] args) {
        try {
            Options options = parse(args);
            print(new LeagueAgingModelEvidenceAnalyzer(initializedDatabase()).analyze(
                options.leagueId(), options.season()));
        } catch (IllegalArgumentException e) {
            System.err.println("Error: " + e.getMessage());
            printUsage();
        } catch (SQLException e) {
            System.err.println("Database error while building league aging-model evidence: " + e.getMessage());
            System.exit(1);
        }
    }

    static boolean isCommand(String[] args) {
        return args != null && args.length >= 2
            && args[0].equalsIgnoreCase("league")
            && args[1].equalsIgnoreCase("aging-model-evidence");
    }

    static Options parse(String[] args) {
        if (!isCommand(args) || args.length != 4) {
            throw new IllegalArgumentException("expected league aging-model-evidence <league-id> <season>");
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

    static void print(LeagueAgingModelEvidenceAnalyzer.LeagueAgingModelEvidenceReport report) {
        if (report == null) throw new IllegalArgumentException("report must not be null");
        System.out.println("League aging-model evidence");
        System.out.println("League ID: " + report.leagueId());
        System.out.println("Season: " + report.season());
        System.out.println("Model age as-of: " + report.modelAgeAsOf());
        System.out.println("League profile source: " + report.leagueProfileSource());
        System.out.println("Support policy: " + report.supportPolicyId());
        System.out.println("Minimum distinct season transitions: " + report.minimumDistinctSeasonTransitions());
        System.out.println("Model profile source: " + report.modelProfileSource());
        System.out.println("Model production source: " + report.modelProductionSource());
        System.out.printf("Players: total=%d full=%d partial=%d below-support=%d not-observed=%d exact-age-unavailable=%d unsupported-position=%d%n",
            report.totalPlayers(), report.fullPlayers(), report.partialPlayers(), report.belowSupportPlayers(),
            report.notObservedPlayers(), report.exactAgeUnavailablePlayers(), report.unsupportedPositionPlayers());
        System.out.println("No score, career-stage label, dynasty adjustment, or recommendation is applied.");

        for (var team : report.teams()) {
            System.out.printf("%s [%s] full=%d partial=%d below-support=%d not-observed=%d exact-age-unavailable=%d unsupported-position=%d%n",
                team.teamName(), team.teamId(), team.fullPlayers(), team.partialPlayers(), team.belowSupportPlayers(),
                team.notObservedPlayers(), team.exactAgeUnavailablePlayers(), team.unsupportedPositionPlayers());
            for (var player : team.players()) {
                System.out.printf("  %s %s model-age=%s status=%s [%s]%n",
                    player.playerName(), player.position(), player.modelAge() == null ? "-" : player.modelAge(),
                    player.status(), player.playerId());
                if (player.evidence() == null) continue;
                for (var metric : player.evidence().metrics()) {
                    if (!metric.available()) {
                        System.out.printf("    %s status=%s cell=unavailable%n", metric.metric(), metric.status());
                        continue;
                    }
                    var cell = metric.cell();
                    System.out.printf("    %s status=%s transitions=%d delta[p25=%.4f median=%.4f p75=%.4f]%n",
                        metric.metric(), metric.status(), cell.distinctSeasonTransitions(),
                        cell.deltaP25(), cell.medianDelta(), cell.deltaP75());
                }
            }
        }
    }

    static void printUsage() {
        System.out.println("  butler league aging-model-evidence <league-id> <season>");
    }

    private static Database initializedDatabase() throws SQLException {
        Database database = new Database(DATABASE_PATH);
        database.initialize();
        return database;
    }

    record Options(String leagueId, int season) {}
}
