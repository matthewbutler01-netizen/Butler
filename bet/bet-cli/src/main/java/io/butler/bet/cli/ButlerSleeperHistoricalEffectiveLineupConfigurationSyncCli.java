package io.butler.bet.cli;

import io.butler.bet.data.Database;
import io.butler.bet.data.HistoricalEffectiveLineupConfigurationRepository;
import io.butler.bet.data.LeagueConfigurationObservationRepository;
import io.butler.bet.domain.HistoricalEffectiveLineupConfiguration;
import io.butler.bet.intelligence.HistoricalEffectiveLineupConfigurationResolver;
import io.butler.bet.intelligence.LeagueSeasonLineupCaptureCommonUniverseEvidenceAnalyzer;
import io.butler.bet.intelligence.SleeperProviderNativeLineupSensitivityCorpusAuditAnalyzer;

import java.nio.file.Path;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/** BF-592 fixed-frame sync for separately persisted effective historical lineup configuration evidence. */
public final class ButlerSleeperHistoricalEffectiveLineupConfigurationSyncCli {
    private static final Path DATABASE_PATH = Path.of("butler.db");
    private static final String SOURCE = "sleeper";

    private ButlerSleeperHistoricalEffectiveLineupConfigurationSyncCli() {}

    public static void main(String[] args) {
        try {
            parse(args);
            Database database = new Database(DATABASE_PATH);
            database.initialize();
            var report = new SleeperProviderNativeLineupSensitivityCorpusAuditAnalyzer(database).audit();
            var starterDiagnostics = ButlerSleeperProviderNativeLineupSensitivityCorpusAuditCli
                .collectStarterSlotDiagnostics(database, report);
            var lineageDiagnostics = ButlerSleeperProviderNativeLineupSensitivityConfigurationLineageCli
                .collect(database, report,
                    sleeperLeagueId -> new io.butler.bet.sleeper.SleeperClient().getLeague(sleeperLeagueId));
            List<SyncResult> results = deriveAndPersist(
                database, report, starterDiagnostics, lineageDiagnostics, LocalDate.now());
            print(report, results);
        } catch (Exception e) {
            System.err.println("Error: " + e.getMessage());
            System.exit(2);
        }
    }

    static void parse(String[] args) {
        if (args != null && args.length != 0) {
            throw new IllegalArgumentException("Usage: sleeperHistoricalEffectiveLineupConfigurationSync");
        }
    }

