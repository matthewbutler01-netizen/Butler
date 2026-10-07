package io.butler.bet.sleeper;

import io.butler.bet.intelligence.NflversePlayerWeekProductionImporter;
import java.net.URI;
import java.time.Instant;
import java.util.*;

/** Observed positional production against the scheduled NFL opponent; never a forecast adjustment. */
final class NflverseDefensiveMatchupProvider {
    static final URI SCHEDULE_URI = URI.create("https://github.com/nflverse/nflverse-data/releases/download/schedules/games.csv.gz");
    private final NflverseRosterUsageProvider downloads = new NflverseRosterUsageProvider();

    Map<String, String> load(int season, int week,
        List<SleeperLiveWaiverTargetRosterContextAudit.TargetPlayer> players) throws java.io.IOException, InterruptedException {
        return parse(downloads.download(SCHEDULE_URI), downloads.download(NflversePlayerWeekProductionImporter.statsUri(season)),
            season, week, players, Instant.now());
    }

    static Map<String, String> parse(String scheduleCsv, String statsCsv, int season, int week,
        List<SleeperLiveWaiverTargetRosterContextAudit.TargetPlayer> players, Instant checked) {
        var schedules = NflversePlayerWeekProductionImporter.parseEvidenceCsv(scheduleCsv).stream()
            .filter(r -> String.valueOf(season).equals(r.get("season")) && "REG".equals(r.get("game_type"))).toList();
        var stats = NflversePlayerWeekProductionImporter.parseEvidenceCsv(statsCsv).stream()
            .filter(r -> String.valueOf(season).equals(r.get("season")) && "REG".equals(r.get("season_type"))).toList();
        Map<String, String> result = new LinkedHashMap<>();
        for (var player : players) {
            String team = player.nflTeam(), position = player.position();
            String prefix = "Saved roster team " + team + "; ";
            if (team == null || team.isBlank() || !Set.of("QB", "RB", "WR", "TE").contains(Objects.toString(position, ""))) {
                result.put(player.sleeperPlayerId(), "NFL matchup unavailable: saved team or supported position missing."); continue;
            }
            var games = schedules.stream().filter(r -> String.valueOf(week).equals(r.get("week"))
                && (team.equals(r.get("home_team")) || team.equals(r.get("away_team")))).toList();
            if (games.size() != 1) {
                result.put(player.sleeperPlayerId(), prefix + "no unique scheduled NFL opponent (bye or missing/ambiguous schedule)."); continue;
            }
            var game = games.getFirst();
            String opponent = team.equals(game.get("home_team")) ? game.get("away_team") : game.get("home_team");
            if (opponent == null || opponent.isBlank() || opponent.equals(team)) throw new IllegalStateException("Invalid scheduled opponent");
            var prior = schedules.stream().filter(r -> {
                int w = Math.toIntExact(number(r, "week"));
                return w >= Math.max(1, week - 3) && w < week
                    && (opponent.equals(r.get("home_team")) || opponent.equals(r.get("away_team")))
                    && present(r.get("home_score")) && present(r.get("away_score"));
            }).toList();
            Set<String> gameIds = new HashSet<>();
            for (var previous : prior) {
                String id = previous.get("game_id");
                if (!present(id) || !gameIds.add(id)) throw new IllegalStateException("Duplicate or missing matchup game identity");
            }
            long rushYards = 0, receivingYards = 0, passYards = 0, touchdowns = 0;
            int covered = 0; boolean incomplete = false;
            Set<String> identities = new HashSet<>();
            for (var previous : prior) {
                String id = previous.get("game_id");
                String offense = opponent.equals(previous.get("home_team")) ? previous.get("away_team") : previous.get("home_team");
                var rows = stats.stream().filter(r -> id.equals(r.get("game_id")) && offense.equals(r.get("team"))
                    && opponent.equals(r.get("opponent_team"))).toList();
                // Require a passing row as a coverage marker; absent positional rows never become a zero.
                if (rows.stream().noneMatch(r -> "QB".equals(r.get("position")))) { incomplete = true; continue; }
                var positional = rows.stream().filter(r -> position.equals(r.get("position"))).toList();
                if (positional.isEmpty()) { incomplete = true; continue; }
                covered++;
                for (var row : positional) {
                    if (!present(row.get("player_id")) || !identities.add(id + ":" + row.get("player_id")))
                        throw new IllegalStateException("Duplicate or missing matchup player/game identity");
                    rushYards += number(row, "rushing_yards"); receivingYards += number(row, "receiving_yards");
                    passYards += number(row, "passing_yards");
                    touchdowns += number(row, "rushing_tds") + number(row, "receiving_tds") + number(row, "passing_tds");
                }
            }
            String detail = prefix + "week " + week + " vs " + opponent + "; "
                + (incomplete || covered == 0 ? "positional production coverage incomplete; no defensive total reported."
                    : "against opposing " + position + " players across " + covered + " completed games in weeks "
                    + Math.max(1, week - 3) + " to " + (week - 1) + ": " + passYards + " passing yards, "
                    + rushYards + " rushing yards, " + receivingYards + " receiving yards, " + touchdowns + " offensive TDs (totals).")
                + " Checked " + checked + ". Small, unadjusted sample; opponent strength and game script are not controlled."
                + " Saved team metadata may lag a transaction. No projection bonus or expert pick inferred.";
            result.put(player.sleeperPlayerId(), detail);
        }
        return Map.copyOf(result);
    }
    private static boolean present(String value) { return value != null && !value.isBlank() && !"NA".equals(value); }
    private static long number(Map<String, String> row, String field) {
        if (!present(row.get(field))) throw new IllegalStateException("Missing matchup stat " + field);
        try { return Long.parseLong(row.get(field)); }
        catch (NumberFormatException e) { throw new IllegalStateException("Invalid matchup stat " + field, e); }
    }
}
