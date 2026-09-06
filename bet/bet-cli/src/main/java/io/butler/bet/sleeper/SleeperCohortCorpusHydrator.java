package io.butler.bet.sleeper;

import io.butler.bet.data.Database;
import io.butler.bet.data.LeagueRepository;
import io.butler.bet.domain.League;

import java.io.IOException;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * Lineage-aware executor for the deterministic BF-555 corpus frame.
 * Candidate inclusion is fixed before hydration and never depends on lineup outcomes or readiness impact.
 */
public final class SleeperCohortCorpusHydrator {
    public static final String POLICY_ID =
        "sleeper-cohort-corpus-hydration-v1-fixed-bf555-frame-provider-lineage-one-butler-id-all-discovered-seasons-visible-failures";
    public static final String BOUNDARY =
        "HYDRATION_ONLY_NO_OUTCOME_SELECTION_NO_READINESS_TARGETING_NO_LEAGUE_SIZE_PREFERENCE_NO_THRESHOLD_FITTING_NO_MANAGER_ATTRIBUTION";

    private final Discovery discovery;
    private final LineageLookup lineageLookup;
    private final LeagueRepository leagues;
    private final LeagueAnchorImporter leagueImporter;
    private final SeasonEvidenceHydrator seasonHydrator;

    public SleeperCohortCorpusHydrator(Database database) {
        Objects.requireNonNull(database, "database must not be null");
        SleeperCohortCorpusAcquisitionPlanner planner = new SleeperCohortCorpusAcquisitionPlanner(database);
        SleeperLeagueLineageResolver resolver = new SleeperLeagueLineageResolver();
        SleeperLeagueImporter importer = new SleeperLeagueImporter(database);
        SleeperHistoricalLineupSeasonEvidenceImporter historical =
            new SleeperHistoricalLineupSeasonEvidenceImporter(database);
        this.discovery = planner::plan;
        this.lineageLookup = resolver::resolve;
        this.leagues = new LeagueRepository(database);
        this.leagueImporter = sleeperLeagueId -> importer.importLeague(sleeperLeagueId).leagueId();
        this.seasonHydrator = (butlerLeagueId, season) -> {
            var result = historical.syncSeason(butlerLeagueId, season);
            return new SeasonHydration(
                result.season(),
                result.sleeperLeagueId(),
                result.weeksImported().size(),
                result.teamWeekSnapshots(),
                result.newPlayersCreated(),
                result.asOfDate().toString());
        };
    }

    SleeperCohortCorpusHydrator(
        Discovery discovery,
        LineageLookup lineageLookup,
        Database database,
        LeagueAnchorImporter leagueImporter,
        SeasonEvidenceHydrator seasonHydrator) {
        this.discovery = Objects.requireNonNull(discovery, "discovery must not be null");
        this.lineageLookup = Objects.requireNonNull(lineageLookup, "lineageLookup must not be null");
        Objects.requireNonNull(database, "database must not be null");
        this.leagues = new LeagueRepository(database);
        this.leagueImporter = Objects.requireNonNull(leagueImporter, "leagueImporter must not be null");
        this.seasonHydrator = Objects.requireNonNull(seasonHydrator, "seasonHydrator must not be null");
    }

