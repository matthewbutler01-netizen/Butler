package io.butler.bet.cli;

import io.butler.bet.data.Database;
import io.butler.bet.intelligence.LeagueTeamWeekStartedLineupEvidenceAnalyzer;

import java.math.BigDecimal;
import java.nio.file.Path;
import java.sql.SQLException;

/** Exposes governed recalculated team-week started-lineup evidence without manager judgment. */
public final class ButlerLeagueTeamWeekStartedLineupEvidenceCli {
    private static final Path DATABASE_PATH = Path.of("butler.db");
    private static final String COMMAND = "team-week-started-lineup-evidence";

    private ButlerLeagueTeamWeekStartedLineupEvidenceCli() {}

    public static void main(String[] args) {
        try {
            Options options = parse(args);
            print(new LeagueTeamWeekStartedLineupEvidenceAnalyzer(initializedDatabase()).analyze(
                options.leagueId(), options.teamId(), options.season(), options.week()));
        } catch (SQLException e) {
            System.err.println("Database error while building team-week started-lineup evidence: " + e.getMessage());
            System.exit(1);
        } catch (IllegalArgumentException | IllegalStateException e) {
            System.err.println("Error: " + e.getMessage());
            System.exit(2);
        }
    }

    static Options parse(String[] args) {
        if (!isCommand(args) || args.length != 6) {
            throw new IllegalArgumentException(
                "Usage: butler league team-week-started-lineup-evidence <league-id> <team-id> <season> <week>");
        }
        String leagueId = requireText(args[2], "league-id");
        String teamId = requireText(args[3], "team-id");
        int season = parseInt(args[4], "season");
        int week = parseInt(args[5], "week");
        if (season < 1999 || season > 2100) {
            throw new IllegalArgumentException("season must be between 1999 and 2100");
        }
        if (week <= 0) throw new IllegalArgumentException("week must be positive");
        return new Options(leagueId, teamId, season, week);
    }

    static boolean isCommand(String[] args) {
        return args != null && args.length >= 2
            && "league".equalsIgnoreCase(args[0])
            && COMMAND.equalsIgnoreCase(args[1]);
    }

    static void print(LeagueTeamWeekStartedLineupEvidenceAnalyzer.StartedLineupReport report) {
        System.out.println("Team-week started-lineup evidence");
        System.out.println("League: " + report.leagueId());
        System.out.println("Team: " + report.teamId());
        System.out.println("Season/week: " + report.season() + "/" + report.week());
        System.out.println("Metric scope: " + report.metricScope());
        System.out.println("Interpretation: exact persisted Sleeper starter order scored from governed production evidence; "
            + "not provider-reported matchup points.");
        System.out.println();
        System.out.println("Policies:");
        System.out.println("  calculation: " + report.policyId());
        System.out.println("  coverage: " + report.coveragePolicyId());
        System.out.println("  scoring: " + report.scoringPolicyId());
        System.out.println("  eligibility: " + report.eligibilityPolicyId());
        System.out.println();
        System.out.println("Evidence provenance:");
        System.out.println("  league configuration as-of: " + report.leagueConfigurationAsOf());
        System.out.println("  roster evidence as-of: " + report.rosterEvidenceAsOf());
        System.out.println("  production coverage as-of: " + report.productionCoverageAsOf());
        System.out.println("  production source: " + report.productionSourceUri());
        System.out.println();
        System.out.println("Observed starting slots:");
        for (var slot : report.slots()) {
            if (slot.state() == LeagueTeamWeekStartedLineupEvidenceAnalyzer.StartedSlotState.EMPTY) {
                System.out.println("  #" + slot.ordinal() + " " + slot.slot()
                    + " -> EMPTY (Sleeper starter sentinel 0; no player production assigned)");
            } else {
                var score = slot.scoreEvidence();
                System.out.println("  #" + slot.ordinal() + " " + slot.slot()
                    + " -> Sleeper " + slot.providerStarterId() + " / Butler " + slot.playerId()
                    + " | " + points(slot.fantasyPoints()));
                System.out.println("    eligibility as-of: " + score.eligibilityObservationAsOf());
                System.out.println("    fantasy positions: " + score.providerFantasyPositions());
                System.out.println("    production state: " + score.productionState());
                System.out.println("    production coverage as-of: " + score.productionCoverageAsOf());
                if (score.productionId() == null) {
                    System.out.println("    production id: none (identity-covered zero)");
                    System.out.println("    scoring policy: none (zero authorized by coverage evidence)");
                } else {
                    System.out.println("    production id: " + score.productionId());
                    System.out.println("    scoring policy: " + score.scoringPolicyId());
                }
            }
        }
        System.out.println("Filled starter slots: " + report.filledSlots() + "/" + report.requiredSlots());
        System.out.println("Complete observed starting lineup: " + report.complete());
        System.out.println("Total recalculated started points: " + points(report.totalStartedPoints()));
        System.out.println();
        System.out.println("Boundary: started-lineup evidence only; no potential-vs-started comparison, "
            + "manager-efficiency score, rank, tier, recommendation, or intent inference is computed.");
    }

    private static Database initializedDatabase() throws SQLException {
        Database database = new Database(DATABASE_PATH);
        database.initialize();
        return database;
    }

    private static String points(BigDecimal value) {
        return value.stripTrailingZeros().toPlainString();
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

    record Options(String leagueId, String teamId, int season, int week) {}
}
