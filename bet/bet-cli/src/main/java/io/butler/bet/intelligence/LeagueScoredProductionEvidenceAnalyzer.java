package io.butler.bet.intelligence;

import io.butler.bet.data.Database;
import io.butler.bet.data.LeagueScoringSettingsRepository;
import io.butler.bet.data.PlayerRepository;
import io.butler.bet.data.PlayerSeasonProductionRepository;
import io.butler.bet.data.RosterRepository;
import io.butler.bet.data.TeamRepository;

import java.math.BigDecimal;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/** League-wide descriptive scored-production evidence without ranking or recommendation. */
public final class LeagueScoredProductionEvidenceAnalyzer {
    public static final String POLICY_ID =
        "league-scored-production-evidence-v1-rostered-players-no-ranking";

    private final Database database;

    public LeagueScoredProductionEvidenceAnalyzer(Database database) {
        this.database = Objects.requireNonNull(database, "database must not be null");
    }

    public EvidenceReport analyze(String leagueId, int season, String source) throws SQLException {
        String normalizedLeagueId = requireText(leagueId, "leagueId");
        String normalizedSource = requireText(source, "source");
        if (season <= 0) throw new IllegalArgumentException("season must be positive");

        var coverage = new LeagueScoringCoverageAnalyzer(database).analyze(normalizedLeagueId);
        if (!coverage.exactScoringEligible()) {
            throw new IllegalStateException(
                "Exact league scoring unavailable for " + normalizedLeagueId + ": " + coverage.reason());
        }

        var settings = new LeagueScoringSettingsRepository(database).findByLeagueId(normalizedLeagueId);
        var teams = new TeamRepository(database).findByLeagueId(normalizedLeagueId);
        var rosters = new RosterRepository(database);
        var players = new PlayerRepository(database);
        var production = new PlayerSeasonProductionRepository(database);
        var scoring = new CoveredProductionScoringPolicy();
        List<PlayerEvidence> evidence = new ArrayList<>();
        int covered = 0;

        for (var team : teams) {
            for (var roster : rosters.findByTeamId(team.getId())) {
                var player = players.findById(roster.getPlayerId()).orElseThrow(() ->
                    new IllegalStateException("Roster player record missing: " + roster.getPlayerId()));
                var snapshot = production.findLatest(player.getId(), season, normalizedSource);
                if (snapshot.isEmpty()) {
                    evidence.add(new PlayerEvidence(
                        team.getId(), team.getName(), roster.getSlot(), player.getId(), player.getDisplayName(),
                        player.getPosition(), false, null, null, null, "No persisted production for requested season/source"));
                    continue;
                }
                var score = scoring.score(snapshot.orElseThrow(), settings);
                covered++;
                evidence.add(new PlayerEvidence(
                    team.getId(), team.getName(), roster.getSlot(), player.getId(), player.getDisplayName(),
                    player.getPosition(), true, score.totalPoints(), snapshot.orElseThrow().id(),
                    snapshot.orElseThrow().asOfDate(), null));
            }
        }

        return new EvidenceReport(
            POLICY_ID, coverage.policyId(), CoveredProductionScoringPolicy.POLICY_ID,
            coverage.leagueId(), coverage.leagueName(), season, normalizedSource,
            List.copyOf(evidence), covered);
    }

    private static String requireText(String value, String field) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(field + " must not be blank");
        return value.trim();
    }

    public record PlayerEvidence(
        String teamId,
        String teamName,
        String rosterSlot,
        String playerId,
        String playerName,
        String position,
        boolean available,
        BigDecimal fantasyPoints,
        String productionId,
        LocalDate productionAsOf,
        String unavailableReason) {
        public PlayerEvidence {
            requireText(teamId, "teamId");
            requireText(teamName, "teamName");
            requireText(rosterSlot, "rosterSlot");
            requireText(playerId, "playerId");
            requireText(playerName, "playerName");
            requireText(position, "position");
            if (available) {
                Objects.requireNonNull(fantasyPoints, "fantasyPoints must not be null when available");
                requireText(productionId, "productionId");
                Objects.requireNonNull(productionAsOf, "productionAsOf must not be null when available");
                if (unavailableReason != null) throw new IllegalArgumentException("available evidence cannot have unavailableReason");
            } else {
                if (fantasyPoints != null || productionId != null || productionAsOf != null) {
                    throw new IllegalArgumentException("unavailable evidence cannot carry score provenance");
                }
                requireText(unavailableReason, "unavailableReason");
            }
        }
    }

    public record EvidenceReport(
        String policyId,
        String coveragePolicyId,
        String scoringPolicyId,
        String leagueId,
        String leagueName,
        int season,
        String source,
        List<PlayerEvidence> players,
        int coveredPlayers) {
        public EvidenceReport {
            if (!POLICY_ID.equals(policyId)) throw new IllegalArgumentException("unexpected policyId");
            requireText(coveragePolicyId, "coveragePolicyId");
            requireText(scoringPolicyId, "scoringPolicyId");
            requireText(leagueId, "leagueId");
            requireText(leagueName, "leagueName");
            if (season <= 0) throw new IllegalArgumentException("season must be positive");
            requireText(source, "source");
            players = List.copyOf(Objects.requireNonNull(players, "players must not be null"));
            if (coveredPlayers < 0 || coveredPlayers > players.size()) {
                throw new IllegalArgumentException("coveredPlayers must be within player count");
            }
        }

        public boolean complete() {
            return coveredPlayers == players.size();
        }

        public double coveragePercent() {
            return players.isEmpty() ? 100.0 : coveredPlayers * 100.0 / players.size();
        }
    }
}