    public HydrationResult hydrate(String anchorButlerLeagueId, int firstSeason, int lastSeason)
        throws IOException, InterruptedException, SQLException {
        String anchorId = requireText(anchorButlerLeagueId, "anchorButlerLeagueId");
        validateSeasonRange(firstSeason, lastSeason);
        League anchorLeague = leagues.findById(anchorId)
            .orElseThrow(() -> new IllegalArgumentException("anchor league not found: " + anchorId));
        String anchorSleeperLeagueId = requireText(anchorLeague.getExternalId(), "anchor Sleeper league id");

        Map<String, CandidateSeason> candidateBySleeperId = discoverFixedFrame(anchorId, firstSeason, lastSeason);
        if (candidateBySleeperId.isEmpty()) {
            return new HydrationResult(
                POLICY_ID, BOUNDARY, anchorId, anchorSleeperLeagueId, firstSeason, lastSeason,
                0, 0, List.of());
        }

        List<ResolvedCandidate> resolvedCandidates = new ArrayList<>();
        for (CandidateSeason candidate : candidateBySleeperId.values()) {
            SleeperLeagueLineageResolver.Lineage lineage = lineageLookup.resolve(candidate.sleeperLeagueId());
            if (lineage.startingSeason() != candidate.season()) {
                throw new IllegalStateException(
                    "Sleeper candidate season moved during lineage resolution for " + candidate.sleeperLeagueId()
                        + ": discovered=" + candidate.season() + " lineage=" + lineage.startingSeason());
            }
            resolvedCandidates.add(new ResolvedCandidate(candidate, lineage));
        }

        Map<String, List<ResolvedCandidate>> byRoot = new TreeMap<>();
        for (ResolvedCandidate candidate : resolvedCandidates) {
            byRoot.computeIfAbsent(candidate.lineage().rootSleeperLeagueId(), ignored -> new ArrayList<>())
                .add(candidate);
        }
        byRoot.values().forEach(list -> list.sort(CANDIDATE_ORDER));

        List<ExistingLineage> existingLineages = resolveExistingLineages();
        List<LineageHydrationResult> lineageResults = new ArrayList<>();
        for (Map.Entry<String, List<ResolvedCandidate>> entry : byRoot.entrySet()) {
            lineageResults.add(hydrateLineage(entry.getKey(), entry.getValue(), existingLineages));
        }

        return new HydrationResult(
            POLICY_ID,
            BOUNDARY,
            anchorId,
            anchorSleeperLeagueId,
            firstSeason,
            lastSeason,
            candidateBySleeperId.size(),
            byRoot.size(),
            List.copyOf(lineageResults));
    }

    private Map<String, CandidateSeason> discoverFixedFrame(String anchorLeagueId, int firstSeason, int lastSeason)
        throws IOException, InterruptedException, SQLException {
        Map<String, CandidateSeason> bySleeperId = new TreeMap<>();
        List<String> expectedOwners = null;
        String expectedAnchorSleeperLeagueId = null;
        for (int season = firstSeason; season <= lastSeason; season++) {
            SleeperCohortCorpusAcquisitionPlanner.AcquisitionPlan plan = discovery.plan(anchorLeagueId, season);
            if (plan.targetSeason() != season) {
                throw new IllegalStateException("BF-555 discovery returned wrong target season: " + plan.targetSeason());
            }
            if (expectedAnchorSleeperLeagueId == null) {
                expectedAnchorSleeperLeagueId = plan.anchorSleeperLeagueId();
                expectedOwners = plan.anchorOwnerIds();
            } else {
                if (!expectedAnchorSleeperLeagueId.equals(plan.anchorSleeperLeagueId())) {
                    throw new IllegalStateException("anchor Sleeper league identity changed across corpus discovery");
                }
                if (!expectedOwners.equals(plan.anchorOwnerIds())) {
                    throw new IllegalStateException("anchor owner cohort changed across corpus discovery");
                }
            }
            for (SleeperCohortCorpusAcquisitionPlanner.Candidate candidate : plan.candidates()) {
                CandidateSeason current = new CandidateSeason(
                    candidate.sleeperLeagueId(),
                    candidate.leagueName(),
                    candidate.season(),
                    candidate.rosterCount(),
                    candidate.leagueType(),
                    candidate.exposingOwnerIds());
                CandidateSeason previous = bySleeperId.putIfAbsent(current.sleeperLeagueId(), current);
                if (previous != null && !previous.equals(current)) {
                    throw new IllegalStateException(
                        "conflicting BF-555 candidate metadata for Sleeper league " + current.sleeperLeagueId());
                }
            }
        }
        return bySleeperId;
    }

    private List<ExistingLineage> resolveExistingLineages() throws IOException, InterruptedException, SQLException {
        List<ExistingLineage> result = new ArrayList<>();
        for (League league : leagues.findAll()) {
            if (league.getExternalId() == null || league.getExternalId().isBlank()) continue;
            SleeperLeagueLineageResolver.Lineage lineage = lineageLookup.resolve(league.getExternalId());
            result.add(new ExistingLineage(league.getId(), league.getExternalId(), lineage));
        }
        result.sort(Comparator.comparing(ExistingLineage::butlerLeagueId));
        return List.copyOf(result);
    }

