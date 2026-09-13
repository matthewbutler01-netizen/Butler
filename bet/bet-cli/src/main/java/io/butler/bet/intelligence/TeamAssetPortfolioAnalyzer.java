package io.butler.bet.intelligence;

import io.butler.bet.data.Database;
import io.butler.bet.data.DraftPickRepository;
import io.butler.bet.data.DraftPickValueRepository;
import io.butler.bet.data.PlayerValueRepository;
import io.butler.bet.data.RosterRepository;
import io.butler.bet.data.TeamRepository;
import io.butler.bet.domain.DraftPick;
import io.butler.bet.domain.DraftPickValue;
import io.butler.bet.domain.PlayerValue;
import io.butler.bet.domain.Roster;
import io.butler.bet.domain.Team;

import java.sql.SQLException;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;

/**
 * Reports each fantasy team's persisted player and draft-pick asset value separately.
 * This is a portfolio view, not a competitive-strength ranking: missing values remain explicit
 * and draft picks are attributed to their current owner.
 */
public final class TeamAssetPortfolioAnalyzer {
    private final TeamRepository teams;
    private final LeagueValueSourceResolver sources;
    private final RosterRepository rosters;
    private final PlayerValueRepository playerValues;
    private final DraftPickRepository draftPicks;
    private final DraftPickValueRepository draftPickValues;

    public TeamAssetPortfolioAnalyzer(Database database) {
        Objects.requireNonNull(database, "database must not be null");
        this.teams = new TeamRepository(database);
        this.sources = new LeagueValueSourceResolver(database);
        this.rosters = new RosterRepository(database);
        this.playerValues = new PlayerValueRepository(database);
        this.draftPicks = new DraftPickRepository(database);
        this.draftPickValues = new DraftPickValueRepository(database);
    }

    public PortfolioReport analyze(String leagueId) throws SQLException {
        String normalizedLeagueId = requireText(leagueId, "leagueId");
        return analyzeResolved(normalizedLeagueId, sources.resolve(normalizedLeagueId));
    }

    public PortfolioReport analyze(String leagueId, String source) throws SQLException {
        String normalizedLeagueId = requireText(leagueId, "leagueId");
        String normalizedSource = requireText(source, "source");
        validateExplicitSource(normalizedLeagueId, normalizedSource);
        return analyzeResolved(normalizedLeagueId, normalizedSource);
    }

    private void validateExplicitSource(String leagueId, String source) throws SQLException {
        Set<String> available = new TreeSet<>();
        available.addAll(playerValues.findSources());
        available.addAll(draftPickValues.findSources());
        if (available.contains(source)) return;

        String availability = available.isEmpty()
            ? "No persisted value sources are available."
            : "Available persisted sources: " + String.join(", ", available);
        throw new IllegalArgumentException(
            "unknown portfolio value source for league " + leagueId + ": " + source + ". " + availability);
    }

    private PortfolioReport analyzeResolved(String leagueId, String source) throws SQLException {
        List<Team> leagueTeams = teams.findByLeagueId(leagueId);

        Map<String, List<Roster>> rostersByTeam = new HashMap<>();
        for (Roster roster : rosters.findByLeagueId(leagueId)) {
            rostersByTeam.computeIfAbsent(roster.getTeamId(), ignored -> new ArrayList<>()).add(roster);
        }

        Map<String, PlayerValue> latestPlayerValues = new HashMap<>();
        for (PlayerValue value : playerValues.findLatestBySource(source)) {
            latestPlayerValues.put(value.getPlayerId(), value);
        }

        Map<String, List<DraftPick>> picksByOwner = new HashMap<>();
        for (DraftPick pick : draftPicks.findByLeagueId(leagueId)) {
            picksByOwner.computeIfAbsent(pick.getOwnerTeamId(), ignored -> new ArrayList<>()).add(pick);
        }

        Map<String, DraftPickValue> latestPickValues = new HashMap<>();
        for (DraftPickValue value : draftPickValues.findLatestBySource(source)) {
            latestPickValues.put(value.getDraftPickId(), value);
        }

        List<TeamPortfolio> teamPortfolios = new ArrayList<>();
        int totalValuedPlayers = 0;
        int totalMissingPlayers = 0;
        int totalValuedPicks = 0;
        int totalMissingPicks = 0;
        double totalPlayerValue = 0.0;
        double totalPickValue = 0.0;

        for (Team team : leagueTeams) {
            ValueSummary playerSummary = playerSummary(
                rostersByTeam.getOrDefault(team.getId(), List.of()), latestPlayerValues);
            ValueSummary pickSummary = pickSummary(
                picksByOwner.getOrDefault(team.getId(), List.of()), latestPickValues);
            totalValuedPlayers += playerSummary.valued();
            totalMissingPlayers += playerSummary.missing();
            totalValuedPicks += pickSummary.valued();
            totalMissingPicks += pickSummary.missing();
            totalPlayerValue += playerSummary.value();
            totalPickValue += pickSummary.value();
            teamPortfolios.add(new TeamPortfolio(
                team.getId(),
                team.getName(),
                playerSummary.value(),
                pickSummary.value(),
                playerSummary.valued(),
                playerSummary.missing(),
                pickSummary.valued(),
                pickSummary.missing(),
                earlier(playerSummary.oldestDate(), pickSummary.oldestDate()),
                later(playerSummary.latestDate(), pickSummary.latestDate())));
        }

        teamPortfolios.sort(Comparator.comparing(TeamPortfolio::teamName, String.CASE_INSENSITIVE_ORDER)
            .thenComparing(TeamPortfolio::teamId));
        return new PortfolioReport(
            leagueId,
            source,
            totalPlayerValue,
            totalPickValue,
            totalValuedPlayers,
            totalMissingPlayers,
            totalValuedPicks,
            totalMissingPicks,
            List.copyOf(teamPortfolios));
    }

