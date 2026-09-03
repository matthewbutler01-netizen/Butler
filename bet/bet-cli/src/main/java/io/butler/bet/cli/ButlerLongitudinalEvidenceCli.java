package io.butler.bet.cli;

import io.butler.bet.data.Database;
import io.butler.bet.intelligence.LeagueLongitudinalEvidenceAnalyzer;

import java.nio.file.Path;
import java.sql.SQLException;

/** CLI leaf for longitudinal exact-age + multi-season production evidence coverage. */
public final class ButlerLongitudinalEvidenceCli {
    private static final Path DATABASE_PATH = Path.of("butler.db");

    private ButlerLongitudinalEvidenceCli() {}

    public static void main(String[] args) {
        String leagueId;
        try {
            leagueId = parse(args);
        } catch (IllegalArgumentException e) {
            System.err.println("Error: " + e.getMessage());
            printUsage();
            return;
        }

        try {
            print(new LeagueLongitudinalEvidenceAnalyzer(initializedDatabase()).analyze(leagueId));
        } catch (SQLException e) {
            System.err.println("Database error while building longitudinal evidence coverage: " + e.getMessage());
            System.exit(1);
        } catch (IllegalArgumentException | IllegalStateException e) {
            System.err.println("Error: " + e.getMessage());
            System.exit(2);
        }
    }

    static boolean isCommand(String[] args) {
        return args != null && args.length >= 2
            && args[0].equalsIgnoreCase("league")
            && args[1].equalsIgnoreCase("longitudinal-evidence");
    }

    static String parse(String[] args) {
        if (!isCommand(args) || args.length != 3) {
            throw new IllegalArgumentException("league longitudinal-evidence requires a league id");
        }
        if (args[2] == null || args[2].isBlank()) {
            throw new IllegalArgumentException("league-id must not be blank");
        }
        return args[2].trim();
    }

    static void print(LeagueLongitudinalEvidenceAnalyzer.LongitudinalEvidenceReport report) {
        if (report == null) throw new IllegalArgumentException("report must not be null");
        System.out.println("League longitudinal evidence coverage");
        System.out.println("League ID: " + report.leagueId());
        System.out.println("Production source: " + report.productionSource());
        System.out.printf("Players: %d  exact-birth-date=%d%n",
            report.totalPlayers(), report.exactBirthDatePlayers());
        System.out.printf("Production player-seasons: %d  rate-eligible=%d%n",
            report.productionPlayerSeasons(), report.rateEligiblePlayerSeasons());
        System.out.printf("Consecutive rate pairs: %d  exact-age pairs=%d  players-with-exact-age-pair=%d%n",
            report.consecutiveRatePairs(), report.exactAgeConsecutiveRatePairs(),
            report.playersWithExactAgeConsecutiveRatePair());
        System.out.println("No sufficiency threshold or aging curve is applied.");

        for (var team : report.teams()) {
            System.out.printf("%s  players=%d  exact-birth=%d  player-seasons=%d  rate-seasons=%d  pairs=%d  exact-age-pairs=%d  players-with-pair=%d  [%s]%n",
                team.teamName(), team.totalPlayers(), team.exactBirthDatePlayers(), team.productionPlayerSeasons(),
                team.rateEligiblePlayerSeasons(), team.consecutiveRatePairs(), team.exactAgeConsecutiveRatePairs(),
                team.playersWithExactAgeConsecutiveRatePair(), team.teamId());
            for (var position : team.positions().values()) {
                System.out.printf("  %s  players=%d  exact-birth=%d  player-seasons=%d  rate-seasons=%d  pairs=%d  exact-age-pairs=%d  players-with-pair=%d%n",
                    position.position(), position.totalPlayers(), position.exactBirthDatePlayers(),
                    position.productionPlayerSeasons(), position.rateEligiblePlayerSeasons(),
                    position.consecutiveRatePairs(), position.exactAgeConsecutiveRatePairs(),
                    position.playersWithExactAgeConsecutiveRatePair());
            }
            for (var player : team.players()) {
                System.out.printf("  player: %s  %s  exact-birth=%s  seasons=%s  rate-seasons=%s  pairs=%d  exact-age-pairs=%d  [%s]%n",
                    player.playerName(), player.position(), player.exactBirthDateAvailable(),
                    player.productionSeasons(), player.rateEligibleSeasons(), player.consecutiveRatePairs(),
                    player.exactAgeConsecutiveRatePairs(), player.playerId());
            }
        }
    }

    static void printUsage() {
        System.out.println("  butler league longitudinal-evidence <league-id>");
    }

    private static Database initializedDatabase() throws SQLException {
        Database database = new Database(DATABASE_PATH);
        database.initialize();
        return database;
    }
}