    private LineageHydrationResult hydrateLineage(
        String rootSleeperLeagueId,
        List<ResolvedCandidate> candidates,
        List<ExistingLineage> existingLineages)
        throws IOException, InterruptedException, SQLException {
        if (candidates.isEmpty()) throw new IllegalArgumentException("lineage candidates must not be empty");
        ResolvedCandidate latest = candidates.stream().max(CANDIDATE_ORDER).orElseThrow();
        List<CandidateSeason> candidateSeasons = candidates.stream().map(ResolvedCandidate::candidate).toList();

        String branchProblem = branchProblem(candidates);
        if (branchProblem != null) {
            return LineageHydrationResult.blocked(
                rootSleeperLeagueId, latest.candidate().sleeperLeagueId(), candidateSeasons,
                "Provider history is branched inside the fixed candidate frame: " + branchProblem);
        }

        List<ExistingLineage> compatibleExisting = existingLineages.stream()
            .filter(existing -> branchCompatible(latest.lineage(), existing.lineage()))
            .toList();
        if (compatibleExisting.size() > 1) {
            return LineageHydrationResult.blocked(
                rootSleeperLeagueId, latest.candidate().sleeperLeagueId(), candidateSeasons,
                "Multiple persisted Butler leagues map to the same Sleeper lineage branch: "
                    + compatibleExisting.stream().map(ExistingLineage::butlerLeagueId).toList());
        }

        String butlerLeagueId;
        boolean created;
        if (compatibleExisting.size() == 1) {
            butlerLeagueId = compatibleExisting.get(0).butlerLeagueId();
            created = false;
        } else {
            try {
                butlerLeagueId = requireText(
                    leagueImporter.importLeague(latest.candidate().sleeperLeagueId()), "imported Butler league id");
                created = true;
            } catch (IllegalArgumentException | IllegalStateException e) {
                return LineageHydrationResult.importFailed(
                    rootSleeperLeagueId, latest.candidate().sleeperLeagueId(), candidateSeasons,
                    e.getMessage());
            }
        }

        List<SeasonResult> seasonResults = new ArrayList<>();
        for (ResolvedCandidate candidate : candidates) {
            try {
                SeasonHydration hydration = seasonHydrator.sync(butlerLeagueId, candidate.candidate().season());
                if (hydration.season() != candidate.candidate().season()) {
                    throw new IllegalStateException(
                        "season hydrator returned wrong season: requested=" + candidate.candidate().season()
                            + " returned=" + hydration.season());
                }
                if (!candidate.candidate().sleeperLeagueId().equals(hydration.sleeperLeagueId())) {
                    throw new IllegalStateException(
                        "season hydrator resolved a different Sleeper league: requested="
                            + candidate.candidate().sleeperLeagueId() + " returned=" + hydration.sleeperLeagueId());
                }
                seasonResults.add(SeasonResult.success(candidate.candidate(), hydration));
            } catch (IllegalArgumentException | IllegalStateException e) {
                seasonResults.add(SeasonResult.failure(candidate.candidate(), e.getMessage()));
            }
        }

        return new LineageHydrationResult(
            rootSleeperLeagueId,
            latest.candidate().sleeperLeagueId(),
            created ? LineageState.IMPORTED_NEW_BUTLER_LINEAGE : LineageState.REUSED_EXISTING_BUTLER_LINEAGE,
            butlerLeagueId,
            candidateSeasons,
            List.copyOf(seasonResults),
            null);
    }

    private static String branchProblem(List<ResolvedCandidate> candidates) {
        for (int i = 0; i < candidates.size(); i++) {
            for (int j = i + 1; j < candidates.size(); j++) {
                ResolvedCandidate a = candidates.get(i);
                ResolvedCandidate b = candidates.get(j);
                boolean comparable = a.lineage().containsSleeperLeagueId(b.candidate().sleeperLeagueId())
                    || b.lineage().containsSleeperLeagueId(a.candidate().sleeperLeagueId());
                if (!comparable) {
                    return a.candidate().sleeperLeagueId() + " and " + b.candidate().sleeperLeagueId()
                        + " share root " + a.lineage().rootSleeperLeagueId()
                        + " but neither is an ancestor of the other";
                }
            }
        }
        return null;
    }

    private static boolean branchCompatible(
        SleeperLeagueLineageResolver.Lineage candidate,
        SleeperLeagueLineageResolver.Lineage existing) {
        if (!candidate.rootSleeperLeagueId().equals(existing.rootSleeperLeagueId())) return false;
        return candidate.containsSleeperLeagueId(existing.startingSleeperLeagueId())
            || existing.containsSleeperLeagueId(candidate.startingSleeperLeagueId());
    }

    private static void validateSeasonRange(int firstSeason, int lastSeason) {
        if (firstSeason < 1999 || firstSeason > 2100 || lastSeason < 1999 || lastSeason > 2100) {
            throw new IllegalArgumentException("season range must be between 1999 and 2100");
        }
        if (firstSeason > lastSeason) throw new IllegalArgumentException("firstSeason must be <= lastSeason");
    }

