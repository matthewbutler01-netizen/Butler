package io.butler.bet.domain;

import java.time.LocalDate;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/** Raw week-specific provider roster membership and ordered starter evidence for one team. */
public record TeamWeekRosterEvidence(
    String id,
    String leagueId,
    String teamId,
    int season,
    int week,
    List<String> providerPlayerIds,
    List<String> providerStarterIds,
    String source,
    LocalDate asOfDate) {

    public TeamWeekRosterEvidence {
        id = requireText(id, "id");
        leagueId = requireText(leagueId, "leagueId");
        teamId = requireText(teamId, "teamId");
        if (season < 1999 || season > 2100) throw new IllegalArgumentException("season must be between 1999 and 2100");
        if (week <= 0) throw new IllegalArgumentException("week must be positive");
        providerPlayerIds = validateIds(providerPlayerIds, "providerPlayerId");
        providerStarterIds = validateIds(providerStarterIds, "providerStarterId");
        source = requireText(source, "source");
        Objects.requireNonNull(asOfDate, "asOfDate must not be null");
    }

    public static TeamWeekRosterEvidence create(
        String leagueId,
        String teamId,
        int season,
        int week,
        List<String> providerPlayerIds,
        List<String> providerStarterIds,
        String source,
        LocalDate asOfDate) {
        return new TeamWeekRosterEvidence(
            UUID.randomUUID().toString(), leagueId, teamId, season, week,
            providerPlayerIds, providerStarterIds, source, asOfDate);
    }

    private static List<String> validateIds(List<String> values, String field) {
        Objects.requireNonNull(values, field + "s must not be null");
        for (String value : values) requireText(value, field);
        return List.copyOf(values);
    }

    private static String requireText(String value, String field) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(field + " must not be blank");
        return value;
    }
}