    static List<SyncResult> deriveAndPersist(
        Database database,
        SleeperProviderNativeLineupSensitivityCorpusAuditAnalyzer.AuditReport report,
        java.util.Map<ButlerSleeperProviderNativeLineupSensitivityCorpusAuditCli.LeagueSeasonKey,
            ButlerSleeperProviderNativeLineupSensitivityCorpusAuditCli.StarterSlotDiagnostics> starterDiagnostics,
        java.util.Map<ButlerSleeperProviderNativeLineupSensitivityConfigurationLineageCli.LeagueSeasonKey,
            ButlerSleeperProviderNativeLineupSensitivityConfigurationLineageCli.CollectedDiagnostic> lineageDiagnostics,
        LocalDate derivedAsOf) throws Exception {
        Objects.requireNonNull(database, "database must not be null");
        Objects.requireNonNull(report, "report must not be null");
        Objects.requireNonNull(starterDiagnostics, "starterDiagnostics must not be null");
        Objects.requireNonNull(lineageDiagnostics, "lineageDiagnostics must not be null");
        Objects.requireNonNull(derivedAsOf, "derivedAsOf must not be null");

        var rawConfigurations = new LeagueConfigurationObservationRepository(database);
        var repository = new HistoricalEffectiveLineupConfigurationRepository(database);
        List<SyncResult> results = new ArrayList<>();

        for (var entry : report.entries()) {
            if (entry.downstreamAudit().isEmpty()
                || entry.downstreamAudit().orElseThrow().sourceCommonUniverse().commonUniverseState()
                    != LeagueSeasonLineupCaptureCommonUniverseEvidenceAnalyzer.CommonUniverseState
                        .UNAVAILABLE_NO_COMMON_COMPARABLE_WEEKS) {
                results.add(SyncResult.notApplicable(entry.leagueId(), entry.leagueName(), entry.season()));
                continue;
            }

            var starterKey = new ButlerSleeperProviderNativeLineupSensitivityCorpusAuditCli.LeagueSeasonKey(
                entry.leagueId(), entry.season());
            var lineageKey = new ButlerSleeperProviderNativeLineupSensitivityConfigurationLineageCli.LeagueSeasonKey(
                entry.leagueId(), entry.season());
            var starter = starterDiagnostics.get(starterKey);
            var collectedLineage = lineageDiagnostics.get(lineageKey);
            try {
                if (starter == null) throw new IllegalStateException("BF-590 diagnostics unavailable");
                if (starter.rosterSnapshots() <= 0 || starter.oneShortSnapshots() != starter.rosterSnapshots()) {
                    throw new IllegalStateException(
                        "complete historical starter frame is not uniformly exactly one supported slot short");
                }
                if (collectedLineage == null || collectedLineage.diagnostic().isEmpty()) {
                    throw new IllegalStateException("BF-591 lineage diagnostics unavailable");
                }
                var lineage = collectedLineage.diagnostic().orElseThrow();
                if (!lineage.persistedMatchesProviderLive()) {
                    throw new IllegalStateException("live provider configuration no longer exactly matches persisted raw configuration");
                }
                if (lineage.predecessorEvidenceState()
                    != ButlerSleeperProviderNativeLineupSensitivityConfigurationLineageCli.PredecessorEvidenceState
                        .PREDECESSOR_UNIQUE_SINGLE_SLOT_ADDITION
                    || lineage.providerAdditions().size() != 1
                    || lineage.predecessor().isEmpty()) {
                    throw new IllegalStateException("BF-591 does not provide one unique predecessor slot transition");
                }

                var addition = lineage.providerAdditions().get(0);
                var compatible = starter.omissionCandidates().stream()
                    .filter(candidate -> candidate.omittedOrdinal() == addition.ordinal()
                        && candidate.slot().equals(addition.slot())
                        && candidate.fullyCompatible()
                        && candidate.evaluatedSnapshots() == starter.rosterSnapshots())
                    .findFirst()
                    .orElseThrow(() -> new IllegalStateException(
                        "BF-590 full-frame compatibility does not agree with BF-591 lineage addition"));
                if (compatible.compatibleSnapshots() != starter.rosterSnapshots()) {
                    throw new IllegalStateException("BF-590 compatibility does not cover the complete snapshot frame");
                }

                var predecessor = lineage.predecessor().orElseThrow();
                if (!starter.supportedStartingSlots().equals(lineage.provider().supportedStartingSlots())) {
                    throw new IllegalStateException("BF-590 raw supported slots do not match BF-591 live provider shape");
                }
                List<String> expectedEffective = new ArrayList<>(starter.supportedStartingSlots());
                expectedEffective.remove(addition.ordinal());
                if (!expectedEffective.equals(predecessor.supportedStartingSlots())) {
                    throw new IllegalStateException("lineage predecessor does not exactly equal raw shape minus governed omission");
                }

                var raw = rawConfigurations.findLatestForSeason(entry.leagueId(), entry.season(), SOURCE)
                    .orElseThrow(() -> new IllegalStateException("raw Sleeper configuration unavailable"));
                if (!raw.asOfDate().equals(starter.configurationAsOf())) {
                    throw new IllegalStateException("BF-590 configuration provenance moved before persistence");
                }
                if (entry.selection().providerAudit() == null
                    || !lineage.provider().id().equals(entry.selection().providerAudit().providerLeagueId())) {
                    throw new IllegalStateException("provider league provenance does not reconcile across BF-566/BF-591");
                }

                HistoricalEffectiveLineupConfiguration derived = new HistoricalEffectiveLineupConfiguration(
                    entry.leagueId(),
                    entry.season(),
                    SOURCE,
                    raw.asOfDate(),
                    derivedAsOf,
                    lineage.provider().id(),
                    predecessor.id(),
                    predecessor.season(),
                    addition.ordinal(),
                    addition.slot(),
                    starter.supportedStartingSlots(),
                    predecessor.supportedStartingSlots(),
                    HistoricalEffectiveLineupConfigurationResolver.DERIVATION_POLICY_ID);
                repository.replace(derived);
                var resolved = new HistoricalEffectiveLineupConfigurationResolver(database)
                    .select(entry.leagueId(), entry.season(), SOURCE);
                if (resolved.state()
                    != HistoricalEffectiveLineupConfigurationResolver.State.DERIVED_EFFECTIVE_CONFIGURATION
                    || !resolved.effectiveSupportedStartingSlots().equals(predecessor.supportedStartingSlots())) {
                    throw new IllegalStateException("persisted BF-592 derivation failed immediate read-back reconciliation");
                }
                results.add(SyncResult.derived(
                    entry.leagueId(), entry.leagueName(), entry.season(), derived));
            } catch (Exception e) {
                results.add(SyncResult.blocked(entry.leagueId(), entry.leagueName(), entry.season(), e.getMessage()));
            }
        }
        return List.copyOf(results);
    }

