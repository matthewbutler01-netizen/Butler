package io.butler.bet.intelligence;

import io.butler.bet.data.Database;
import io.butler.bet.data.LeagueRepository;
import io.butler.bet.data.PlayerRepository;
import io.butler.bet.data.RosterRepository;
import io.butler.bet.data.TeamRepository;

import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * Narrows a DynastyProcess preview to the rostered players of one league so callers can see
 * exactly which teams would be affected by provider mapping gaps before deciding to persist.
 */
public final class DynastyProcessLeaguePreviewAnalyzer {
    private final LeagueRepository leagues;
    private final TeamRepository teams;
    private final RosterRepository rosters;
    private final PlayerRepository players;

    public DynastyProcessLeaguePreviewAnalyzer(Database database) {
        Objects.requireNonNull(database, "database must not be null");
        this.leagues = new LeagueRepository(database);
        this.teams = new TeamRepository(database);
        this.rosters = new RosterRepository(database);
        this.players = new PlayerRepository(database);
    }

    public LeaguePreview analyze(String leagueId, DynastyProcessValueImporter.ImportResult preview) throws SQLException {
        String normalizedLeagueId = requireText(leagueId, "leagueId");
        Objects.requireNonNull(preview, "preview must not be null");
        leagues.findById(normalizedLeagueId)
            .orElseThrow(() -> new IllegalArgumentException("league not found: " + normalizedLeagueId));

        Set<String> unmatchedPlayerIds = new HashSet<>();
        for (var unmatched : preview.unmatched()) unmatchedPlayerIds.add(unmatched.playerId());

        List<TeamPreview> teamPreviews = new ArrayList<>();
        int rosteredPlayers = 0;
        int matchedPlayers = 0;
        int unmatchedPlayers = 0;
        int ineligiblePlayers = 0;
        int affectedTeams = 0;

        for (var team : teams.findByLeagueId(normalizedLeagueId)) {
            int teamRosterSize = 0;
            int teamMatched = 0;
            int teamIneligible = 0;
            List<PlayerGap> gaps = new ArrayList<>();

            for (var membership : rosters.findByTeamId(team.getId())) {
                var player = players.findById(membership.getPlayerId())
                    .orElseThrow(() -> new IllegalStateException("roster references missing player: " + membership.getPlayerId()));
                teamRosterSize++;
                rosteredPlayers++;
                if (player.getExternalId() == null || player.getExternalId().isBlank()) {
                    teamIneligible++;
                    ineligiblePlayers++;
                    gaps.add(new PlayerGap(player.getId(), player.getDisplayName(), player.getPosition(), GapReason.NO_SLEEPER_ID));
                } else if (unmatchedPlayerIds.contains(player.getId())) {
                    unmatchedPlayers++;
                    gaps.add(new PlayerGap(player.getId(), player.getDisplayName(), player.getPosition(), GapReason.PROVIDER_UNMATCHED));
                } else {
                    teamMatched++;
                    matchedPlayers++;
                }
            }

            if (!gaps.isEmpty()) affectedTeams++;
            teamPreviews.add(new TeamPreview(team.getId(), team.getName(), teamRosterSize, teamMatched,
                gaps.size() - teamIneligible, teamIneligible, List.copyOf(gaps)));
        }

        return new LeaguePreview(normalizedLeagueId, preview.asOfDate(), rosteredPlayers, matchedPlayers,
            unmatchedPlayers, ineligiblePlayers, affectedTeams, List.copyOf(teamPreviews));
    }

    private static String requireText(String value, String field) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(field + " must not be blank");
        return value.trim();
    }

    public record LeaguePreview(String leagueId, java.time.LocalDate asOfDate, int rosteredPlayers,
                                int matchedPlayers, int unmatchedPlayers, int ineligiblePlayers,
                                int affectedTeams, List<TeamPreview> teams) {
        public double coveragePercent() {
            return rosteredPlayers == 0 ? 0.0 : (matchedPlayers * 100.0) / rosteredPlayers;
        }
    }

    public record TeamPreview(String teamId, String teamName, int rosterSize, int matchedPlayers,
                              int unmatchedPlayers, int ineligiblePlayers, List<PlayerGap> gaps) {
        public double coveragePercent() {
            return rosterSize == 0 ? 0.0 : (matchedPlayers * 100.0) / rosterSize;
        }
    }

    public record PlayerGap(String playerId, String playerName, String position, GapReason reason) {}

    public enum GapReason {
        PROVIDER_UNMATCHED,
        NO_SLEEPER_ID
    }
}
