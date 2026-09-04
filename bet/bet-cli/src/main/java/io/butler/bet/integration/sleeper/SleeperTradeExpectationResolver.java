package io.butler.bet.integration.sleeper;

import io.butler.bet.data.Database;
import io.butler.bet.data.DraftPickRepository;
import io.butler.bet.data.LeagueRepository;
import io.butler.bet.data.PlayerRepository;
import io.butler.bet.data.RosterRepository;
import io.butler.bet.data.TeamRepository;
import io.butler.bet.intelligence.TradeAssetAnalyzer;

import java.sql.SQLException;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** Resolves governed Butler trade coordinates into the exact Sleeper expectation used by BF-398. */
public final class SleeperTradeExpectationResolver {
    public static final String POLICY_ID =
        "sleeper-trade-expectation-resolution-v1-external-id-owned-assets";

    private final LeagueRepository leagues;
    private final TeamRepository teams;
    private final PlayerRepository players;
    private final RosterRepository rosters;
    private final DraftPickRepository picks;

    public SleeperTradeExpectationResolver(Database database) {
        Objects.requireNonNull(database, "database must not be null");
        this.leagues = new LeagueRepository(database);
        this.teams = new TeamRepository(database);
        this.players = new PlayerRepository(database);
        this.rosters = new RosterRepository(database);
        this.picks = new DraftPickRepository(database);
    }

    public Resolution resolve(
        String leagueId,
        String sideATeamId,
        String sideBTeamId,
        TradeAssetAnalyzer.TradePackage sideA,
        TradeAssetAnalyzer.TradePackage sideB,
        int round,
        String creatorUserId,
        long notBeforeEpochMillis) throws SQLException {
        leagueId = requireText(leagueId, "leagueId");
        sideATeamId = requireText(sideATeamId, "sideATeamId");
        sideBTeamId = requireText(sideBTeamId, "sideBTeamId");
        Objects.requireNonNull(sideA, "sideA must not be null");
        Objects.requireNonNull(sideB, "sideB must not be null");
        if (sideATeamId.equals(sideBTeamId)) throw new IllegalArgumentException("trade teams must differ");
        if (round < 1 || round > 30) throw new IllegalArgumentException("round must be between 1 and 30");
        if (notBeforeEpochMillis < 0) throw new IllegalArgumentException("notBeforeEpochMillis must not be negative");
        if (creatorUserId != null && creatorUserId.isBlank()) {
            throw new IllegalArgumentException("creatorUserId must be null or non-blank");
        }

        var league = leagues.findById(leagueId).orElse(null);
        if (league == null) return unavailable("Butler league was not found.");
        String sleeperLeagueId = numericExternalId(league.getExternalId());
        if (sleeperLeagueId == null) return unavailable("Butler league is missing a numeric Sleeper external_id.");

        var teamA = teams.findById(sideATeamId).orElse(null);
        if (teamA == null || !leagueId.equals(teamA.getLeagueId())) {
            return unavailable("Side A Butler team was not found in the requested league.");
        }
        var teamB = teams.findById(sideBTeamId).orElse(null);
        if (teamB == null || !leagueId.equals(teamB.getLeagueId())) {
            return unavailable("Side B Butler team was not found in the requested league.");
        }
        Integer rosterA = positiveIntExternalId(teamA.getExternalId());
        if (rosterA == null) return unavailable("Side A team is missing a positive Sleeper roster external_id.");
        Integer rosterB = positiveIntExternalId(teamB.getExternalId());
        if (rosterB == null) return unavailable("Side B team is missing a positive Sleeper roster external_id.");
        if (rosterA.equals(rosterB)) return unavailable("Trade teams resolve to the same Sleeper roster id.");

        Map<String, Integer> adds = new LinkedHashMap<>();
        Map<String, Integer> drops = new LinkedHashMap<>();
        Set<SleeperReadOnlyClient.DraftPick> draftPicks = new LinkedHashSet<>();

        Resolution failure = resolvePlayers(sideA.playerIds(), sideATeamId, rosterA, rosterB, adds, drops);
        if (failure != null) return failure;
        failure = resolvePlayers(sideB.playerIds(), sideBTeamId, rosterB, rosterA, adds, drops);
        if (failure != null) return failure;
        failure = resolvePicks(sideA.draftPickIds(), leagueId, sideATeamId, rosterA, rosterB, draftPicks);
        if (failure != null) return failure;
        failure = resolvePicks(sideB.draftPickIds(), leagueId, sideBTeamId, rosterB, rosterA, draftPicks);
        if (failure != null) return failure;

        if (adds.isEmpty() && draftPicks.isEmpty()) {
            return unavailable("Resolved counter contains no Sleeper asset movement.");
        }

        var expected = new SleeperTradeReconciliationPolicy.ExpectedTrade(
            sleeperLeagueId,
            round,
            Set.of(rosterA, rosterB),
            adds,
            drops,
            draftPicks,
            creatorUserId == null ? null : creatorUserId.trim(),
            notBeforeEpochMillis);
        return new Resolution(
            POLICY_ID,
            State.RESOLVED,
            expected,
            "Butler league, team, player, pick, and ownership coordinates resolved to Sleeper external IDs.");
    }