    static void print(
        SleeperProviderNativeLineupSensitivityCorpusAuditAnalyzer.AuditReport report,
        List<SyncResult> results) {
        System.out.println("Sleeper effective historical lineup configuration sync");
        System.out.println("Policy: " + HistoricalEffectiveLineupConfigurationResolver.DERIVATION_POLICY_ID);
        System.out.println("Fixed-frame league-seasons: " + report.summary().fixedFrameLeagueSeasons());
        System.out.println("Rule: persist a separate effective lineup shape only when the complete BF-590 one-short frame and BF-591 unique lineage addition agree on the same ordinal and slot.");
        for (SyncResult result : results) {
            System.out.println(result.season() + " | " + result.leagueName() + " [" + result.leagueId() + "] | " + result.state());
            if (result.derivation() != null) {
                var value = result.derivation();
                System.out.println("  raw configuration as-of: " + value.rawConfigurationAsOf());
                System.out.println("  provider lineage: " + value.predecessorProviderLeagueId() + " -> " + value.providerLeagueId());
                System.out.println("  governed omission: ordinal " + value.omittedOrdinal() + " " + value.omittedSlot());
                System.out.println("  raw supported starting slots: " + value.rawSupportedStartingSlots());
                System.out.println("  effective supported starting slots: " + value.effectiveSupportedStartingSlots());
                System.out.println("  derived as-of: " + value.derivedAsOf());
            }
            if (result.detail() != null) System.out.println("  detail: " + result.detail());
        }
        System.out.println("Boundary: raw Sleeper configuration and scoring settings are unchanged. No player identity, starter identity, starter order, or missing slot is reconstructed. Failed agreement persists no derivation for that league-season.");
    }

    enum SyncState { DERIVED_AND_PERSISTED, BLOCKED_NO_DERIVATION, NOT_APPLICABLE }

    record SyncResult(
        String leagueId,
        String leagueName,
        int season,
        SyncState state,
        HistoricalEffectiveLineupConfiguration derivation,
        String detail) {
        static SyncResult derived(String id, String name, int season, HistoricalEffectiveLineupConfiguration value) {
            return new SyncResult(id, name, season, SyncState.DERIVED_AND_PERSISTED, value, null);
        }
        static SyncResult blocked(String id, String name, int season, String detail) {
            return new SyncResult(id, name, season, SyncState.BLOCKED_NO_DERIVATION, null, detail);
        }
        static SyncResult notApplicable(String id, String name, int season) {
            return new SyncResult(id, name, season, SyncState.NOT_APPLICABLE, null, null);
        }
    }
}
