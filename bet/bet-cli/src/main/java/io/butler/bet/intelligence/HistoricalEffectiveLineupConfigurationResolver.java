package io.butler.bet.intelligence;

import io.butler.bet.data.Database;
import io.butler.bet.data.HistoricalEffectiveLineupConfigurationRepository;
import io.butler.bet.data.LeagueConfigurationObservationRepository;
import io.butler.bet.domain.HistoricalEffectiveLineupConfiguration;
import io.butler.bet.domain.LeagueConfigurationObservation;

import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Selects one historical lineup shape from immutable persisted evidence.
 * Raw scoring settings always remain those of the raw provider configuration.
 */
public final class HistoricalEffectiveLineupConfigurationResolver {
    public static final String POLICY_ID =
        "historical-effective-lineup-configuration-v1-raw-unless-bf592-derived-provenance-exact";
    public static final String DERIVATION_POLICY_ID =
        "bf592-effective-lineup-derivation-v1-full-one-short-bf590-bf591-agreement-no-reconstruction";

    private final Database database;

    public HistoricalEffectiveLineupConfigurationResolver(Database database) {
        this.database = Objects.requireNonNull(database, "database must not be null");
    }

    public Selection select(String leagueId, int season, String source) throws SQLException {
        LeagueConfigurationObservation raw = new LeagueConfigurationObservationRepository(database)
            .findLatestForSeason(leagueId, season, source)
            .orElseThrow(() -> new IllegalStateException(
                "No raw league configuration observation for " + leagueId + "/" + season + "/" + source));

        var policy = new LineupSlotEligibilityPolicy();
        List<String> rawSupported = raw.lineupSlots().stream()
            .filter(slot -> policy.ruleFor(slot).state() == LineupSlotEligibilityPolicy.SlotState.STARTING_SUPPORTED)
            .toList();
        Optional<HistoricalEffectiveLineupConfiguration> derivation =
            new HistoricalEffectiveLineupConfigurationRepository(database)
                .findLatestForSeason(leagueId, season, source);
        if (derivation.isEmpty()) {
            return new Selection(
                POLICY_ID,
                State.RAW_PROVIDER_CONFIGURATION,
                raw,
                raw.lineupSlots(),
                rawSupported,
                Optional.empty());
        }

        HistoricalEffectiveLineupConfiguration value = derivation.orElseThrow();
        validateAgainstRaw(raw, rawSupported, value, policy);
        List<String> effectiveFull = removeSupportedOrdinal(raw.lineupSlots(), value.omittedOrdinal(), policy);
        List<String> effectiveSupported = effectiveFull.stream()
            .filter(slot -> policy.ruleFor(slot).state() == LineupSlotEligibilityPolicy.SlotState.STARTING_SUPPORTED)
            .toList();
        if (!effectiveSupported.equals(value.effectiveSupportedStartingSlots())) {
            throw new IllegalStateException(
                "BF-592 derived effective slot sequence no longer matches raw provider configuration");
        }
        return new Selection(
            POLICY_ID,
            State.DERIVED_EFFECTIVE_CONFIGURATION,
            raw,
            effectiveFull,
            effectiveSupported,
            derivation);
    }

    private static void validateAgainstRaw(
        LeagueConfigurationObservation raw,
        List<String> rawSupported,
        HistoricalEffectiveLineupConfiguration value,
        LineupSlotEligibilityPolicy policy) {
        if (!DERIVATION_POLICY_ID.equals(value.derivationPolicyId())) {
            throw new IllegalStateException("BF-592 derivation policy is not recognized");
        }
        if (!raw.leagueId().equals(value.leagueId())
            || raw.providerSeason() == null
            || raw.providerSeason() != value.season()
            || !raw.source().equals(value.source())) {
            throw new IllegalStateException("BF-592 derivation identity does not match raw configuration");
        }
        if (!raw.asOfDate().equals(value.rawConfigurationAsOf())) {
            throw new IllegalStateException(
                "BF-592 derivation is stale: raw configuration as-of changed from "
                    + value.rawConfigurationAsOf() + " to " + raw.asOfDate());
        }
        if (!rawSupported.equals(value.rawSupportedStartingSlots())) {
            throw new IllegalStateException(
                "BF-592 derivation is stale: raw supported starting-slot sequence changed");
        }
        List<String> expected = removeSupportedOrdinal(raw.lineupSlots(), value.omittedOrdinal(), policy).stream()
            .filter(slot -> policy.ruleFor(slot).state() == LineupSlotEligibilityPolicy.SlotState.STARTING_SUPPORTED)
            .toList();
        if (!expected.equals(value.effectiveSupportedStartingSlots())) {
            throw new IllegalStateException("BF-592 derivation no longer reconciles to raw configuration");
        }
    }

    static List<String> removeSupportedOrdinal(
        List<String> rawLineupSlots,
        int omittedSupportedOrdinal,
        LineupSlotEligibilityPolicy policy) {
        Objects.requireNonNull(rawLineupSlots, "rawLineupSlots must not be null");
        Objects.requireNonNull(policy, "policy must not be null");
        List<String> result = new ArrayList<>();
        int supportedOrdinal = 0;
        boolean removed = false;
        for (String slot : rawLineupSlots) {
            boolean supported = policy.ruleFor(slot).state()
                == LineupSlotEligibilityPolicy.SlotState.STARTING_SUPPORTED;
            if (supported && supportedOrdinal++ == omittedSupportedOrdinal) {
                removed = true;
                continue;
            }
            result.add(slot);
        }
        if (!removed) {
            throw new IllegalArgumentException("omitted supported ordinal does not exist in raw lineup slots");
        }
        return List.copyOf(result);
    }

    public enum State {
        RAW_PROVIDER_CONFIGURATION,
        DERIVED_EFFECTIVE_CONFIGURATION
    }

    public record Selection(
        String policyId,
        State state,
        LeagueConfigurationObservation rawConfiguration,
        List<String> effectiveLineupSlots,
        List<String> effectiveSupportedStartingSlots,
        Optional<HistoricalEffectiveLineupConfiguration> derivation) {
        public Selection {
            if (!POLICY_ID.equals(policyId)) throw new IllegalArgumentException("unexpected policyId");
            Objects.requireNonNull(state, "state must not be null");
            Objects.requireNonNull(rawConfiguration, "rawConfiguration must not be null");
            effectiveLineupSlots = List.copyOf(Objects.requireNonNull(
                effectiveLineupSlots, "effectiveLineupSlots must not be null"));
            effectiveSupportedStartingSlots = List.copyOf(Objects.requireNonNull(
                effectiveSupportedStartingSlots, "effectiveSupportedStartingSlots must not be null"));
            derivation = Objects.requireNonNull(derivation, "derivation must not be null");
            if ((state == State.DERIVED_EFFECTIVE_CONFIGURATION) != derivation.isPresent()) {
                throw new IllegalArgumentException("derived state must exactly reflect derivation presence");
            }
        }
    }
}
