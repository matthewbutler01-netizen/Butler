package io.butler.bet.cli;

import io.butler.bet.data.Database;
import io.butler.bet.intelligence.LeagueCompositeTeamProfileAnalyzer;

import java.nio.file.Path;
import java.sql.SQLException;
import java.util.List;
import java.util.Objects;

/**
 * Read-only exact franchise inspection over Butler's existing neutral composite team profile.
 */
public final class ButlerLeagueFranchiseDetailCli {
    private static final Path DATABASE_PATH = Path.of("butler.db");

    private ButlerLeagueFranchiseDetailCli() {}

    public static void main(String[] args) {
        try {
            Options options = parse(args);
            var report = new LeagueCompositeTeamProfileAnalyzer(initializedDatabase())
                .analyze(options.leagueId());
            print(select(options.teamId(), report));
        } catch (SQLException e) {
            System.err.println("Database error while building franchise detail evidence: " + e.getMessage());
            System.exit(1);
        } catch (IllegalArgumentException | IllegalStateException e) {
            System.err.println("Error: " + e.getMessage());
            System.exit(2);
        }
    }

    static Options parse(String[] args) {
        if (!isCommand(args) || args.length != 4) {
            throw new IllegalArgumentException(
                "Usage: butler league franchise-detail <league-id> <team-id>");
        }
        return new Options(requireText(args[2], "league-id"), requireText(args[3], "team-id"));
    }

    static boolean isCommand(String[] args) {
        return args != null && args.length >= 2
            && "league".equalsIgnoreCase(args[0])
            && "franchise-detail".equalsIgnoreCase(args[1]);
    }

    static FranchiseDetailReport select(
        String teamId,
        LeagueCompositeTeamProfileAnalyzer.CompositeProfileReport report) {
        String exactTeamId = requireText(teamId, "team-id");
        Objects.requireNonNull(report, "report must not be null");

        List<LeagueCompositeTeamProfileAnalyzer.TeamProfile> matches = report.teams().stream()
            .filter(team -> team.teamId().equals(exactTeamId))
            .toList();
        if (matches.size() != 1) {
            throw new IllegalArgumentException(
                "team must resolve exactly once in league team profile: " + exactTeamId
                    + " (matches=" + matches.size() + ")");
        }
        return new FranchiseDetailReport(
            report.leagueId(),
            report.source(),
            report.minimumAsOfDate(),
            matches.get(0));
    }

    static void print(FranchiseDetailReport report) {
        Objects.requireNonNull(report, "report must not be null");
        var team = report.team();
        var concentration = team.concentration();
        var roster = team.rosterSlots();
        var picks = team.draftCapital();

        int rosterValued = roster.slots().values().stream().mapToInt(slot -> slot.valuedPlayers()).sum();
        int rosterStale = roster.slots().values().stream().mapToInt(slot -> slot.stalePlayers()).sum();
        int rosterMissing = roster.slots().values().stream().mapToInt(slot -> slot.missingPlayers()).sum();
        int rosterTotal = roster.slots().values().stream().mapToInt(slot -> slot.totalPlayers()).sum();
        double rosterCoverage = rosterTotal == 0 ? 0.0 : rosterValued * 100.0 / rosterTotal;

        System.out.println("League franchise detail evidence");
        System.out.println("League ID: " + report.leagueId());
        System.out.println("Source: " + report.source());
        if (report.minimumAsOfDate() != null) {
            System.out.println("Minimum as-of: " + report.minimumAsOfDate());
        }
        System.out.println("Team ID: " + team.teamId());
        System.out.println("Team name: " + team.teamName());
        System.out.printf("Usable asset value: %.2f%n", team.usableAssetValue());
        System.out.printf("Usable player value: %.2f%n", team.usablePlayerValue());
        System.out.printf("Usable draft-pick value: %.2f%n", team.usableDraftPickValue());
        System.out.printf("Starter value share: %.1f%%%n", team.starterValueSharePercent());
        System.out.printf("Top asset share: %.1f%%%n", team.topAssetSharePercent());
        System.out.printf("Top three asset share: %.1f%%%n", team.topThreeAssetSharePercent());
        System.out.printf("Concentration index: %.4f%n", team.concentrationIndex());
        System.out.printf("Asset coverage: valued=%d total=%d stale=%d missing=%d percent=%.1f%%%n",
            concentration.valuedAssets(), concentration.totalAssets(), concentration.staleAssets(),
            concentration.missingAssets(), concentration.coveragePercent());
        System.out.printf("Roster coverage: valued=%d total=%d stale=%d missing=%d percent=%.1f%%%n",
            rosterValued, rosterTotal, rosterStale, rosterMissing, rosterCoverage);
        System.out.printf("Draft coverage: valued=%d total=%d stale=%d missing=%d percent=%.1f%% seasons=%d%n",
            picks.valuedPicks(), picks.totalPicks(), picks.stalePicks(), picks.missingPicks(),
            picks.coveragePercent(), picks.seasons().size());

        team.positionalDepth().positions().values().stream()
            .sorted(java.util.Comparator.comparing(
                io.butler.bet.intelligence.LeaguePositionalDepthAnalyzer.PositionDepth::position))
            .forEach(position -> System.out.printf(
                "Position: %s | players=%d | valued=%d | stale=%d | missing=%d | coverage=%.1f%% | value=%.2f | top1=%.1f%% | top3=%.1f%%%n",
                position.position(), position.totalPlayers(), position.valuedPlayers(), position.stalePlayers(),
                position.missingPlayers(), position.coveragePercent(), position.totalUsableValue(),
                position.topOneSharePercent(), position.topThreeSharePercent()));

        System.out.println(
            "Neutral franchise evidence only; no new ranking, contender/rebuilder label, trade-target label, manager grade, or strategy recommendation is produced.");
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

    record Options(String leagueId, String teamId) {}

    record FranchiseDetailReport(
        String leagueId,
        String source,
        java.time.LocalDate minimumAsOfDate,
        LeagueCompositeTeamProfileAnalyzer.TeamProfile team) {
        FranchiseDetailReport {
            Objects.requireNonNull(team, "team must not be null");
        }
    }
}
