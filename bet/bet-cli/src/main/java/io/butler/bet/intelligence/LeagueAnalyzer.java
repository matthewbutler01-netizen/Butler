package io.butler.bet.intelligence;

import io.butler.bet.data.Database;
import io.butler.bet.data.PlayerRepository;
import io.butler.bet.data.RosterRepository;
import io.butler.bet.data.TeamRepository;
import io.butler.bet.domain.Player;
import io.butler.bet.domain.Roster;
import io.butler.bet.domain.Team;

import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

public final class LeagueAnalyzer {
    private final TeamRepository teams;
    private final PlayerRepository players;
    private final RosterRepository rosters;

    public LeagueAnalyzer(Database database) {
        Objects.requireNonNull(database, "database must not be null");
        this.teams = new TeamRepository(database);
        this.players = new PlayerRepository(database);
        this.rosters = new RosterRepository(database);
    }

    public LeagueReport analyze(String leagueId) throws SQLException {
        if (leagueId == null || leagueId.isBlank()) {
            throw new IllegalArgumentException("leagueId must not be blank");
        }

        List<TeamReport> reports = new ArrayList<>();
        Map<String, Integer> leaguePositions = new HashMap<>();
        int totalPlayers = 0;

        for (Team team : teams.findByLeagueId(leagueId)) {
            Map<String, Integer> positions = new HashMap<>();
            Map<String, Integer> slots = new HashMap<>();
            List<Roster> memberships = rosters.findByTeamId(team.getId());

            for (Roster membership : memberships) {
                Player player = players.findById(membership.getPlayerId()).orElse(null);
                if (player == null) continue;
                positions.merge(player.getPosition(), 1, Integer::sum);
                leaguePositions.merge(player.getPosition(), 1, Integer::sum);
                slots.merge(membership.getSlot(), 1, Integer::sum);
                totalPlayers++;
            }

            reports.add(new TeamReport(team.getId(), team.getName(), memberships.size(), Map.copyOf(positions), Map.copyOf(slots)));
        }

        reports.sort(Comparator.comparing(TeamReport::teamName, String.CASE_INSENSITIVE_ORDER));
        return new LeagueReport(leagueId, reports.size(), totalPlayers, Map.copyOf(leaguePositions), List.copyOf(reports));
    }

    public record LeagueReport(String leagueId, int teamCount, int rosteredPlayers, Map<String, Integer> positionCounts, List<TeamReport> teams) {}

    public record TeamReport(String teamId, String teamName, int rosterSize, Map<String, Integer> positionCounts, Map<String, Integer> slotCounts) {}
}
