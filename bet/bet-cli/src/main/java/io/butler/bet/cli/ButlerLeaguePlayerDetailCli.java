package io.butler.bet.cli;

import io.butler.bet.data.Database;
import io.butler.bet.intelligence.DecisionSupportingEvidenceFlag;
import io.butler.bet.intelligence.LeagueAgeOutlookSupportingEvidenceAnalyzer;
import io.butler.bet.intelligence.LeagueAgeProductionContextAnalyzer;
import io.butler.bet.intelligence.LeaguePlayerEvidenceProfileAnalyzer;

import java.nio.file.Path;
import java.sql.SQLException;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Read-only manager-facing inspection of one player's existing neutral evidence.
 *
 * This command deliberately does not score, rank, grade, adjust, or recommend the player.
 */
public final class ButlerLeaguePlayerDetailCli {
    private static final Path DATABASE_PATH = Path.of("butler.db");

    private ButlerLeaguePlayerDetailCli() {}

    public static void main(String[] args) {
        try {
            Options options = parse(args);
            Database database = initializedDatabase();
            var ageProduction = new LeagueAgeProductionContextAnalyzer(database);
            var profiles = new LeaguePlayerEvidenceProfileAnalyzer(database);
            LocalDate ageAsOf = LocalDate.now(ZoneOffset.UTC);
            var ageReport = options.season() == null
                ? ageProduction.analyze(options.leagueId(), ageAsOf, null)
                : ageProduction.analyze(options.leagueId(), options.season(), ageAsOf, null);
            var profileReport = options.season() == null
                ? profiles.analyze(options.leagueId(), ageAsOf, null)
                : profiles.analyze(options.leagueId(), options.season(), ageAsOf, null);
            print(select(options.playerId(), ageReport, profileReport));
        } catch (SQLException e) {
            System.err.println("Database error while building player detail evidence: " + e.getMessage());
            System.exit(1);
        } catch (IllegalArgumentException | IllegalStateException e) {
            System.err.println("Error: " + e.getMessage());
            System.exit(2);
        }
    }

    static Options parse(String[] args) {
        if (!isCommand(args) || (args.length != 4 && args.length != 5)) {
            throw new IllegalArgumentException(
                "Usage: butler league player-detail <league-id> <player-id> [season]");
        }
        return new Options(
            requireText(args[2], "league-id"),
            requireText(args[3], "player-id"),
            args.length == 5 ? parseSeason(args[4]) : null);
    }

    static boolean isCommand(String[] args) {
        return args != null && args.length >= 2
            && "league".equalsIgnoreCase(args[0])
            && "player-detail".equalsIgnoreCase(args[1]);
    }

    static PlayerDetailReport select(
        String playerId,
        LeagueAgeProductionContextAnalyzer.AgeProductionReport ageReport,
        LeaguePlayerEvidenceProfileAnalyzer.PlayerEvidenceProfileReport profileReport) {
        String exactPlayerId = requireText(playerId, "player-id");
        Objects.requireNonNull(ageReport, "ageReport must not be null");
        Objects.requireNonNull(profileReport, "profileReport must not be null");

        if (!ageReport.leagueId().equals(profileReport.leagueId())) {
            throw new IllegalStateException("player detail evidence dimensions reference different leagues");
        }
        if (ageReport.season() != profileReport.season()) {
            throw new IllegalStateException("player detail evidence dimensions reference different seasons");
        }
        if (!ageReport.ageAsOf().equals(profileReport.ageAsOf())) {
            throw new IllegalStateException("player detail evidence dimensions use different age as-of dates");
        }

        List<SelectedAgePlayer> matches = new ArrayList<>();
        for (var team : ageReport.teams()) {
            for (var player : team.players()) {
                if (player.playerId().equals(exactPlayerId)) {
                    matches.add(new SelectedAgePlayer(team.teamId(), team.teamName(), player));
                }
            }
        }
        if (matches.size() != 1) {
            throw new IllegalArgumentException(
                "player must resolve exactly once in league age/production evidence: " + exactPlayerId
                    + " (matches=" + matches.size() + ")");
        }

        SelectedAgePlayer selected = matches.get(0);
        var profileTeam = profileReport.teams().stream()
            .filter(team -> team.teamId().equals(selected.teamId()))
            .findFirst()
            .orElseThrow(() -> new IllegalStateException(
                "supporting evidence is missing selected Butler team: " + selected.teamId()));

        List<LeagueAgeOutlookSupportingEvidenceAnalyzer.PlayerSupportingEvidence> supportingMatches =
            profileTeam.supportingEvidence().stream()
                .filter(player -> player.playerId().equals(playerId))
                .toList();
        if (supportingMatches.size() > 1) {
            throw new IllegalStateException(
                "supporting evidence resolved selected player more than once: " + exactPlayerId);
        }

        LeagueAgeOutlookSupportingEvidenceAnalyzer.PlayerSupportingEvidence supporting =
            supportingMatches.isEmpty() ? null : supportingMatches.get(0);
        if (supporting != null) {
            if (!supporting.teamId().equals(selected.teamId())
                || !supporting.teamName().equals(selected.teamName())
                || !supporting.playerName().equals(selected.player().playerName())
                || !supporting.position().equals(selected.player().position())) {
                throw new IllegalStateException(
                    "supporting evidence identity does not match selected player: " + exactPlayerId);
            }
        }

        return new PlayerDetailReport(
            ageReport.leagueId(),
            ageReport.season(),
            ageReport.ageAsOf(),
            ageReport.profileSource(),
            ageReport.minimumProfileAsOf(),
            ageReport.productionSource(),
            selected.teamId(),
            selected.teamName(),
            selected.player(),
            profileReport.modelAgeAsOf(),
            profileReport.supportPolicyId(),
            profileReport.outlookPolicyId(),
            profileReport.modelProfileSource(),
            profileReport.modelProductionSource(),
            supporting == null ? List.of() : supporting.flags());
    }

