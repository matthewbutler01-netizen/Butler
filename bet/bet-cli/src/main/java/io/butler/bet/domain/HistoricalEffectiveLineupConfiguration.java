package io.butler.bet.domain;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Immutable governed derivation of the effective historical starting-slot shape for one league-season.
 * Raw provider configuration remains authoritative source evidence and is never overwritten by this artifact.
 */
public record HistoricalEffectiveLineupConfiguration(
    String leagueId,
    int season,
    String source,
    LocalDate rawConfigurationAsOf,
    LocalDate derivedAsOf,
    String providerLeagueId,
    String predecessorProviderLeagueId,
    int predecessorSeason,
    int omittedOrdinal,
    String omittedSlot,
    List<String> rawSupportedStartingSlots,
    List<String> effectiveSupportedStartingSlots,
    String derivationPolicyId) {

    public HistoricalEffectiveLineupConfiguration {
        leagueId = requireText(leagueId, "leagueId");
        if (season < 1999 || season > 2100) {
            throw new IllegalArgumentException("season must be between 1999 and 2100");
        }
        source = requireText(source, "source");
        Objects.requireNonNull(rawConfigurationAsOf, "rawConfigurationAsOf must not be null");
        Objects.requireNonNull(derivedAsOf, "derivedAsOf must not be null");
        providerLeagueId = requireText(providerLeagueId, "providerLeagueId");
        predecessorProviderLeagueId = requireText(predecessorProviderLeagueId, "predecessorProviderLeagueId");
        if (predecessorSeason < 1999 || predecessorSeason >= season) {
            throw new IllegalArgumentException("predecessorSeason must precede season");
        }
        omittedSlot = requireText(omittedSlot, "omittedSlot");
        derivationPolicyId = requireText(derivationPolicyId, "derivationPolicyId");
        rawSupportedStartingSlots = List.copyOf(Objects.requireNonNull(
            rawSupportedStartingSlots, "rawSupportedStartingSlots must not be null"));
        effectiveSupportedStartingSlots = List.copyOf(Objects.requireNonNull(
            effectiveSupportedStartingSlots, "effectiveSupportedStartingSlots must not be null"));
        if (rawSupportedStartingSlots.size() != effectiveSupportedStartingSlots.size() + 1) {
            throw new IllegalArgumentException("raw supported starting slots must be exactly one longer than effective slots");
        }
        if (omittedOrdinal < 0 || omittedOrdinal >= rawSupportedStartingSlots.size()) {
            throw new IllegalArgumentException("omittedOrdinal must identify a raw supported starting slot");
        }
        if (!omittedSlot.equals(rawSupportedStartingSlots.get(omittedOrdinal))) {
            throw new IllegalArgumentException("omittedSlot must match raw supported starting-slot ordinal");
        }
        List<String> expectedEffective = new ArrayList<>(rawSupportedStartingSlots);
        expectedEffective.remove(omittedOrdinal);
        if (!expectedEffective.equals(effectiveSupportedStartingSlots)) {
            throw new IllegalArgumentException("effective supported starting slots must equal raw slots with the governed omission removed");
        }
    }

    private static String requireText(String value, String field) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(field + " must not be blank");
        return value.trim();
    }
}
