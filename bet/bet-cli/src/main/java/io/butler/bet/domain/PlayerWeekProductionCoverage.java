package io.butler.bet.domain;

import java.net.URI;
import java.time.LocalDate;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;

/**
 * Evidence that one complete provider week slice was parsed and persisted, including the Butler
 * players whose provider identity was resolvable during that exact import snapshot.
 *
 * <p>This is source-coverage evidence, not fantasy scoring and not a claim that every rostered
 * player appeared in the provider stats rows. An identity-covered player may legitimately have no
 * stats row for a week; downstream policy may then treat the covered scoring dimensions as zero.</p>
 */
public record PlayerWeekProductionCoverage(
    int season,
    int week,
    String source,
    URI sourceUri,
    LocalDate asOfDate,
    int providerRows,
    int matchedPlayerWeeks,
    int unmatchedProviderRows,
    List<String> identityCoveredPlayerIds) {

    public PlayerWeekProductionCoverage {
        if (season < 1999 || season > 2100) {
            throw new IllegalArgumentException("season must be between 1999 and 2100");
        }
        if (week <= 0) throw new IllegalArgumentException("week must be positive");
        source = requireText(source, "source");
        Objects.requireNonNull(sourceUri, "sourceUri must not be null");
        if (!sourceUri.isAbsolute()) throw new IllegalArgumentException("sourceUri must be absolute");
        Objects.requireNonNull(asOfDate, "asOfDate must not be null");
        if (providerRows <= 0) throw new IllegalArgumentException("providerRows must be positive");
        if (matchedPlayerWeeks < 0 || matchedPlayerWeeks > providerRows) {
            throw new IllegalArgumentException("matchedPlayerWeeks must be within providerRows");
        }
        if (unmatchedProviderRows < 0 || unmatchedProviderRows > providerRows) {
            throw new IllegalArgumentException("unmatchedProviderRows must be within providerRows");
        }
        identityCoveredPlayerIds = List.copyOf(Objects.requireNonNull(
            identityCoveredPlayerIds, "identityCoveredPlayerIds must not be null"));
        HashSet<String> unique = new HashSet<>();
        for (String playerId : identityCoveredPlayerIds) {
            String normalized = requireText(playerId, "identityCoveredPlayerId");
            if (!normalized.equals(playerId)) {
                throw new IllegalArgumentException("identityCoveredPlayerId must already be normalized");
            }
            if (!unique.add(playerId)) {
                throw new IllegalArgumentException("duplicate identityCoveredPlayerId: " + playerId);
            }
        }
    }

    public boolean coversIdentity(String playerId) {
        String normalized = requireText(playerId, "playerId");
        return identityCoveredPlayerIds.contains(normalized);
    }

    private static String requireText(String value, String field) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(field + " must not be blank");
        return value.trim();
    }
}
