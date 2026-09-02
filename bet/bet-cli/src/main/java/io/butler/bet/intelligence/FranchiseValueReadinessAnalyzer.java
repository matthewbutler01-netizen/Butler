package io.butler.bet.intelligence;

import io.butler.bet.data.Database;

import java.sql.SQLException;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Explains whether complete franchise-value rankings are currently possible and, when they are not,
 * which player or draft-pick assets are missing persisted values. Freshness is only enforced when
 * the caller supplies an explicit minimum as-of date; no universal threshold is assumed.
 */
public final class FranchiseValueReadinessAnalyzer {
    private final LeagueAssetInventoryAnalyzer inventory;

    public FranchiseValueReadinessAnalyzer(Database database) {
        Objects.requireNonNull(database, "database must not be null");
        this.inventory = new LeagueAssetInventoryAnalyzer(database);
    }

    public ReadinessReport analyze(String leagueId) throws SQLException {
        return analyze(inventory.analyze(leagueId), null);
    }

    public ReadinessReport analyze(String leagueId, String source) throws SQLException {
        return analyze(inventory.analyze(leagueId, source), null);
    }

    public ReadinessReport analyze(String leagueId, LocalDate minimumAsOfDate) throws SQLException {
        return analyze(inventory.analyze(leagueId), Objects.requireNonNull(minimumAsOfDate,
            "minimumAsOfDate must not be null"));
    }

    public ReadinessReport analyze(String leagueId, String source, LocalDate minimumAsOfDate) throws SQLException {
        return analyze(inventory.analyze(leagueId, source), Objects.requireNonNull(minimumAsOfDate,
            "minimumAsOfDate must not be null"));
    }

    private ReadinessReport analyze(LeagueAssetInventoryAnalyzer.InventoryReport inventoryReport,
                                    LocalDate minimumAsOfDate) {
        List<TeamReadiness> teams = new ArrayList<>();
        List<MissingPlayer> missingPlayers = new ArrayList<>();
        List<MissingDraftPick> missingPicks = new ArrayList<>();
        List<StalePlayer> stalePlayers = new ArrayList<>();
        List<StaleDraftPick> stalePicks = new ArrayList<>();
        LocalDate oldestValueDate = null;
        LocalDate latestValueDate = null;

        for (var team : inventoryReport.teams()) {
            List<MissingPlayer> teamMissingPlayers = new ArrayList<>();
            List<MissingDraftPick> teamMissingPicks = new ArrayList<>();
            List<StalePlayer> teamStalePlayers = new ArrayList<>();
            List<StaleDraftPick> teamStalePicks = new ArrayList<>();
            int valuedPlayers = 0;
            int valuedPicks = 0;
            LocalDate teamOldestValueDate = null;
            LocalDate teamLatestValueDate = null;

            for (var player : team.players()) {
                if (player.valued()) {
                    valuedPlayers++;
                    teamOldestValueDate = earlier(teamOldestValueDate, player.asOfDate());
                    teamLatestValueDate = later(teamLatestValueDate, player.asOfDate());
                    oldestValueDate = earlier(oldestValueDate, player.asOfDate());
                    latestValueDate = later(latestValueDate, player.asOfDate());
                    if (isStale(player.asOfDate(), minimumAsOfDate)) {
                        var stale = new StalePlayer(
                            team.teamId(), team.teamName(), player.playerId(), player.playerName(),
                            player.position(), player.nflTeam(), player.slot(), player.asOfDate());
                        teamStalePlayers.add(stale);
                        stalePlayers.add(stale);
                    }
                } else {
                    var missing = new MissingPlayer(
                        team.teamId(), team.teamName(), player.playerId(), player.playerName(),
                        player.position(), player.nflTeam(), player.slot());
                    teamMissingPlayers.add(missing);
                    missingPlayers.add(missing);
                }
            }
            for (var pick : team.draftPicks()) {
                if (pick.valued()) {
                    valuedPicks++;
                    teamOldestValueDate = earlier(teamOldestValueDate, pick.asOfDate());
                    teamLatestValueDate = later(teamLatestValueDate, pick.asOfDate());
                    oldestValueDate = earlier(oldestValueDate, pick.asOfDate());
                    latestValueDate = later(latestValueDate, pick.asOfDate());
                    if (isStale(pick.asOfDate(), minimumAsOfDate)) {
                        var stale = new StaleDraftPick(
                            team.teamId(), team.teamName(), pick.draftPickId(), pick.label(),
                            pick.originalTeamId(), pick.originalTeamName(), pick.pickNumber(), pick.asOfDate());
                        teamStalePicks.add(stale);
                        stalePicks.add(stale);
                    }
                } else {
                    var missing = new MissingDraftPick(
                        team.teamId(), team.teamName(), pick.draftPickId(), pick.label(),
                        pick.originalTeamId(), pick.originalTeamName(), pick.pickNumber());
                    teamMissingPicks.add(missing);
                    missingPicks.add(missing);
                }
            }

            int totalAssets = team.totalAssets();
            int valuedAssets = valuedPlayers + valuedPicks;
            int missingAssets = teamMissingPlayers.size() + teamMissingPicks.size();
            int staleAssets = teamStalePlayers.size() + teamStalePicks.size();
            teams.add(new TeamReadiness(
                team.teamId(), team.teamName(), classify(totalAssets, valuedAssets, missingAssets, staleAssets),
                valuedPlayers, teamMissingPlayers.size(), valuedPicks, teamMissingPicks.size(),
                minimumAsOfDate, teamOldestValueDate, teamLatestValueDate,
                List.copyOf(teamMissingPlayers), List.copyOf(teamMissingPicks),
                List.copyOf(teamStalePlayers), List.copyOf(teamStalePicks)));
        }

        Readiness status = classify(
            inventoryReport.totalAssets(), inventoryReport.valuedAssets(), inventoryReport.missingAssets(),
            stalePlayers.size() + stalePicks.size());
        return new ReadinessReport(
            inventoryReport.leagueId(), inventoryReport.source(), status,
            inventoryReport.valuedPlayers(), inventoryReport.missingPlayers(),
            inventoryReport.valuedDraftPicks(), inventoryReport.missingDraftPicks(),
            minimumAsOfDate, oldestValueDate, latestValueDate,
            List.copyOf(missingPlayers), List.copyOf(missingPicks),
            List.copyOf(stalePlayers), List.copyOf(stalePicks), List.copyOf(teams));
    }

