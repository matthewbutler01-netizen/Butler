package io.butler.bet.intelligence;

import io.butler.bet.data.Database;
import io.butler.bet.data.TeamRepository;
import io.butler.bet.data.TeamWeekMatchupEvidenceRepository;
import io.butler.bet.domain.Team;
import io.butler.bet.domain.TeamWeekMatchupEvidence;

import java.sql.SQLException;
import java.time.LocalDate;
import java.util.List;
import java.util.Objects;

/** Resolves one exact provider-paired opponent for a Butler team-week without prediction or ranking. */
public final class LeagueTeamWeekMatchupEvidenceAnalyzer {
    public static final String POLICY_ID =
        "league-team-week-matchup-evidence-v1-bf840-exact-provider-pairing";
    public static final String DEFAULT_SOURCE = "sleeper";

    private final TeamRepository teams;
    private final TeamWeekMatchupEvidenceRepository matchupEvidence;

    public LeagueTeamWeekMatchupEvidenceAnalyzer(Database database) {
        Objects.requireNonNull(database, "database must not be null");
        this.teams = new TeamRepository(database);
        this.matchupEvidence = new TeamWeekMatchupEvidenceRepository(database);
    }

    public MatchupReport analyze(String leagueId, String teamId, int season, int week) throws SQLException {
        return analyze(leagueId, teamId, season, week, DEFAULT_SOURCE);
    }

    public MatchupReport analyze(String leagueId, String teamId, int season, int week, String source)
        throws SQLException {
        String normalizedLeagueId = requireText(leagueId, "leagueId");
        String normalizedTeamId = requireText(teamId, "teamId");
        String normalizedSource = requireText(source, "source");
        if (season < 1999 || season > 2100) {
            throw new IllegalArgumentException("season must be between 1999 and 2100");
        }
        if (week <= 0) throw new IllegalArgumentException("week must be positive");

        Team target = teams.findById(normalizedTeamId)
            .orElseThrow(() -> new IllegalStateException(
                "BF-840 BLOCKED: target Butler team does not exist: " + normalizedTeamId));
        if (!normalizedLeagueId.equals(target.getLeagueId())) {
            throw new IllegalStateException(
                "BF-840 BLOCKED: target Butler team does not belong to the requested league");
        }

        TeamWeekMatchupEvidence targetEvidence = matchupEvidence
            .findLatest(normalizedTeamId, season, week, normalizedSource)
            .orElseThrow(() -> new IllegalStateException(
                "BF-840 BLOCKED: exact matchup pairing evidence is unavailable for target team/week"));

        if (!normalizedLeagueId.equals(targetEvidence.leagueId())) {
            throw new IllegalStateException(
                "BF-840 BLOCKED: target matchup evidence league does not match the requested league");
        }

        List<TeamWeekMatchupEvidence> samePair = matchupEvidence
            .findLatestByLeagueWeek(normalizedLeagueId, season, week, normalizedSource)
            .stream()
            .filter(e -> e.providerMatchupId() == targetEvidence.providerMatchupId())
            .toList();

        if (samePair.size() != 2) {
            throw new IllegalStateException(
                "BF-840 BLOCKED: provider matchup id " + targetEvidence.providerMatchupId()
                    + " resolves to " + samePair.size() + " Butler teams instead of exactly two");
        }

        TeamWeekMatchupEvidence opponentEvidence = samePair.stream()
            .filter(e -> !normalizedTeamId.equals(e.teamId()))
            .findFirst()
            .orElseThrow(() -> new IllegalStateException(
                "BF-840 BLOCKED: exact matchup pair does not contain one distinct opponent"));

        if (!targetEvidence.asOfDate().equals(opponentEvidence.asOfDate())) {
            throw new IllegalStateException(
                "BF-840 BLOCKED: target and opponent matchup evidence do not share one provider snapshot date");
        }

        Team opponent = teams.findById(opponentEvidence.teamId())
            .orElseThrow(() -> new IllegalStateException(
                "BF-840 BLOCKED: paired opponent Butler team does not exist: " + opponentEvidence.teamId()));
        if (!normalizedLeagueId.equals(opponent.getLeagueId())) {
            throw new IllegalStateException(
                "BF-840 BLOCKED: paired opponent does not belong to the requested league");
        }

        return new MatchupReport(
            normalizedLeagueId,
            season,
            week,
            normalizedSource,
            POLICY_ID,
            targetEvidence.providerMatchupId(),
            target.getId(),
            target.getExternalId(),
            target.getName(),
            opponent.getId(),
            opponent.getExternalId(),
            opponent.getName(),
            targetEvidence.asOfDate());
    }

    public record MatchupReport(
        String leagueId,
        int season,
        int week,
        String source,
        String policyId,
        int providerMatchupId,
        String teamId,
        String teamExternalId,
        String teamName,
        String opponentTeamId,
        String opponentExternalId,
        String opponentTeamName,
        LocalDate asOfDate) {

        public MatchupReport {
            leagueId = requireText(leagueId, "leagueId");
            source = requireText(source, "source");
            policyId = requireText(policyId, "policyId");
            if (!POLICY_ID.equals(policyId)) throw new IllegalArgumentException("unexpected matchup policyId");
            if (season < 1999 || season > 2100) throw new IllegalArgumentException("season must be between 1999 and 2100");
            if (week <= 0) throw new IllegalArgumentException("week must be positive");
            if (providerMatchupId <= 0) throw new IllegalArgumentException("providerMatchupId must be positive");
            teamId = requireText(teamId, "teamId");
            teamExternalId = requireText(teamExternalId, "teamExternalId");
            teamName = requireText(teamName, "teamName");
            opponentTeamId = requireText(opponentTeamId, "opponentTeamId");
            opponentExternalId = requireText(opponentExternalId, "opponentExternalId");
            opponentTeamName = requireText(opponentTeamName, "opponentTeamName");
            if (teamId.equals(opponentTeamId)) throw new IllegalArgumentException("opponent must be distinct from target team");
            Objects.requireNonNull(asOfDate, "asOfDate must not be null");
        }
    }

    private static String requireText(String value, String field) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(field + " must not be blank");
        return value.trim();
    }
}
