package io.butler.bet.intelligence;

import io.butler.bet.data.Database;
import io.butler.bet.data.LeagueRepository;
import io.butler.bet.data.TeamRepository;
import io.butler.bet.data.TeamWeekMatchupEvidenceRepository;
import io.butler.bet.domain.Team;
import io.butler.bet.domain.TeamWeekMatchupEvidence;

import java.sql.SQLException;
import java.time.LocalDate;
import java.util.List;
import java.util.Objects;

/** Resolves one exact opponent only from persisted provider matchup identity. */
public final class WeeklyMatchupWorkspaceAnalyzer {
    private final LeagueRepository leagues;
    private final TeamRepository teams;
    private final TeamWeekMatchupEvidenceRepository matchupEvidence;

    public WeeklyMatchupWorkspaceAnalyzer(Database database) {
        Objects.requireNonNull(database, "database must not be null");
        this.leagues = new LeagueRepository(database);
        this.teams = new TeamRepository(database);
        this.matchupEvidence = new TeamWeekMatchupEvidenceRepository(database);
    }

    public MatchupReport analyze(
        String sleeperLeagueId,
        String targetTeamId,
        int season,
        int week,
        String source) throws SQLException {
        String externalLeagueId = requireText(sleeperLeagueId, "sleeperLeagueId");
        String teamId = requireText(targetTeamId, "targetTeamId");
        String normalizedSource = requireText(source, "source");
        if (season < 1999 || season > 2100) {
            throw new IllegalArgumentException("season must be between 1999 and 2100");
        }
        if (week <= 0) throw new IllegalArgumentException("week must be positive");

        var league = leagues.findByExternalId(externalLeagueId)
            .orElseThrow(() -> new IllegalStateException(
                "No Butler league is mapped to Sleeper league " + externalLeagueId));
        Team target = teams.findById(teamId)
            .orElseThrow(() -> new IllegalStateException("Bound Butler team is unavailable: " + teamId));
        if (!league.getId().equals(target.getLeagueId())) {
            throw new IllegalStateException("Bound Butler team does not belong to the requested league");
        }

        TeamWeekMatchupEvidence targetEvidence = matchupEvidence
            .findLatest(teamId, season, week, normalizedSource)
            .orElseThrow(() -> new IllegalStateException(
                "Exact Sleeper matchup pairing is unavailable for week " + week));

        List<TeamWeekMatchupEvidence> exactPair = matchupEvidence
            .findLatestByLeagueSeasonWeek(league.getId(), season, week, normalizedSource)
            .stream()
            .filter(evidence -> evidence.providerMatchupId() == targetEvidence.providerMatchupId())
            .filter(evidence -> evidence.asOfDate().equals(targetEvidence.asOfDate()))
            .toList();

        if (exactPair.size() != 2) {
            throw new IllegalStateException(
                "Exact Sleeper matchup pairing is incomplete or ambiguous for week " + week
                    + " matchup_id " + targetEvidence.providerMatchupId());
        }
        long targetRows = exactPair.stream().filter(e -> e.teamId().equals(teamId)).count();
        if (targetRows != 1) {
            throw new IllegalStateException("Exact Sleeper matchup pairing does not contain the bound team exactly once");
        }

        TeamWeekMatchupEvidence opponentEvidence = exactPair.stream()
            .filter(e -> !e.teamId().equals(teamId))
            .findFirst()
            .orElseThrow(() -> new IllegalStateException("Exact Sleeper opponent is unavailable"));
        Team opponent = teams.findById(opponentEvidence.teamId())
            .orElseThrow(() -> new IllegalStateException(
                "Exact Sleeper opponent is not mapped to a Butler team: " + opponentEvidence.teamId()));
        if (!league.getId().equals(opponent.getLeagueId())) {
            throw new IllegalStateException("Exact Sleeper opponent does not belong to the requested league");
        }

        return new MatchupReport(
            league.getId(),
            league.getName(),
            season,
            week,
            targetEvidence.providerMatchupId(),
            target.getId(),
            target.getName(),
            opponent.getId(),
            opponent.getName(),
            normalizedSource,
            targetEvidence.asOfDate());
    }

    public record MatchupReport(
        String leagueId,
        String leagueName,
        int season,
        int week,
        int providerMatchupId,
        String userTeamId,
        String userTeamName,
        String opponentTeamId,
        String opponentTeamName,
        String source,
        LocalDate asOfDate) {}

    private static String requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
        return value.trim();
    }
}
