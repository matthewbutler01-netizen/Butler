package io.butler.bet.intelligence;

import io.butler.bet.data.Database;

import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Explains whether complete franchise-value rankings are currently possible and, when they are not,
 * which player or draft-pick assets are missing persisted values. No freshness threshold is assumed.
 */
public final class FranchiseValueReadinessAnalyzer {
    private final LeagueAssetInventoryAnalyzer inventory;

    public FranchiseValueReadinessAnalyzer(Database database) {
        Objects.requireNonNull(database, "database must not be null");
        this.inventory = new LeagueAssetInventoryAnalyzer(database);
    }

    public ReadinessReport analyze(String leagueId) throws SQLException {
        return analyze(inventory.analyze(leagueId));
    }

    public ReadinessReport analyze(String leagueId, String source) throws SQLException {
        return analyze(inventory.analyze(leagueId, source));
    }

    private ReadinessReport analyze(LeagueAssetInventoryAnalyzer.InventoryReport inventoryReport) {
        List<TeamReadiness> teams = new ArrayList<>();
        List<MissingPlayer> missingPlayers = new ArrayList<>();
        List<MissingDraftPick> missingPicks = new ArrayList<>();

        for (var team : inventoryReport.teams()) {
            List<MissingPlayer> teamMissingPlayers = new ArrayList<>();
            List<MissingDraftPick> teamMissingPicks = new ArrayList<>();
            int valuedPlayers = 0;
            int valuedPicks = 0;

            for (var player : team.players()) {
                if (player.valued()) {
                    valuedPlayers++;
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
            teams.add(new TeamReadiness(
                team.teamId(), team.teamName(), classify(totalAssets, valuedAssets, missingAssets),
                valuedPlayers, teamMissingPlayers.size(), valuedPicks, teamMissingPicks.size(),
                List.copyOf(teamMissingPlayers), List.copyOf(teamMissingPicks)));
        }

        Readiness status = classify(
            inventoryReport.totalAssets(), inventoryReport.valuedAssets(), inventoryReport.missingAssets());
        return new ReadinessReport(
            inventoryReport.leagueId(), inventoryReport.source(), status,
            inventoryReport.valuedPlayers(), inventoryReport.missingPlayers(),
            inventoryReport.valuedDraftPicks(), inventoryReport.missingDraftPicks(),
            List.copyOf(missingPlayers), List.copyOf(missingPicks), List.copyOf(teams));
    }

    private static Readiness classify(int totalAssets, int valuedAssets, int missingAssets) {
        if (totalAssets == 0) return Readiness.EMPTY;
        if (valuedAssets == 0) return Readiness.UNAVAILABLE;
        if (missingAssets > 0) return Readiness.PARTIAL;
        return Readiness.READY;
    }

    public enum Readiness { EMPTY, UNAVAILABLE, PARTIAL, READY }

    public record ReadinessReport(String leagueId, String source, Readiness status,
                                  int valuedPlayers, int missingPlayers,
                                  int valuedDraftPicks, int missingDraftPicks,
                                  List<MissingPlayer> missingPlayerAssets,
                                  List<MissingDraftPick> missingDraftPickAssets,
                                  List<TeamReadiness> teams) {
        public int valuedAssets() { return valuedPlayers + valuedDraftPicks; }
        public int missingAssets() { return missingPlayers + missingDraftPicks; }
        public int totalAssets() { return valuedAssets() + missingAssets(); }
        public boolean rankable() { return status == Readiness.READY; }
        public double coveragePercent() {
            return totalAssets() == 0 ? 0.0 : valuedAssets() * 100.0 / totalAssets();
        }
    }

    public record TeamReadiness(String teamId, String teamName, Readiness status,
                                int valuedPlayers, int missingPlayers,
                                int valuedDraftPicks, int missingDraftPicks,
                                List<MissingPlayer> missingPlayerAssets,
                                List<MissingDraftPick> missingDraftPickAssets) {
        public int valuedAssets() { return valuedPlayers + valuedDraftPicks; }
        public int missingAssets() { return missingPlayers + missingDraftPicks; }
        public int totalAssets() { return valuedAssets() + missingAssets(); }
        public boolean rankable() { return status == Readiness.READY; }
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
}
