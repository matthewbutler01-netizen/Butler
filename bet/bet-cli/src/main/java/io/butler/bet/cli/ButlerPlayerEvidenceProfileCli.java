package io.butler.bet.cli;

import io.butler.bet.data.Database;
import io.butler.bet.intelligence.LeaguePlayerEvidenceProfileAnalyzer;

import java.nio.file.Path;
import java.sql.SQLException;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.format.DateTimeParseException;

/** CLI leaf for the neutral player-evidence profile. */
public final class ButlerPlayerEvidenceProfileCli {
    private static final Path DATABASE_PATH = Path.of("butler.db");

    private ButlerPlayerEvidenceProfileCli() {}

    public static void main(String[] args) {
        PlayerEvidenceProfileOptions options;
        try {
            options = parse(args);
        } catch (IllegalArgumentException e) {
            System.err.println("Error: " + e.getMessage());
            printUsage();
            return;
        }

        try {
            print(analyze(options));
        } catch (SQLException e) {
            System.err.println("Database error while building player evidence profile: " + e.getMessage());
            System.exit(1);
        } catch (IllegalArgumentException | IllegalStateException e) {
            System.err.println("Error: " + e.getMessage());
            System.exit(2);
        }
    }

    static boolean isCommand(String[] args) {
        return args != null && args.length >= 2
            && args[0].equalsIgnoreCase("league")
            && args[1].equalsIgnoreCase("player-evidence-profile");
    }

    static PlayerEvidenceProfileOptions parse(String[] args) {
        if (!isCommand(args) || args.length < 3) {
            throw new IllegalArgumentException("league player-evidence-profile requires a league id");
        }
        String leagueId = requireText(args[2], "league-id");
        Integer season = null;
        LocalDate ageAsOf = null;
        LocalDate minimumProfileAsOf = null;
        int index = 3;
        if (index < args.length && !args[index].startsWith("--")) {
            season = parseSeason(args[index++]);
        }
        while (index < args.length) {
            String flag = args[index++];
            if (index >= args.length) throw new IllegalArgumentException("missing date after " + flag);
            LocalDate date = parseDate(args[index++], flag);
            if (flag.equalsIgnoreCase("--age-as-of")) {
                if (ageAsOf != null) throw new IllegalArgumentException("duplicate --age-as-of");
                ageAsOf = date;
            } else if (flag.equalsIgnoreCase("--minimum-profile-as-of")) {
                if (minimumProfileAsOf != null) throw new IllegalArgumentException("duplicate --minimum-profile-as-of");
                minimumProfileAsOf = date;
            } else {
                throw new IllegalArgumentException("unsupported player-evidence-profile option: " + flag);
            }
        }
        return new PlayerEvidenceProfileOptions(leagueId, season, ageAsOf, minimumProfileAsOf);
    }

    private static LeaguePlayerEvidenceProfileAnalyzer.PlayerEvidenceProfileReport analyze(PlayerEvidenceProfileOptions options)
        throws SQLException {
        LeaguePlayerEvidenceProfileAnalyzer analyzer = new LeaguePlayerEvidenceProfileAnalyzer(initializedDatabase());
        if (options.ageAsOf() == null && options.minimumProfileAsOf() == null) {
            return options.season() == null
                ? analyzer.analyze(options.leagueId())
                : analyzer.analyze(options.leagueId(), options.season());
        }
        LocalDate ageAsOf = options.ageAsOf() == null ? LocalDate.now(ZoneOffset.UTC) : options.ageAsOf();
        return options.season() == null
            ? analyzer.analyze(options.leagueId(), ageAsOf, options.minimumProfileAsOf())
            : analyzer.analyze(options.leagueId(), options.season(), ageAsOf, options.minimumProfileAsOf());
    }

