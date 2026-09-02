package io.butler.bet.intelligence;

import io.butler.bet.data.Database;
import io.butler.bet.data.DraftPickRepository;
import io.butler.bet.data.DraftPickValueRepository;
import io.butler.bet.data.TeamRepository;

import java.sql.SQLException;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;

/**
 * Provides neutral future draft-capital context by team and season. Values are descriptive only;
 * no team strategy, rebuild window, or preferred pick distribution is inferred.
 */
public final class LeagueDraftCapitalTimelineAnalyzer {
    private final LeagueValueSourceResolver sources;
    private final DraftPickRepository picks;
    private final DraftPickValueRepository values;
    private final TeamRepository teams;

    public LeagueDraftCapitalTimelineAnalyzer(Database database) {
        Objects.requireNonNull(database, "database must not be null");
        this.sources = new LeagueValueSourceResolver(database);
        this.picks = new DraftPickRepository(database);
        this.values = new DraftPickValueRepository(database);
        this.teams = new TeamRepository(database);
    }

    public DraftCapitalReport analyze(String leagueId) throws SQLException {
        return analyze(leagueId, sources.resolve(leagueId), null);
    }

    public DraftCapitalReport analyze(String leagueId, String sourceOverride) throws SQLException {
        return analyze(leagueId, sourceOverride, null);
    }

    public DraftCapitalReport analyze(String leagueId, LocalDate minimumAsOfDate) throws SQLException {
        return analyze(leagueId, sources.resolve(leagueId), minimumAsOfDate);
    }

    public DraftCapitalReport analyze(String leagueId, String sourceOverride,
                                      LocalDate minimumAsOfDate) throws SQLException {
        String normalizedLeagueId = requireText(leagueId, "leagueId");
        String source = requireText(sourceOverride, "source");

        Map<String, String> teamNames = new LinkedHashMap<>();
        for (var team : teams.findByLeagueId(normalizedLeagueId)) {
            teamNames.put(team.getId(), team.getName());
        }

        Map<String, MutableTeam> byTeam = new LinkedHashMap<>();
        for (var entry : teamNames.entrySet()) {
            byTeam.put(entry.getKey(), new MutableTeam(entry.getKey(), entry.getValue()));
        }

        for (var pick : picks.findByLeagueId(normalizedLeagueId)) {
            String ownerId = pick.getOwnerTeamId();
            MutableTeam team = byTeam.computeIfAbsent(ownerId,
                id -> new MutableTeam(id, teamNames.getOrDefault(id, id)));
            MutableSeason season = team.seasons.computeIfAbsent(pick.getSeason(), MutableSeason::new);
            season.totalPicks++;
            season.roundCounts.merge(pick.getRound(), 1, Integer::sum);
            team.totalPicks++;

            var value = values.findLatestByDraftPickIdAndSource(pick.getId(), source).orElse(null);
            if (value == null) {
                season.missingPicks++;
                team.missingPicks++;
                continue;
            }
            if (minimumAsOfDate != null && value.getAsOfDate().isBefore(minimumAsOfDate)) {
                season.stalePicks++;
                team.stalePicks++;
                continue;
            }
            season.valuedPicks++;
            season.value += value.getValue();
            team.valuedPicks++;
            team.value += value.getValue();
        }

        List<TeamDraftCapital> teamReports = new ArrayList<>();
        for (MutableTeam team : byTeam.values()) {
            List<SeasonDraftCapital> seasons = new ArrayList<>();
            for (MutableSeason season : team.seasons.values()) {
                seasons.add(new SeasonDraftCapital(season.season, season.value, season.valuedPicks,
                    season.stalePicks, season.missingPicks, season.totalPicks,
                    Map.copyOf(season.roundCounts)));
            }
            seasons.sort(Comparator.comparingInt(SeasonDraftCapital::season));
            teamReports.add(new TeamDraftCapital(team.teamId, team.teamName, team.value, team.valuedPicks,
                team.stalePicks, team.missingPicks, team.totalPicks, List.copyOf(seasons)));
        }
        teamReports.sort(Comparator.comparing(TeamDraftCapital::teamName, String.CASE_INSENSITIVE_ORDER)
            .thenComparing(TeamDraftCapital::teamId));

        return new DraftCapitalReport(normalizedLeagueId, source, minimumAsOfDate, List.copyOf(teamReports));
    }

    private static String requireText(String value, String field) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(field + " must not be blank");
        return value.trim();
    }

    private static final class MutableTeam {
        final String teamId;
        final String teamName;
        final Map<Integer, MutableSeason> seasons = new TreeMap<>();
        double value;
        int valuedPicks;
        int stalePicks;
        int missingPicks;
        int totalPicks;

        MutableTeam(String teamId, String teamName) {
            this.teamId = teamId;
            this.teamName = teamName;
        }
    }

    private static final class MutableSeason {
        final int season;
        final Map<Integer, Integer> roundCounts = new TreeMap<>();
        double value;
        int valuedPicks;
        int stalePicks;
        int missingPicks;
        int totalPicks;

        MutableSeason(int season) {
            this.season = season;
        }
    }

    public record DraftCapitalReport(String leagueId, String source, LocalDate minimumAsOfDate,
                                     List<TeamDraftCapital> teams) {
        public DraftCapitalReport {
            teams = List.copyOf(Objects.requireNonNull(teams, "teams must not be null"));
        }

        public double totalValue() {
            return teams.stream().mapToDouble(TeamDraftCapital::value).sum();
        }

        public int valuedPicks() {
            return teams.stream().mapToInt(TeamDraftCapital::valuedPicks).sum();
        }

        public int stalePicks() {
            return teams.stream().mapToInt(TeamDraftCapital::stalePicks).sum();
        }

        public int missingPicks() {
            return teams.stream().mapToInt(TeamDraftCapital::missingPicks).sum();
        }

        public int totalPicks() {
            return teams.stream().mapToInt(TeamDraftCapital::totalPicks).sum();
        }

        public double coveragePercent() {
            return totalPicks() == 0 ? 0.0 : valuedPicks() * 100.0 / totalPicks();
        }
    }

    public record TeamDraftCapital(String teamId, String teamName, double value,
                                   int valuedPicks, int stalePicks, int missingPicks,
                                   int totalPicks, List<SeasonDraftCapital> seasons) {
        public TeamDraftCapital {
            seasons = List.copyOf(Objects.requireNonNull(seasons, "seasons must not be null"));
        }

        public double coveragePercent() {
            return totalPicks == 0 ? 0.0 : valuedPicks * 100.0 / totalPicks;
        }
    }

    public record SeasonDraftCapital(int season, double value, int valuedPicks,
                                     int stalePicks, int missingPicks, int totalPicks,
                                     Map<Integer, Integer> roundCounts) {
        public SeasonDraftCapital {
            roundCounts = Map.copyOf(Objects.requireNonNull(roundCounts, "roundCounts must not be null"));
        }

        public double coveragePercent() {
            return totalPicks == 0 ? 0.0 : valuedPicks * 100.0 / totalPicks;
        }
    }
}