    private static Readiness classify(int totalAssets, int valuedAssets, int missingAssets, int staleAssets) {
        if (totalAssets == 0) return Readiness.EMPTY;
        if (valuedAssets == 0) return Readiness.UNAVAILABLE;
        if (staleAssets > 0) return Readiness.STALE;
        if (missingAssets > 0) return Readiness.PARTIAL;
        return Readiness.READY;
    }

    private static boolean isStale(LocalDate asOfDate, LocalDate minimumAsOfDate) {
        return minimumAsOfDate != null && asOfDate != null && asOfDate.isBefore(minimumAsOfDate);
    }

    private static LocalDate earlier(LocalDate current, LocalDate candidate) {
        if (candidate == null) return current;
        return current == null || candidate.isBefore(current) ? candidate : current;
    }

    private static LocalDate later(LocalDate current, LocalDate candidate) {
        if (candidate == null) return current;
        return current == null || candidate.isAfter(current) ? candidate : current;
    }

    public enum Readiness { EMPTY, UNAVAILABLE, STALE, PARTIAL, READY }

    public record ReadinessReport(String leagueId, String source, Readiness status,
                                  int valuedPlayers, int missingPlayers,
                                  int valuedDraftPicks, int missingDraftPicks,
                                  LocalDate minimumAsOfDate, LocalDate oldestValueDate, LocalDate latestValueDate,
                                  List<MissingPlayer> missingPlayerAssets,
                                  List<MissingDraftPick> missingDraftPickAssets,
                                  List<StalePlayer> stalePlayerAssets,
                                  List<StaleDraftPick> staleDraftPickAssets,
                                  List<TeamReadiness> teams) {
        public int valuedAssets() { return valuedPlayers + valuedDraftPicks; }
        public int missingAssets() { return missingPlayers + missingDraftPicks; }
        public int staleAssets() { return stalePlayerAssets.size() + staleDraftPickAssets.size(); }
        public int totalAssets() { return valuedAssets() + missingAssets(); }
        public boolean rankable() { return status == Readiness.READY; }
        public boolean stale() { return staleAssets() > 0; }
        public double coveragePercent() {
            return totalAssets() == 0 ? 0.0 : valuedAssets() * 100.0 / totalAssets();
        }
    }

    public record TeamReadiness(String teamId, String teamName, Readiness status,
                                int valuedPlayers, int missingPlayers,
                                int valuedDraftPicks, int missingDraftPicks,
                                LocalDate minimumAsOfDate, LocalDate oldestValueDate, LocalDate latestValueDate,
                                List<MissingPlayer> missingPlayerAssets,
                                List<MissingDraftPick> missingDraftPickAssets,
                                List<StalePlayer> stalePlayerAssets,
                                List<StaleDraftPick> staleDraftPickAssets) {
        public int valuedAssets() { return valuedPlayers + valuedDraftPicks; }
        public int missingAssets() { return missingPlayers + missingDraftPicks; }
        public int staleAssets() { return stalePlayerAssets.size() + staleDraftPickAssets.size(); }
        public int totalAssets() { return valuedAssets() + missingAssets(); }
        public boolean rankable() { return status == Readiness.READY; }
        public boolean stale() { return staleAssets() > 0; }
        public double coveragePercent() {
            return totalAssets() == 0 ? 0.0 : valuedAssets() * 100.0 / totalAssets();
        }
    }

    public record MissingPlayer(String teamId, String teamName,
                                String playerId, String playerName, String position,
                                String nflTeam, String slot) {}

    public record MissingDraftPick(String teamId, String teamName,
                                   String draftPickId, String label,
                                   String originalTeamId, String originalTeamName,
                                   Integer pickNumber) {}

    public record StalePlayer(String teamId, String teamName,
                              String playerId, String playerName, String position,
                              String nflTeam, String slot, LocalDate asOfDate) {}

    public record StaleDraftPick(String teamId, String teamName,
                                 String draftPickId, String label,
                                 String originalTeamId, String originalTeamName,
                                 Integer pickNumber, LocalDate asOfDate) {}
}
