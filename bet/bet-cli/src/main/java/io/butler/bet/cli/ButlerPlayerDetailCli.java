package io.butler.bet.cli;

import io.butler.bet.data.Database;
import io.butler.bet.intelligence.DecisionSupportingEvidenceFlag;
import io.butler.bet.intelligence.LeagueAgeOutlookSupportingEvidenceAnalyzer;
import io.butler.bet.intelligence.LeagueAgeProductionContextAnalyzer;
import io.butler.bet.intelligence.LeaguePlayerEvidenceProfileAnalyzer;

import java.nio.file.Path;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

/**
 * Read-only exact player evidence surface.
 *
 * This CLI composes existing neutral age/production and supporting-evidence dimensions. It does
 * not create a blended score, player grade, rank, dynasty adjustment, strategy label, or
 * recommendation.
 */
public final class ButlerPlayerDetailCli {
    private static final Path DATABASE_PATH = Path.of("butler.db");

    private ButlerPlayerDetailCli() {}

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
            print(analyze(initializedDatabase(), options));
        } catch (SQLException e) {
            System.err.println("Database error while building player detail: " + e.getMessage());
            System.exit(1);
        } catch (IllegalArgumentException | IllegalStateException e) {
            System.err.println("Error: " + e.getMessage());
            System.exit(2);
        }
    }

    static boolean isCommand(String[] args) {
        return args != null && args.length >= 2
            && "league".equalsIgnoreCase(args[0])
            && "player-detail".equalsIgnoreCase(args[1]);
    }

    static Options parse(String[] args) {
        if (!isCommand(args) || (args.length != 4 && args.length != 5)) {
            throw new IllegalArgumentException(
                "league player-detail requires league id, Butler player id, and optional season");
        }
        String leagueId = requireText(args[2], "league-id");
        String playerId = requireText(args[3], "player-id");
        Integer season = args.length == 5 ? parseSeason(args[4]) : null;
        return new Options(leagueId, playerId, season);
    }

    static PlayerDetailReport analyze(Database database, Options options) throws SQLException {
        Objects.requireNonNull(database, "database must not be null");
        Objects.requireNonNull(options, "options must not be null");

        var ageProductionAnalyzer = new LeagueAgeProductionContextAnalyzer(database);
        var profileAnalyzer = new LeaguePlayerEvidenceProfileAnalyzer(database);
        var ageProduction = options.season() == null
            ? ageProductionAnalyzer.analyze(options.leagueId())
            : ageProductionAnalyzer.analyze(options.leagueId(), options.season());
        var profile = profileAnalyzer.analyze(options.leagueId(), ageProduction.season());

        if (!ageProduction.leagueId().equals(profile.leagueId())
            || ageProduction.season() != profile.season()) {
            throw new IllegalStateException("player detail evidence dimensions reference different coordinates");
        }

        List<PlayerMatch> matches = new ArrayList<>();
        for (var team : ageProduction.teams()) {
            for (var player : team.players()) {
                if (player.playerId().equals(options.playerId())) {
                    matches.add(new PlayerMatch(team.teamId(), team.teamName(), player));
                }
            }
        }
        if (matches.size() != 1) {
            throw new IllegalStateException(
                "player detail requires exactly one league player match; found " + matches.size());
        }
        var selected = matches.getFirst();

        var profileTeams = profile.teams().stream()
            .filter(team -> team.teamId().equals(selected.teamId()))
            .toList();
        if (profileTeams.size() != 1) {
            throw new IllegalStateException(
                "player detail supporting evidence requires exactly one matching team");
        }
        var profileTeam = profileTeams.getFirst();
        if (!profileTeam.teamName().equals(selected.teamName())) {
            throw new IllegalStateException("player detail team identity differs across evidence dimensions");
        }

        Integer modelAge = null;
        List<DecisionSupportingEvidenceFlag> flags = List.of();
        if (!profileTeam.supportingEvidence().isEmpty()) {
            var supportingMatches = profileTeam.supportingEvidence().stream()
                .filter(player -> player.playerId().equals(options.playerId()))
                .toList();
            if (supportingMatches.size() != 1) {
                throw new IllegalStateException(
                    "player detail supporting evidence requires exactly one matching player");
            }
            LeagueAgeOutlookSupportingEvidenceAnalyzer.PlayerSupportingEvidence supporting =
                supportingMatches.getFirst();
            if (!supporting.playerName().equals(selected.player().playerName())
                || !supporting.position().equals(selected.player().position())) {
                throw new IllegalStateException(
                    "player detail player identity differs across evidence dimensions");
            }
            modelAge = supporting.modelAge();
            flags = supporting.flags();
            for (var flag : flags) {
                if (!flag.subjectId().equals(options.playerId())) {
                    throw new IllegalStateException("player detail supporting flag references another player");
                }
            }
        }

        return new PlayerDetailReport(
            ageProduction.leagueId(),
            ageProduction.season(),
            selected.teamId(),
            selected.teamName(),
            selected.player(),
            ageProduction.ageAsOf(),
            ageProduction.profileSource(),
            ageProduction.minimumProfileAsOf(),
            ageProduction.productionSource(),
            profile.modelAgeAsOf(),
            modelAge,
            profile.supportPolicyId(),
            profile.outlookPolicyId(),
            profile.modelProfileSource(),
            profile.modelProductionSource(),
            List.copyOf(flags));
    }

    static void print(PlayerDetailReport report) {
        Objects.requireNonNull(report, "report must not be null");
        var player = report.player();

        System.out.println("Player detail (read-only neutral evidence)");
        System.out.println("League ID: " + report.leagueId());
        System.out.println("Season: " + report.season());
        System.out.println("Team: " + report.teamName() + " [" + report.teamId() + "]");
        System.out.println("Player: " + player.playerName() + " [" + player.playerId() + "]");
        System.out.println("Position: " + player.position());
        System.out.println("Roster slot: " + player.rosterSlot());
        System.out.println("Age as-of: " + report.ageAsOf());
        System.out.println("Age: " + formatInteger(player.age()));
        System.out.println("Age provenance: " + player.ageProvenance());
        System.out.println("Profile source: " + report.profileSource());
        if (report.minimumProfileAsOf() != null) {
            System.out.println("Minimum profile as-of: " + report.minimumProfileAsOf());
        }
        System.out.println("Production source: " + report.productionSource());
        System.out.println("Production snapshot: "
            + (player.productionSnapshotAvailable() ? "AVAILABLE" : "MISSING"));
        System.out.println("Games played: " + player.gamesPlayed());
        printRate("Passing yards/game", player.passingYardsPerGame());
        printRate("Passing TD/game", player.passingTouchdownsPerGame());
        printRate("Interceptions/game", player.interceptionsPerGame());
        printRate("Rushing yards/game", player.rushingYardsPerGame());
        printRate("Rushing TD/game", player.rushingTouchdownsPerGame());
        printRate("Receptions/game", player.receptionsPerGame());
        printRate("Receiving yards/game", player.receivingYardsPerGame());
        printRate("Receiving TD/game", player.receivingTouchdownsPerGame());
        printRate("Fumbles lost/game", player.fumblesLostPerGame());

        System.out.println("Supporting model age as-of: " + report.modelAgeAsOf());
        System.out.println("Supporting model age: " + formatInteger(report.modelAge()));
        System.out.println("Supporting flags: " + report.flags().size());
        for (var flag : report.flags()) {
            System.out.println("Supporting flag: category=" + flag.category()
                + " dimension=" + flag.dimension()
                + " signal=" + flag.signal()
                + " policy=" + flag.policyId()
                + " source=" + flag.evidenceSource()
                + " summary=" + flag.summary());
        }
        System.out.println("Supporting policy: " + report.supportPolicyId());
        System.out.println("Age outlook policy: " + report.outlookPolicyId());
        System.out.println("Aging model sources: "
            + report.modelProfileSource() + "+" + report.modelProductionSource());
        System.out.println(
            "Boundary: age, production, and supporting flags remain independent evidence dimensions; "
                + "no blended score, grade, rank, dynasty adjustment, or recommendation is produced.");
    }

    private static void printRate(String label, Double value) {
        System.out.println(label + ": " + formatRate(value));
    }

    private static String formatRate(Double value) {
        return value == null ? "-" : String.format(Locale.ROOT, "%.2f", value);
    }

    private static String formatInteger(Integer value) {
        return value == null ? "-" : value.toString();
    }

    static void printUsage() {
        System.out.println(
            "  butler league player-detail <league-id> <player-id> [season]");
    }

    private static Database initializedDatabase() throws SQLException {
        Database database = new Database(DATABASE_PATH);
        database.initialize();
        return database;
    }

    private static int parseSeason(String value) {
        try {
            int season = Integer.parseInt(value);
            if (season < 1999 || season > 2100) {
                throw new NumberFormatException();
            }
            return season;
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException(
                "season must be a year between 1999 and 2100: " + value);
        }
    }

    private static String requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
        return value.trim();
    }

    record Options(String leagueId, String playerId, Integer season) {}

    private record PlayerMatch(
        String teamId,
        String teamName,
        LeagueAgeProductionContextAnalyzer.PlayerAgeProductionContext player) {}

    record PlayerDetailReport(
        String leagueId,
        int season,
        String teamId,
        String teamName,
        LeagueAgeProductionContextAnalyzer.PlayerAgeProductionContext player,
        java.time.LocalDate ageAsOf,
        String profileSource,
        java.time.LocalDate minimumProfileAsOf,
        String productionSource,
        java.time.LocalDate modelAgeAsOf,
        Integer modelAge,
        String supportPolicyId,
        String outlookPolicyId,
        String modelProfileSource,
        String modelProductionSource,
        List<DecisionSupportingEvidenceFlag> flags) {

        PlayerDetailReport {
            Objects.requireNonNull(leagueId, "leagueId must not be null");
            Objects.requireNonNull(teamId, "teamId must not be null");
            Objects.requireNonNull(teamName, "teamName must not be null");
            Objects.requireNonNull(player, "player must not be null");
            Objects.requireNonNull(ageAsOf, "ageAsOf must not be null");
            Objects.requireNonNull(profileSource, "profileSource must not be null");
            Objects.requireNonNull(productionSource, "productionSource must not be null");
            Objects.requireNonNull(modelAgeAsOf, "modelAgeAsOf must not be null");
            Objects.requireNonNull(supportPolicyId, "supportPolicyId must not be null");
            Objects.requireNonNull(outlookPolicyId, "outlookPolicyId must not be null");
            Objects.requireNonNull(modelProfileSource, "modelProfileSource must not be null");
            Objects.requireNonNull(modelProductionSource, "modelProductionSource must not be null");
            flags = List.copyOf(Objects.requireNonNull(flags, "flags must not be null"));
        }
    }
}