    private static String requireText(String value, String field) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(field + " must not be blank");
        return value.trim();
    }

    private static final Comparator<ResolvedCandidate> CANDIDATE_ORDER =
        Comparator.comparingInt((ResolvedCandidate candidate) -> candidate.candidate().season())
            .thenComparing(candidate -> candidate.candidate().sleeperLeagueId());

    interface Discovery {
        SleeperCohortCorpusAcquisitionPlanner.AcquisitionPlan plan(String anchorButlerLeagueId, int season)
            throws IOException, InterruptedException, SQLException;
    }

    interface LineageLookup {
        SleeperLeagueLineageResolver.Lineage resolve(String sleeperLeagueId)
            throws IOException, InterruptedException;
    }

    interface LeagueAnchorImporter {
        String importLeague(String sleeperLeagueId) throws IOException, InterruptedException, SQLException;
    }

    interface SeasonEvidenceHydrator {
        SeasonHydration sync(String butlerLeagueId, int season)
            throws IOException, InterruptedException, SQLException;
    }

    private record ResolvedCandidate(
        CandidateSeason candidate,
        SleeperLeagueLineageResolver.Lineage lineage) {}

    private record ExistingLineage(
        String butlerLeagueId,
        String sleeperLeagueId,
        SleeperLeagueLineageResolver.Lineage lineage) {}

    public record CandidateSeason(
        String sleeperLeagueId,
        String leagueName,
        int season,
        int rosterCount,
        int leagueType,
        List<String> exposingOwnerIds) {
        public CandidateSeason {
            sleeperLeagueId = requireText(sleeperLeagueId, "sleeperLeagueId");
            leagueName = requireText(leagueName, "leagueName");
            if (season < 1999 || season > 2100) throw new IllegalArgumentException("season must be between 1999 and 2100");
            if (rosterCount < 0) throw new IllegalArgumentException("rosterCount must not be negative");
            if (leagueType < 0 || leagueType > 2) throw new IllegalArgumentException("leagueType must be between 0 and 2");
            exposingOwnerIds = List.copyOf(Objects.requireNonNull(exposingOwnerIds, "exposingOwnerIds must not be null"));
        }
    }

    public record SeasonHydration(
        int season,
        String sleeperLeagueId,
        int weeksImported,
        int teamWeekSnapshots,
        int newPlayersCreated,
        String asOfDate) {
        public SeasonHydration {
            if (season < 1999 || season > 2100) throw new IllegalArgumentException("season must be between 1999 and 2100");
            sleeperLeagueId = requireText(sleeperLeagueId, "sleeperLeagueId");
            if (weeksImported <= 0 || teamWeekSnapshots <= 0 || newPlayersCreated < 0) {
                throw new IllegalArgumentException("invalid season hydration counts");
            }
            asOfDate = requireText(asOfDate, "asOfDate");
        }
    }

    public enum LineageState {
        REUSED_EXISTING_BUTLER_LINEAGE,
        IMPORTED_NEW_BUTLER_LINEAGE,
        IMPORT_FAILED,
        BLOCKED_BRANCHED_OR_AMBIGUOUS_LINEAGE
    }

    public enum SeasonState { SUCCESS, FAILED }

    public record SeasonResult(
        CandidateSeason candidate,
        SeasonState state,
        SeasonHydration hydration,
        String failure) {
        public SeasonResult {
            Objects.requireNonNull(candidate, "candidate must not be null");
            Objects.requireNonNull(state, "state must not be null");
            if (state == SeasonState.SUCCESS) {
                Objects.requireNonNull(hydration, "successful season result requires hydration");
                if (failure != null) throw new IllegalArgumentException("successful season result cannot carry failure");
            } else {
                if (hydration != null) throw new IllegalArgumentException("failed season result cannot carry hydration");
                failure = requireText(failure, "failure");
            }
        }

        static SeasonResult success(CandidateSeason candidate, SeasonHydration hydration) {
            return new SeasonResult(candidate, SeasonState.SUCCESS, hydration, null);
        }

        static SeasonResult failure(CandidateSeason candidate, String failure) {
            return new SeasonResult(candidate, SeasonState.FAILED, null,
                failure == null || failure.isBlank() ? "unknown governed evidence failure" : failure);
        }
    }

    public record LineageHydrationResult(
        String rootSleeperLeagueId,
        String latestCandidateSleeperLeagueId,
        LineageState state,
        String butlerLeagueId,
        List<CandidateSeason> candidates,
        List<SeasonResult> seasons,
        String failure) {
        public LineageHydrationResult {
            rootSleeperLeagueId = requireText(rootSleeperLeagueId, "rootSleeperLeagueId");
            latestCandidateSleeperLeagueId = requireText(latestCandidateSleeperLeagueId, "latestCandidateSleeperLeagueId");
            Objects.requireNonNull(state, "state must not be null");
            candidates = List.copyOf(Objects.requireNonNull(candidates, "candidates must not be null"));
            seasons = List.copyOf(Objects.requireNonNull(seasons, "seasons must not be null"));
            if (candidates.isEmpty()) throw new IllegalArgumentException("candidates must not be empty");
            if (state == LineageState.REUSED_EXISTING_BUTLER_LINEAGE
                || state == LineageState.IMPORTED_NEW_BUTLER_LINEAGE) {
                butlerLeagueId = requireText(butlerLeagueId, "butlerLeagueId");
                if (failure != null) throw new IllegalArgumentException("hydrated lineage cannot carry lineage failure");
            } else {
                if (butlerLeagueId != null) throw new IllegalArgumentException("blocked/import-failed lineage cannot carry Butler league id");
                failure = requireText(failure, "failure");
                if (!seasons.isEmpty()) throw new IllegalArgumentException("blocked/import-failed lineage cannot carry season results");
            }
        }

        static LineageHydrationResult blocked(
            String root, String latest, List<CandidateSeason> candidates, String failure) {
            return new LineageHydrationResult(
                root, latest, LineageState.BLOCKED_BRANCHED_OR_AMBIGUOUS_LINEAGE,
                null, candidates, List.of(), failure);
        }

        static LineageHydrationResult importFailed(
            String root, String latest, List<CandidateSeason> candidates, String failure) {
            return new LineageHydrationResult(
                root, latest, LineageState.IMPORT_FAILED,
                null, candidates, List.of(),
                failure == null || failure.isBlank() ? "unknown import failure" : failure);
        }

        public long successfulSeasons() {
            return seasons.stream().filter(result -> result.state() == SeasonState.SUCCESS).count();
        }

        public long failedSeasons() {
            return seasons.stream().filter(result -> result.state() == SeasonState.FAILED).count();
        }
    }

    public record HydrationResult(
        String policyId,
        String boundary,
        String anchorButlerLeagueId,
        String anchorSleeperLeagueId,
        int firstSeason,
        int lastSeason,
        int discoveredCandidateSeasonLeagues,
        int providerRootGroups,
        List<LineageHydrationResult> lineages) {
        public HydrationResult {
            if (!POLICY_ID.equals(policyId)) throw new IllegalArgumentException("unexpected policyId");
            if (!BOUNDARY.equals(boundary)) throw new IllegalArgumentException("unexpected boundary");
            anchorButlerLeagueId = requireText(anchorButlerLeagueId, "anchorButlerLeagueId");
            anchorSleeperLeagueId = requireText(anchorSleeperLeagueId, "anchorSleeperLeagueId");
            validateSeasonRange(firstSeason, lastSeason);
            if (discoveredCandidateSeasonLeagues < 0 || providerRootGroups < 0) {
                throw new IllegalArgumentException("corpus counts must not be negative");
            }
            lineages = List.copyOf(Objects.requireNonNull(lineages, "lineages must not be null"));
            if (lineages.size() != providerRootGroups) {
                throw new IllegalArgumentException("lineage result count must equal providerRootGroups");
            }
        }

        public long newButlerLineages() {
            return lineages.stream().filter(lineage -> lineage.state() == LineageState.IMPORTED_NEW_BUTLER_LINEAGE).count();
        }

        public long reusedButlerLineages() {
            return lineages.stream().filter(lineage -> lineage.state() == LineageState.REUSED_EXISTING_BUTLER_LINEAGE).count();
        }

        public long blockedOrImportFailedLineages() {
            return lineages.stream().filter(lineage -> lineage.state() == LineageState.IMPORT_FAILED
                || lineage.state() == LineageState.BLOCKED_BRANCHED_OR_AMBIGUOUS_LINEAGE).count();
        }

        public long successfulSeasons() {
            return lineages.stream().mapToLong(LineageHydrationResult::successfulSeasons).sum();
        }

        public long failedSeasons() {
            return lineages.stream().mapToLong(LineageHydrationResult::failedSeasons).sum();
        }
    }
}
