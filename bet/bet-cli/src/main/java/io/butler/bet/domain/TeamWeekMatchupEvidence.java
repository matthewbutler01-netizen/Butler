package io.butler.bet.domain;

import java.time.LocalDate;
import java.util.Objects;
import java.util.UUID;

/** Exact provider team-week matchup pairing evidence. */
public record TeamWeekMatchupEvidence(
    String id,
    String leagueId,
    String teamId,
    int season,
    int week,
    int providerMatchupId,
    String source,
    LocalDate asOfDate) {

    public TeamWeekMatchupEvidence {
        id = requireText(id, "id");
        leagueId = requireText(leagueId, "leagueId");
        teamId = requireText(teamId, "teamId");
        if (season < 1999 || season > 2100) throw new IllegalArgumentException("season must be between 1999 and 2100");
        if (week <= 0) throw new IllegalArgumentException("week must be positive");
        if (providerMatchupId <= 0) throw new IllegalArgumentException("providerMatchupId must be positive");
        source = requireText(source, "source");
        Objects.requireNonNull(asOfDate, "asOfDate must not be null");
    }

    public static TeamWeekMatchupEvidence create(
        String leagueId,
        String teamId,
        int season,
        int week,
        int providerMatchupId,
        String source,
        LocalDate asOfDate) {
        return new TeamWeekMatchupEvidence(
            UUID.randomUUID().toString(),
            leagueId,
            teamId,
            season,
            week,
            providerMatchupId,
            source,
            asOfDate);
    }

    private static String requireText(String value, String field) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(field + " must not be blank");
        return value.trim();
    }
}
