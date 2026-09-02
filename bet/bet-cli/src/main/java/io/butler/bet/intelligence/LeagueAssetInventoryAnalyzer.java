package io.butler.bet.intelligence;

import io.butler.bet.data.Database;
import io.butler.bet.data.DraftPickRepository;
import io.butler.bet.data.DraftPickValueRepository;
import io.butler.bet.data.PlayerRepository;
import io.butler.bet.data.PlayerValueRepository;
import io.butler.bet.data.RosterRepository;
import io.butler.bet.domain.DraftPick;
import io.butler.bet.domain.DraftPickValue;
import io.butler.bet.domain.Player;
import io.butler.bet.domain.PlayerValue;
import io.butler.bet.domain.Roster;

import java.sql.SQLException;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Enumerates the player and draft-pick assets currently owned by every team in a league.
 * Values are optional and source-specific; missing values remain explicit so this inventory can
 * also be used to obtain stable internal IDs for trade comparison.
 */
public final class LeagueAssetInventoryAnalyzer {
    private final LeagueAnalyzer leagues;
    private final LeagueValueSourceResolver sources;
    private final RosterRepository rosters;
    private final PlayerRepository players;
    private final PlayerValueRepository playerValues;
    private final DraftPickRepository draftPicks;
    private final DraftPickValueRepository draftPickValues;

    public LeagueAssetInventoryAnalyzer(Database database) {
        Objects.requireNonNull(database, "database must not be null");
        this.leagues = new LeagueAnalyzer(database);
        this.sources = new LeagueValueSourceResolver(database);
        this.rosters = new RosterRepository(database);
        this.players = new PlayerRepository(database);
        this.playerValues = new PlayerValueRepository(database);
        this.draftPicks = new DraftPickRepository(database);
        this.draftPickValues = new DraftPickValueRepository(database);
    }

    public InventoryReport analyze(String leagueId) throws SQLException {
        String normalizedLeagueId = requireText(leagueId, "leagueId");
        return analyzeResolved(normalizedLeagueId, sources.resolve(normalizedLeagueId));
    }

    public InventoryReport analyze(String leagueId, String source) throws SQLException {
        String normalizedLeagueId = requireText(leagueId, "leagueId");
        leagues.analyze(normalizedLeagueId);
        return analyzeResolved(normalizedLeagueId, requireText(source, "source"));
    }

    private InventoryReport analyzeResolved(String leagueId, String source) throws SQLException {
        LeagueAnalyzer.LeagueReport league = leagues.analyze(leagueId);
        Map<String, String> teamNames = new HashMap<>();
        for (var team : league.teams()) teamNames.put(team.teamId(), team.teamName());

        List<TeamInventory> teams = new ArrayList<>();
        int valuedPlayers = 0;
        int missingPlayers = 0;
        int valuedPicks = 0;
        int missingPicks = 0;

        for (var team : league.teams()) {
            List<PlayerAsset> playerAssets = new ArrayList<>();
            for (Roster roster : rosters.findByTeamId(team.teamId())) {
                Player player = players.findById(roster.getPlayerId())
                    .orElseThrow(() -> new IllegalStateException("rostered player not found: " + roster.getPlayerId()));
                PlayerValue value = playerValues.findLatestByPlayerIdAndSource(player.getId(), source).orElse(null);
                if (value == null) missingPlayers++; else valuedPlayers++;
                playerAssets.add(new PlayerAsset(
                    player.getId(), player.getDisplayName(), player.getPosition(), player.getNflTeam(), roster.getSlot(),
                    value == null ? null : value.getValue(), value == null ? null : value.getAsOfDate()));
            }
            playerAssets.sort(Comparator.comparing(PlayerAsset::position, String.CASE_INSENSITIVE_ORDER)
                .thenComparing(PlayerAsset::playerName, String.CASE_INSENSITIVE_ORDER)
                .thenComparing(PlayerAsset::playerId));

            List<DraftPickAsset> pickAssets = new ArrayList<>();
            for (DraftPick pick : draftPicks.findByOwnerTeamId(team.teamId())) {
                DraftPickValue value = draftPickValues.findLatestByDraftPickIdAndSource(pick.getId(), source).orElse(null);
                if (value == null) missingPicks++; else valuedPicks++;
                String originalTeamName = teamNames.get(pick.getOriginalTeamId());
                if (originalTeamName == null) {
                    throw new IllegalStateException("draft pick original team not found in league: " + pick.getOriginalTeamId());
                }
                pickAssets.add(new DraftPickAsset(
                    pick.getId(), pick.getSeason(), pick.getRound(), genericPickLabel(pick.getSeason(), pick.getRound()),
                    pick.getOriginalTeamId(), originalTeamName, pick.getPickNumber(),
                    value == null ? null : value.getValue(), value == null ? null : value.getAsOfDate()));
            }
            pickAssets.sort(Comparator.comparingInt(DraftPickAsset::season)
                .thenComparingInt(DraftPickAsset::round)
                .thenComparing(DraftPickAsset::originalTeamName, String.CASE_INSENSITIVE_ORDER)
                .thenComparing(DraftPickAsset::draftPickId));

            teams.add(new TeamInventory(team.teamId(), team.teamName(),
                List.copyOf(playerAssets), List.copyOf(pickAssets)));
        }

        teams.sort(Comparator.comparing(TeamInventory::teamName, String.CASE_INSENSITIVE_ORDER)
            .thenComparing(TeamInventory::teamId));
        return new InventoryReport(leagueId, source, valuedPlayers, missingPlayers,
            valuedPicks, missingPicks, List.copyOf(teams));
    }

    private static String genericPickLabel(int season, int round) {
        return season + " " + switch (round) {
            case 1 -> "1st";
            case 2 -> "2nd";
            case 3 -> "3rd";
            default -> round + "th";
        };
    }

    private static String requireText(String value, String field) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(field + " must not be blank");
        return value.trim();
    }

    public record InventoryReport(String leagueId, String source,
                                  int valuedPlayers, int missingPlayers,
                                  int valuedDraftPicks, int missingDraftPicks,
                                  List<TeamInventory> teams) {
        public int totalPlayers() { return valuedPlayers + missingPlayers; }
        public int totalDraftPicks() { return valuedDraftPicks + missingDraftPicks; }
        public int valuedAssets() { return valuedPlayers + valuedDraftPicks; }
        public int missingAssets() { return missingPlayers + missingDraftPicks; }
        public int totalAssets() { return totalPlayers() + totalDraftPicks(); }
        public double coveragePercent() {
            return totalAssets() == 0 ? 0.0 : valuedAssets() * 100.0 / totalAssets();
        }
    }

    public record TeamInventory(String teamId, String teamName,
                                List<PlayerAsset> players, List<DraftPickAsset> draftPicks) {
        public int totalAssets() { return players.size() + draftPicks.size(); }
    }

    public record PlayerAsset(String playerId, String playerName, String position, String nflTeam,
                              String slot, Double value, LocalDate asOfDate) {
        public boolean valued() { return value != null; }
    }

    public record DraftPickAsset(String draftPickId, int season, int round, String label,
                                 String originalTeamId, String originalTeamName, Integer pickNumber,
                                 Double value, LocalDate asOfDate) {
        public boolean valued() { return value != null; }
    }
}
