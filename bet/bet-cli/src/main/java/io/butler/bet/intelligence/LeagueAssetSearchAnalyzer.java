package io.butler.bet.intelligence;

import io.butler.bet.data.Database;

import java.sql.SQLException;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

/**
 * Searches a league's current player and draft-pick inventory without auto-selecting ambiguous matches.
 * Matching is a transparent case-insensitive substring check across the displayed asset metadata.
 */
public final class LeagueAssetSearchAnalyzer {
    private final LeagueAssetInventoryAnalyzer inventory;

    public LeagueAssetSearchAnalyzer(Database database) {
        Objects.requireNonNull(database, "database must not be null");
        this.inventory = new LeagueAssetInventoryAnalyzer(database);
    }

    public SearchReport search(String leagueId, String query) throws SQLException {
        String normalizedQuery = requireText(query, "query");
        return search(inventory.analyze(leagueId), normalizedQuery);
    }

    public SearchReport search(String leagueId, String query, String source) throws SQLException {
        String normalizedQuery = requireText(query, "query");
        return search(inventory.analyze(leagueId, source), normalizedQuery);
    }

    private SearchReport search(LeagueAssetInventoryAnalyzer.InventoryReport report, String query) {
        String needle = query.toLowerCase(Locale.ROOT);
        List<PlayerMatch> players = new ArrayList<>();
        List<DraftPickMatch> picks = new ArrayList<>();

        for (var team : report.teams()) {
            for (var player : team.players()) {
                if (!matchesPlayer(needle, team, player)) continue;
                players.add(new PlayerMatch(
                    team.teamId(), team.teamName(), player.playerId(), player.playerName(), player.position(),
                    player.nflTeam(), player.slot(), player.value(), player.asOfDate()));
            }
            for (var pick : team.draftPicks()) {
                if (!matchesPick(needle, team, pick)) continue;
                picks.add(new DraftPickMatch(
                    team.teamId(), team.teamName(), pick.draftPickId(), pick.season(), pick.round(), pick.label(),
                    pick.originalTeamId(), pick.originalTeamName(), pick.pickNumber(), pick.value(), pick.asOfDate()));
            }
        }

        players.sort(Comparator.comparing(PlayerMatch::teamName, String.CASE_INSENSITIVE_ORDER)
            .thenComparing(PlayerMatch::playerName, String.CASE_INSENSITIVE_ORDER)
            .thenComparing(PlayerMatch::playerId));
        picks.sort(Comparator.comparing(DraftPickMatch::teamName, String.CASE_INSENSITIVE_ORDER)
            .thenComparingInt(DraftPickMatch::season)
            .thenComparingInt(DraftPickMatch::round)
            .thenComparing(DraftPickMatch::originalTeamName, String.CASE_INSENSITIVE_ORDER)
            .thenComparing(DraftPickMatch::draftPickId));

        return new SearchReport(report.leagueId(), report.source(), query, List.copyOf(players), List.copyOf(picks));
    }

    private static boolean matchesPlayer(String needle, LeagueAssetInventoryAnalyzer.TeamInventory team,
                                         LeagueAssetInventoryAnalyzer.PlayerAsset player) {
        return contains(team.teamId(), needle)
            || contains(team.teamName(), needle)
            || contains(player.playerId(), needle)
            || contains(player.playerName(), needle)
            || contains(player.position(), needle)
            || contains(player.nflTeam(), needle)
            || contains(player.slot(), needle);
    }

    private static boolean matchesPick(String needle, LeagueAssetInventoryAnalyzer.TeamInventory team,
                                       LeagueAssetInventoryAnalyzer.DraftPickAsset pick) {
        return contains(team.teamId(), needle)
            || contains(team.teamName(), needle)
            || contains(pick.draftPickId(), needle)
            || contains(pick.label(), needle)
            || contains(pick.originalTeamId(), needle)
            || contains(pick.originalTeamName(), needle)
            || contains(Integer.toString(pick.season()), needle)
            || contains(Integer.toString(pick.round()), needle)
            || (pick.pickNumber() != null && contains(Integer.toString(pick.pickNumber()), needle));
    }

    private static boolean contains(String value, String needle) {
        return value != null && value.toLowerCase(Locale.ROOT).contains(needle);
    }

    private static String requireText(String value, String field) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(field + " must not be blank");
        return value.trim();
    }

    public record SearchReport(String leagueId, String source, String query,
                               List<PlayerMatch> players, List<DraftPickMatch> draftPicks) {
        public int totalMatches() { return players.size() + draftPicks.size(); }
    }

    public record PlayerMatch(String teamId, String teamName,
                              String playerId, String playerName, String position, String nflTeam, String slot,
                              Double value, LocalDate asOfDate) {
        public boolean valued() { return value != null; }
    }

    public record DraftPickMatch(String teamId, String teamName,
                                 String draftPickId, int season, int round, String label,
                                 String originalTeamId, String originalTeamName, Integer pickNumber,
                                 Double value, LocalDate asOfDate) {
        public boolean valued() { return value != null; }
    }
}
