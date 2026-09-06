package io.butler.bet.domain;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Objects;
import java.util.UUID;

/** League-scored provider points for one rostered identity in one historical team week. */
public record ProviderPlayerWeekPointsEvidence(
    String id,
    String leagueId,
    String teamId,
    String providerRosterId,
    String providerLeagueId,
    int season,
    int week,
    String providerPlayerId,
    BigDecimal points,
    String source,
    String sourceSurface,
    LocalDate asOfDate) {

    public ProviderPlayerWeekPointsEvidence {
        id = requireText(id, "id");
        leagueId = requireText(leagueId, "leagueId");
        teamId = requireText(teamId, "teamId");
        providerRosterId = requireText(providerRosterId, "providerRosterId");
        providerLeagueId = requireText(providerLeagueId, "providerLeagueId");
        if (season < 1999 || season > 2100) {
            throw new IllegalArgumentException("season must be between 1999 and 2100");
        }
        if (week <= 0) throw new IllegalArgumentException("week must be positive");
        providerPlayerId = requireText(providerPlayerId, "providerPlayerId");
        points = Objects.requireNonNull(points, "points must not be null");
        source = requireText(source, "source");
        sourceSurface = requireText(sourceSurface, "sourceSurface");
        Objects.requireNonNull(asOfDate, "asOfDate must not be null");
    }

    public static ProviderPlayerWeekPointsEvidence create(
        String leagueId,
        String teamId,
        String providerRosterId,
        String providerLeagueId,
        int season,
        int week,
        String providerPlayerId,
        BigDecimal points,
        String source,
        String sourceSurface,
        LocalDate asOfDate) {
        return new ProviderPlayerWeekPointsEvidence(
            UUID.randomUUID().toString(), leagueId, teamId, providerRosterId, providerLeagueId,
            season, week, providerPlayerId, points, source, sourceSurface, asOfDate);
    }

    private static String requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
        return value.trim();
    }
}