    private Resolution resolvePlayers(
        java.util.List<String> playerIds,
        String senderTeamId,
        int senderRosterId,
        int receiverRosterId,
        Map<String, Integer> adds,
        Map<String, Integer> drops) throws SQLException {
        for (String playerId : playerIds) {
            var player = players.findById(playerId).orElse(null);
            if (player == null) return unavailable("Trade player was not found: " + playerId);
            String sleeperPlayerId = textExternalId(player.getExternalId());
            if (sleeperPlayerId == null) return unavailable("Trade player is missing Sleeper external_id: " + playerId);
            if (rosters.findByTeamAndPlayer(senderTeamId, playerId).isEmpty()) {
                return unavailable("Trade player is not owned by the stated sending team: " + playerId);
            }
            if (adds.putIfAbsent(sleeperPlayerId, receiverRosterId) != null
                || drops.putIfAbsent(sleeperPlayerId, senderRosterId) != null) {
                return unavailable("Duplicate Sleeper player identity appeared in both trade packages: " + sleeperPlayerId);
            }
        }
        return null;
    }

    private Resolution resolvePicks(
        java.util.List<String> pickIds,
        String leagueId,
        String senderTeamId,
        int senderRosterId,
        int receiverRosterId,
        Set<SleeperReadOnlyClient.DraftPick> output) throws SQLException {
        for (String pickId : pickIds) {
            var pick = picks.findById(pickId).orElse(null);
            if (pick == null) return unavailable("Trade draft pick was not found: " + pickId);
            if (!leagueId.equals(pick.getLeagueId())) {
                return unavailable("Trade draft pick belongs to a different Butler league: " + pickId);
            }
            if (!senderTeamId.equals(pick.getOwnerTeamId())) {
                return unavailable("Trade draft pick is not owned by the stated sending team: " + pickId);
            }
            var originalTeam = teams.findById(pick.getOriginalTeamId()).orElse(null);
            if (originalTeam == null || !leagueId.equals(originalTeam.getLeagueId())) {
                return unavailable("Draft pick original Butler team could not be resolved: " + pickId);
            }
            Integer originalRosterId = positiveIntExternalId(originalTeam.getExternalId());
            if (originalRosterId == null) {
                return unavailable("Draft pick original team is missing Sleeper roster external_id: " + pickId);
            }
            var sleeperPick = new SleeperReadOnlyClient.DraftPick(
                Integer.toString(pick.getSeason()),
                pick.getRound(),
                originalRosterId,
                senderRosterId,
                receiverRosterId);
            if (!output.add(sleeperPick)) {
                return unavailable("Duplicate Sleeper draft-pick movement appeared in the trade packages: " + pickId);
            }
        }
        return null;
    }

    private static Resolution unavailable(String reason) {
        return new Resolution(POLICY_ID, State.UNAVAILABLE, null, reason);
    }

    private static String numericExternalId(String value) {
        String text = textExternalId(value);
        return text != null && text.matches("[0-9]+") ? text : null;
    }

    private static Integer positiveIntExternalId(String value) {
        String text = numericExternalId(value);
        if (text == null) return null;
        try {
            int parsed = Integer.parseInt(text);
            return parsed > 0 ? parsed : null;
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static String textExternalId(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private static String requireText(String value, String field) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(field + " must not be blank");
        return value.trim();
    }

    public enum State {
        RESOLVED,
        UNAVAILABLE
    }

    public record Resolution(
        String policyId,
        State state,
        SleeperTradeReconciliationPolicy.ExpectedTrade expectedTrade,
        String reason) {
        public Resolution {
            if (!POLICY_ID.equals(policyId)) throw new IllegalArgumentException("unexpected policyId");
            Objects.requireNonNull(state, "state must not be null");
            if (reason == null || reason.isBlank()) throw new IllegalArgumentException("reason must not be blank");
            if ((state == State.RESOLVED) != (expectedTrade != null)) {
                throw new IllegalArgumentException("resolved state must carry exactly one expected trade");
            }
        }

        public boolean available() {
            return state == State.RESOLVED;
        }
    }
}