    static void print(LeaguePlayerEvidenceProfileAnalyzer.PlayerEvidenceProfileReport report) {
        if (report == null) throw new IllegalArgumentException("report must not be null");
        System.out.println("League player evidence profile");
        System.out.println("League ID: " + report.leagueId());
        System.out.println("Season: " + report.season());
        System.out.println("Age as-of: " + report.ageAsOf());
        System.out.println("Profile source: " + report.profileSource());
        System.out.println("Production source: " + report.productionSource());
        System.out.println("Aging-model age as-of: " + report.modelAgeAsOf());
        System.out.println("Aging support policy: " + report.supportPolicyId());
        System.out.println("Age outlook policy: " + report.outlookPolicyId());
        System.out.println("Aging model sources: " + report.modelProfileSource() + "+" + report.modelProductionSource());
        if (report.minimumProfileAsOf() != null) {
            System.out.println("Minimum profile as-of: " + report.minimumProfileAsOf());
        }
        System.out.printf("Age coverage: %d/%d (%.1f%%)%n",
            report.ageCoveredPlayers(), report.totalPlayers(), report.ageCoveragePercent());
        System.out.printf("Production coverage: %d/%d (%.1f%%)%n",
            report.productionCoveredPlayers(), report.totalPlayers(), report.productionCoveragePercent());
        System.out.printf("Supporting flags: total=%d directional=%d%n",
            report.supportingFlags(), report.directionalSupportingFlags());
        System.out.println("Age, production, and supporting flags remain independent evidence dimensions; no blended score, dynasty adjustment, or recommendation is produced.");

        for (var team : report.teams()) {
            var age = team.age();
            var production = team.production();
            System.out.printf("%s  age=%d/%d (%.1f%%) avg=%s min=%s max=%s  production=%d/%d (%.1f%%) supporting-flags=%d directional=%d  [%s]%n",
                team.teamName(), age.coveredPlayers(), age.totalPlayers(), age.coveragePercent(),
                formatAge(age.averageAge()), formatAge(age.minimumAge()), formatAge(age.maximumAge()),
                production.coveredPlayers(), production.totalPlayers(), production.coveragePercent(),
                team.supportingFlags(), team.directionalSupportingFlags(), team.teamId());
            for (var position : production.positions().values()) {
                System.out.printf("  production %s: coverage=%d/%d (%.1f%%) games=%d pass=%d/%d INT=%d rush=%d/%d rec=%d-%d/%d FL=%d%n",
                    position.position(), position.coveredPlayers(), position.totalPlayers(), position.coveragePercent(),
                    position.playerGames(), position.passingYards(), position.passingTouchdowns(), position.interceptions(),
                    position.rushingYards(), position.rushingTouchdowns(), position.receptions(),
                    position.receivingYards(), position.receivingTouchdowns(), position.fumblesLost());
            }
            for (var player : team.supportingEvidence()) {
                if (player.flags().isEmpty()) continue;
                System.out.printf("  supporting %s %s model-age=%s favorable=%d inconclusive=%d unfavorable=%d [%s]%n",
                    player.playerName(), player.position(), player.modelAge() == null ? "-" : player.modelAge(),
                    player.favorableFlags(), player.inconclusiveFlags(), player.unfavorableFlags(), player.playerId());
                for (var flag : player.flags()) {
                    System.out.printf("    %s/%s signal=%s policy=%s source=%s summary=%s%n",
                        flag.category(), flag.dimension(), flag.signal(), flag.policyId(), flag.evidenceSource(), flag.summary());
                }
            }
        }
    }

    static void printUsage() {
        System.out.println("  butler league player-evidence-profile <league-id> [season] [--age-as-of YYYY-MM-DD] [--minimum-profile-as-of YYYY-MM-DD]");
    }

    private static String formatAge(Number value) {
        if (value == null) return "-";
        return value instanceof Double || value instanceof Float ? String.format("%.1f", value.doubleValue()) : value.toString();
    }

    private static Database initializedDatabase() throws SQLException {
        Database database = new Database(DATABASE_PATH);
        database.initialize();
        return database;
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

    private static LocalDate parseDate(String value, String flag) {
        try {
            return LocalDate.parse(value);
        } catch (DateTimeParseException e) {
            throw new IllegalArgumentException(flag + " must use YYYY-MM-DD: " + value);
        }
    }

    private static String requireText(String value, String field) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(field + " must not be blank");
        return value.trim();
    }

    record PlayerEvidenceProfileOptions(String leagueId, Integer season,
                                        LocalDate ageAsOf, LocalDate minimumProfileAsOf) {}
}
