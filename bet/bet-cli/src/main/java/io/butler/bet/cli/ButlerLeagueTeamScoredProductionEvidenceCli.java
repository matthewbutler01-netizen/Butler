package io.butler.bet.cli;

import io.butler.bet.data.Database;
import io.butler.bet.intelligence.LeagueTeamScoredProductionEvidenceAnalyzer;

import java.math.BigDecimal;
import java.nio.file.Path;
import java.sql.SQLException;

/** Read-only team observed scored-production evidence; never a lineup-strength ranking. */
public final class ButlerLeagueTeamScoredProductionEvidenceCli {
    private static final Path DATABASE_PATH = Path.of("butler.db");

    private ButlerLeagueTeamScoredProductionEvidenceCli() {}

    public static void main(String[] args) {
        try {
            Options options = parse(args);
            print(new LeagueTeamScoredProductionEvidenceAnalyzer(initializedDatabase()).analyze(
                options.leagueId(), options.season(), options.source()));
        } catch (SQLException e) {
            System.err.println("Database error while building team scored-production evidence: " + e.getMessage());
            System.exit(1);
        } catch (IllegalArgumentException | IllegalStateException e) {
            System.err.println("Error: " + e.getMessage());
            System.exit(2);
        }
    }

    static Options parse(String[] args) {
        if (!isCommand(args) || args.length != 5) {
            throw new IllegalArgumentException(
                "Usage: butler league team-scored-production-evidence <league-id> <season> <source>");
        }
        return new Options(requireText(args[2], "league-id"), parseSeason(args[3]), requireText(args[4], "source"));
    }

    static boolean isCommand(String[] args) {
        return args != null && args.length >= 2
            && "league".equalsIgnoreCase(args[0])
            && "team-scored-production-evidence".equalsIgnoreCase(args[1]);
    }

    static void print(LeagueTeamScoredProductionEvidenceAnalyzer.TeamEvidenceReport report) {
        if (report == null) throw new IllegalArgumentException("report must not be null");
        System.out.println("League team observed scored-production evidence");
        System.out.println("Policy: " + report.policyId());
        System.out.println("Player evidence policy: " + report.playerEvidencePolicyId());
        System.out.println("Coverage policy: " + report.coveragePolicyId());
        System.out.println("Scoring policy: " + report.scoringPolicyId());
        System.out.println("League: " + report.leagueName() + " (" + report.leagueId() + ")");
        System.out.println("Season: " + report.season());
        System.out.println("Source: " + report.source());
        System.out.println("Team | Observed fantasy points | Coverage | Complete");
        for (var team : report.teams()) {
            System.out.printf("%s [%s] | %s | %d/%d (%.1f%%) | %s%n",
                team.teamName(), team.teamId(), format(team.observedFantasyPoints()),
                team.coveredPlayers(), team.totalPlayers(), team.coveragePercent(), team.complete());
        }
        System.out.println("Observed full-roster historical production only; teams are not score-ranked and these totals are not lineup strength or a recommendation.");
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