    static void print(PlayerDetailReport report) {
        Objects.requireNonNull(report, "report must not be null");
        var player = report.player();

        System.out.println("League player detail evidence");
        System.out.println("League ID: " + report.leagueId());
        System.out.println("Season: " + report.season());
        System.out.println("Player ID: " + player.playerId());
        System.out.println("Player name: " + player.playerName());
        System.out.println("Position: " + player.position());
        System.out.println("Butler team: " + report.teamName() + " [" + report.teamId() + "]");
        System.out.println("Roster slot: " + value(player.rosterSlot()));
        System.out.println("Age: " + value(player.age()));
        System.out.println("Age provenance: " + player.ageProvenance());
        System.out.println("Age as-of: " + report.ageAsOf());
        System.out.println("Profile source: " + report.profileSource());
        if (report.minimumProfileAsOf() != null) {
            System.out.println("Minimum profile as-of: " + report.minimumProfileAsOf());
        }
        System.out.println("Production source: " + report.productionSource());
        System.out.println("Production snapshot: " + (player.productionAvailable() ? "AVAILABLE" : "MISSING"));
        System.out.println("Games played: " + (player.productionAvailable() ? player.gamesPlayed() : "UNAVAILABLE"));
        System.out.println("Passing yards/game: " + rate(player.passingYardsPerGame()));
        System.out.println("Passing TD/game: " + rate(player.passingTouchdownsPerGame()));
        System.out.println("Interceptions/game: " + rate(player.interceptionsPerGame()));
        System.out.println("Rushing yards/game: " + rate(player.rushingYardsPerGame()));
        System.out.println("Rushing TD/game: " + rate(player.rushingTouchdownsPerGame()));
        System.out.println("Receptions/game: " + rate(player.receptionsPerGame()));
        System.out.println("Receiving yards/game: " + rate(player.receivingYardsPerGame()));
        System.out.println("Receiving TD/game: " + rate(player.receivingTouchdownsPerGame()));
        System.out.println("Fumbles lost/game: " + rate(player.fumblesLostPerGame()));
        System.out.println("Supporting flags: " + report.supportingFlags().size());
        System.out.println("Supporting model age as-of: " + report.modelAgeAsOf());
        System.out.println("Supporting policy: " + report.supportPolicyId());
        System.out.println("Outlook policy: " + report.outlookPolicyId());
        System.out.println("Supporting sources: " + report.modelProfileSource() + "+" + report.modelProductionSource());
        for (DecisionSupportingEvidenceFlag flag : report.supportingFlags()) {
            System.out.println("  " + flag.signal() + " | " + flag.category() + " | " + flag.dimension()
                + " | " + flag.summary());
        }
        System.out.println(
            "Neutral evidence only; no universal score, grade, rank, buy/sell label, dynasty adjustment, start/sit, trade, or waiver recommendation is produced.");
    }

    private static String value(Object value) {
        if (value == null) return "UNAVAILABLE";
        String text = value.toString();
        return text.isBlank() ? "UNAVAILABLE" : text;
    }

    private static String rate(Double value) {
        return value == null ? "UNAVAILABLE" : String.format(java.util.Locale.ROOT, "%.3f", value);
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

    record Options(String leagueId, String playerId, Integer season) {}

    private record SelectedAgePlayer(
        String teamId,
        String teamName,
        LeagueAgeProductionContextAnalyzer.PlayerAgeProductionContext player) {}

    record PlayerDetailReport(
        String leagueId,
        int season,
        java.time.LocalDate ageAsOf,
        String profileSource,
        java.time.LocalDate minimumProfileAsOf,
        String productionSource,
        String teamId,
        String teamName,
        LeagueAgeProductionContextAnalyzer.PlayerAgeProductionContext player,
        java.time.LocalDate modelAgeAsOf,
        String supportPolicyId,
        String outlookPolicyId,
        String modelProfileSource,
        String modelProductionSource,
        List<DecisionSupportingEvidenceFlag> supportingFlags) {
        PlayerDetailReport {
            supportingFlags = List.copyOf(Objects.requireNonNull(supportingFlags, "supportingFlags must not be null"));
        }
    }
}