    private static ValueSummary playerSummary(List<Roster> teamRosters,
                                              Map<String, PlayerValue> latestPlayerValues) {
        double value = 0.0;
        int valued = 0;
        int missing = 0;
        LocalDate oldest = null;
        LocalDate latest = null;
        for (Roster roster : teamRosters) {
            PlayerValue snapshot = latestPlayerValues.get(roster.getPlayerId());
            if (snapshot == null) {
                missing++;
                continue;
            }
            value += snapshot.getValue();
            valued++;
            oldest = earlier(oldest, snapshot.getAsOfDate());
            latest = later(latest, snapshot.getAsOfDate());
        }
        return new ValueSummary(value, valued, missing, oldest, latest);
    }

    private static ValueSummary pickSummary(List<DraftPick> teamPicks,
                                            Map<String, DraftPickValue> latestPickValues) {
        double value = 0.0;
        int valued = 0;
        int missing = 0;
        LocalDate oldest = null;
        LocalDate latest = null;
        for (DraftPick pick : teamPicks) {
            DraftPickValue snapshot = latestPickValues.get(pick.getId());
            if (snapshot == null) {
                missing++;
                continue;
            }
            value += snapshot.getValue();
            valued++;
            oldest = earlier(oldest, snapshot.getAsOfDate());
            latest = later(latest, snapshot.getAsOfDate());
        }
        return new ValueSummary(value, valued, missing, oldest, latest);
    }

    private static LocalDate earlier(LocalDate current, LocalDate candidate) {
        if (candidate == null) return current;
        return current == null || candidate.isBefore(current) ? candidate : current;
    }

    private static LocalDate later(LocalDate current, LocalDate candidate) {
        if (candidate == null) return current;
        return current == null || candidate.isAfter(current) ? candidate : current;
    }

    private static String requireText(String value, String field) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(field + " must not be blank");
        return value.trim();
    }

    private record ValueSummary(double value, int valued, int missing,
                                LocalDate oldestDate, LocalDate latestDate) {}

    public record PortfolioReport(String leagueId, String source,
                                  double playerValue, double draftPickValue,
                                  int valuedPlayers, int missingPlayers,
                                  int valuedDraftPicks, int missingDraftPicks,
                                  List<TeamPortfolio> teams) {
        public double totalAssetValue() { return playerValue + draftPickValue; }
        public int totalPlayers() { return valuedPlayers + missingPlayers; }
        public int totalDraftPicks() { return valuedDraftPicks + missingDraftPicks; }
        public int totalAssets() { return totalPlayers() + totalDraftPicks(); }
        public int valuedAssets() { return valuedPlayers + valuedDraftPicks; }
        public int missingAssets() { return missingPlayers + missingDraftPicks; }
        public boolean complete() { return missingAssets() == 0; }
        public double coveragePercent() {
            return totalAssets() == 0 ? 0.0 : valuedAssets() * 100.0 / totalAssets();
        }
    }

    public record TeamPortfolio(String teamId, String teamName,
                                double playerValue, double draftPickValue,
                                int valuedPlayers, int missingPlayers,
                                int valuedDraftPicks, int missingDraftPicks,
                                LocalDate oldestValueDate, LocalDate latestValueDate) {
        public double totalAssetValue() { return playerValue + draftPickValue; }
        public int totalPlayers() { return valuedPlayers + missingPlayers; }
        public int totalDraftPicks() { return valuedDraftPicks + missingDraftPicks; }
        public int totalAssets() { return totalPlayers() + totalDraftPicks(); }
        public int valuedAssets() { return valuedPlayers + valuedDraftPicks; }
        public int missingAssets() { return missingPlayers + missingDraftPicks; }
        public boolean complete() { return missingAssets() == 0; }
        public double coveragePercent() {
            return totalAssets() == 0 ? 0.0 : valuedAssets() * 100.0 / totalAssets();
        }
        public double playerCoveragePercent() {
            return totalPlayers() == 0 ? 0.0 : valuedPlayers * 100.0 / totalPlayers();
        }
        public double draftPickCoveragePercent() {
            return totalDraftPicks() == 0 ? 100.0 : valuedDraftPicks * 100.0 / totalDraftPicks();
        }
    }
}
