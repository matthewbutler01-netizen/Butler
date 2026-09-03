package io.butler.bet.domain;

import java.time.LocalDate;

/**
 * Persistable descriptive team-season competitive-performance evidence.
 * This record contains observed results only and intentionally carries no contender/rebuilder label.
 */
public record TeamSeasonPerformance(
    String leagueId,
    String teamId,
    int season,
    int wins,
    int losses,
    int ties,
    double pointsFor,
    double pointsAgainst,
    String source,
    LocalDate asOfDate) {

    public TeamSeasonPerformance {
        leagueId = requireText(leagueId, "leagueId");
        teamId = requireText(teamId, "teamId");
        source = requireText(source, "source");
        if (season < 1999 || season > 2100) throw new IllegalArgumentException("season must be between 1999 and 2100");
        if (wins < 0 || losses < 0 || ties < 0) throw new IllegalArgumentException("record counts must be non-negative");
        if (!Double.isFinite(pointsFor) || pointsFor < 0.0) throw new IllegalArgumentException("pointsFor must be finite and non-negative");
        if (!Double.isFinite(pointsAgainst) || pointsAgainst < 0.0) throw new IllegalArgumentException("pointsAgainst must be finite and non-negative");
        if (asOfDate == null) throw new IllegalArgumentException("asOfDate must not be null");
    }

    public int gamesPlayed() { return wins + losses + ties; }

    public double winPercentage() {
        int games = gamesPlayed();
        return games == 0 ? 0.0 : (wins + 0.5 * ties) / games;
    }

    public double pointDifferential() { return pointsFor - pointsAgainst; }

    private static String requireText(String value, String field) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(field + " must not be blank");
        return value.trim();
    }
}
