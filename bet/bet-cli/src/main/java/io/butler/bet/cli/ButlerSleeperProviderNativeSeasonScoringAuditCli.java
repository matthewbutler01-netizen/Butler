package io.butler.bet.cli;

import io.butler.bet.data.Database;
import io.butler.bet.sleeper.SleeperProviderNativeSeasonScoringAudit;

import java.nio.file.Path;

/** Read-only BF-566 operator surface for proving the provider-native historical scoring lane. */
public final class ButlerSleeperProviderNativeSeasonScoringAuditCli {
    private static final Path DATABASE_PATH = Path.of("butler.db");

    private ButlerSleeperProviderNativeSeasonScoringAuditCli() {}

    public static void main(String[] args) {
        try {
            Options options = parse(args);
            Database database = new Database(DATABASE_PATH);
            database.initialize();
            print(new SleeperProviderNativeSeasonScoringAudit(database)
                .audit(options.leagueId(), options.season()));
        } catch (Exception e) {
            System.err.println("Error: " + e.getMessage());
            System.exit(2);
        }
    }

    static Options parse(String[] args) {
        if (args == null || args.length != 2) {
            throw new IllegalArgumentException(
                "Usage: sleeperProviderNativeSeasonScoringAudit <butler-league-id> <season>");
        }
        String leagueId = requireText(args[0], "butler-league-id");
        int season = parseInt(args[1], "season");
        if (season < 1999 || season > 2100) {
            throw new IllegalArgumentException("season must be between 1999 and 2100");
        }
        return new Options(leagueId, season);
    }

    static void print(SleeperProviderNativeSeasonScoringAudit.AuditReport report) {
        System.out.println("Sleeper provider-native season scoring audit");
        System.out.println("Policy: " + report.policyId());
        System.out.println("League: " + report.leagueName() + " [" + report.leagueId() + "]");
        System.out.println("Season: " + report.season());
        System.out.println("Provider source: " + report.source());
        System.out.println("Provider surface: " + value(report.sourceSurface()));
        System.out.println("Historical provider league: " + value(report.providerLeagueId()));
        System.out.println("Provider points as-of: " + value(report.providerPointsAsOf()));
        System.out.println("Audited team-weeks: " + report.observedTeamWeeks());
        System.out.println("Ready team-weeks: " + report.readyTeamWeeks());
        System.out.println("Roster candidate identities: " + report.candidateIdentities());
        System.out.println("Scored identities: " + report.scoredIdentities());
        System.out.println("Audit state: " + report.state());
        if (!report.blockers().isEmpty()) {
            System.out.println("Global blockers:");
            report.blockers().forEach(blocker -> System.out.println("  - " + blocker));
        }

        System.out.println();
        System.out.println("Week | Team | state | roster ids | provider rows | canonical/evidence roster | missing | extra | scores | blockers");
        for (var teamWeek : report.teamWeeks()) {
            String scores = teamWeek.scores().stream()
                .map(score -> score.providerPlayerId() + "=" + score.points().toPlainString())
                .reduce((left, right) -> left + "," + right)
                .orElse("none");
            System.out.println(teamWeek.week()
                + " | " + teamWeek.teamName() + " [" + teamWeek.teamId() + "]"
                + " | " + teamWeek.state()
                + " | " + teamWeek.candidateIdentityCount()
                + " | " + teamWeek.providerPointRowCount()
                + " | " + value(teamWeek.expectedProviderRosterId()) + "/" + value(teamWeek.observedProviderRosterId())
                + " | " + (teamWeek.missingProviderPlayerIds().isEmpty() ? "none" : teamWeek.missingProviderPlayerIds())
                + " | " + (teamWeek.extraProviderPlayerIds().isEmpty() ? "none" : teamWeek.extraProviderPlayerIds())
                + " | " + scores
                + " | " + (teamWeek.blockers().isEmpty() ? "none" : teamWeek.blockers()));
        }

        System.out.println();
        System.out.println("Boundary: read-only provider-native scoring proof. READY requires exact roster/provider identity parity for every audited team-week under one persisted Sleeper provider snapshot. Missing or extra evidence is never treated as zero. This command does not alter potential-lineup scoring, K/DEF eligibility, BF-518/BF-521 readiness, nflverse scoring, rankings, confidence, manager attribution, or recommendations.");
    }

    private static String value(Object value) {
        return value == null ? "n/a" : value.toString();
    }

    private static String requireText(String value, String field) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(field + " must not be blank");
        return value.trim();
    }

    private static int parseInt(String value, String field) {
        try {
            return Integer.parseInt(requireText(value, field));
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException(field + " must be an integer: " + value, e);
        }
    }

    record Options(String leagueId, int season) {}
}
