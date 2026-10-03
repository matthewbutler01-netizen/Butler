package io.butler.bet.sleeper;

import io.butler.bet.intelligence.NflversePlayerWeekProductionImporter;

import java.io.IOException;
import java.time.DateTimeException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * BF-1006 read-only NFL kickoff lock evidence from the existing nflverse schedule source.
 *
 * <p>nflverse schedule {@code gametime} is Eastern time. A player is locked at kickoff; this
 * provider never infers fantasy-platform transaction semantics beyond that exact NFL game time.</p>
 */
final class NflverseGameLockProvider {
    static final ZoneId NFLVERSE_SCHEDULE_ZONE = ZoneId.of("America/New_York");

    private final NflverseRosterUsageProvider downloads = new NflverseRosterUsageProvider();

    Map<String, GameLockEvidence> load(
        int season,
        int week,
        List<SleeperLiveWaiverTargetRosterContextAudit.TargetPlayer> players)
        throws IOException, InterruptedException {

        Objects.requireNonNull(players, "players must not be null");
        return parse(
            downloads.download(NflverseDefensiveMatchupProvider.SCHEDULE_URI),
            season,
            week,
            players,
            Instant.now());
    }

    static Map<String, GameLockEvidence> parse(
        String scheduleCsv,
        int season,
        int week,
        List<SleeperLiveWaiverTargetRosterContextAudit.TargetPlayer> players,
        Instant checkedAt) {

        Objects.requireNonNull(scheduleCsv, "scheduleCsv must not be null");
        Objects.requireNonNull(players, "players must not be null");
        Objects.requireNonNull(checkedAt, "checkedAt must not be null");
        if (season <= 0 || week <= 0) throw new IllegalArgumentException("season/week must be positive");

        var schedules = NflversePlayerWeekProductionImporter.parseEvidenceCsv(scheduleCsv).stream()
            .filter(row -> String.valueOf(season).equals(row.get("season"))
                && String.valueOf(week).equals(row.get("week"))
                && "REG".equals(row.get("game_type")))
            .toList();

        Map<String, GameLockEvidence> result = new LinkedHashMap<>();
        for (var player : players) {
            String id = player.sleeperPlayerId();
            String team = player.nflTeam();
            if (team == null || team.isBlank()) {
                result.put(id, GameLockEvidence.unverified(
                    "NFL kickoff lock unverified: exact saved NFL team is missing; checked=" + checkedAt + "."));
                continue;
            }

            var games = schedules.stream()
                .filter(row -> team.equals(row.get("home_team")) || team.equals(row.get("away_team")))
                .toList();
            if (games.size() != 1) {
                result.put(id, GameLockEvidence.unverified(
                    "NFL kickoff lock unverified for team " + team
                        + ": expected one Week " + week + " schedule row, found " + games.size()
                        + "; checked=" + checkedAt + "; source=" + NflverseDefensiveMatchupProvider.SCHEDULE_URI + "."));
                continue;
            }

            var game = games.getFirst();
            String gameday = clean(game.get("gameday"));
            String gametime = clean(game.get("gametime"));
            if (gameday == null || gametime == null) {
                result.put(id, GameLockEvidence.unverified(
                    "NFL kickoff lock unverified for team " + team
                        + ": schedule row is missing gameday/gametime; checked=" + checkedAt
                        + "; source=" + NflverseDefensiveMatchupProvider.SCHEDULE_URI + "."));
                continue;
            }

            final Instant kickoff;
            try {
                kickoff = ZonedDateTime.of(
                    LocalDate.parse(gameday),
                    LocalTime.parse(gametime),
                    NFLVERSE_SCHEDULE_ZONE).toInstant();
            } catch (DateTimeException e) {
                result.put(id, GameLockEvidence.unverified(
                    "NFL kickoff lock unverified for team " + team
                        + ": invalid gameday/gametime " + gameday + " " + gametime
                        + "; checked=" + checkedAt + "; source=" + NflverseDefensiveMatchupProvider.SCHEDULE_URI + "."));
                continue;
            }

            boolean locked = !checkedAt.isBefore(kickoff);
            result.put(id, new GameLockEvidence(
                true,
                locked,
                kickoff,
                "NFL kickoff " + kickoff + " for " + team + " Week " + week
                    + "; checked=" + checkedAt + "; source=" + NflverseDefensiveMatchupProvider.SCHEDULE_URI
                    + ". nflverse gametime interpreted in America/New_York."));
        }
        return Map.copyOf(result);
    }

    record GameLockEvidence(boolean verified, boolean locked, Instant kickoff, String detail) {
        GameLockEvidence {
            detail = requireText(detail, "detail");
            if (verified && kickoff == null) {
                throw new IllegalArgumentException("verified game-lock evidence requires kickoff");
            }
            if (!verified && locked) {
                throw new IllegalArgumentException("unverified game-lock evidence cannot assert locked");
            }
        }

        static GameLockEvidence unverified(String detail) {
            return new GameLockEvidence(false, false, null, detail);
        }
    }

    private static String clean(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private static String requireText(String value, String field) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(field + " must not be blank");
        return value.trim();
    }
}
