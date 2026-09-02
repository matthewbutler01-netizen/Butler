package io.butler.bet.intelligence;

import io.butler.bet.data.Database;
import io.butler.bet.data.LeagueRepository;
import io.butler.bet.data.LeagueValueFormatRepository;
import io.butler.bet.domain.LeagueValueFormat;

import java.sql.SQLException;
import java.util.Objects;

/**
 * Resolves the default player-value source for a league from persisted Sleeper format metadata.
 * Callers may always supply an explicit source override; automatic resolution never guesses when
 * the league format is unavailable or unknown.
 */
public final class LeagueValueSourceResolver {
    private final LeagueRepository leagues;
    private final LeagueValueFormatRepository formats;

    public LeagueValueSourceResolver(Database database) {
        Objects.requireNonNull(database, "database must not be null");
        this.leagues = new LeagueRepository(database);
        this.formats = new LeagueValueFormatRepository(database);
    }

    public String resolve(String leagueId) throws SQLException {
        String normalizedLeagueId = requireText(leagueId, "leagueId");
        leagues.findById(normalizedLeagueId)
            .orElseThrow(() -> new IllegalArgumentException("league not found: " + normalizedLeagueId));

        LeagueValueFormat format = formats.findByLeagueId(normalizedLeagueId)
            .orElseThrow(() -> new IllegalArgumentException(
                "league value format unavailable: re-import the Sleeper league or specify a value source explicitly"));

        return switch (format) {
            case ONE_QB -> DynastyProcessValueImporter.SOURCE_1QB;
            case TWO_QB -> DynastyProcessValueImporter.SOURCE_2QB;
            case UNKNOWN -> throw new IllegalArgumentException(
                "league value format is UNKNOWN: specify a value source explicitly");
        };
    }

    public String resolve(String leagueId, String overrideSource) throws SQLException {
        if (overrideSource != null && !overrideSource.isBlank()) return overrideSource.trim();
        return resolve(leagueId);
    }

    private static String requireText(String value, String field) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(field + " must not be blank");
        return value.trim();
    }
}
