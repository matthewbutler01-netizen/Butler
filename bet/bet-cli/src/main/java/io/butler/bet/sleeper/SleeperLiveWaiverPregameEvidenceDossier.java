package io.butler.bet.sleeper;

import io.butler.bet.data.Database;
import io.butler.bet.data.LiveWaiverAvailabilityRepository;
import io.butler.bet.data.LiveWaiverCurrentWeekStatRepository;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** BF-608 read-only composition of the exact governed pregame waiver evidence lanes. */
public final class SleeperLiveWaiverPregameEvidenceDossier {
    public static final String POLICY_ID =
        "sleeper-live-waiver-pregame-dossier-v1-exact-bf603-bf604-bf606-bf607-read-only";

    private final Database database;

    public SleeperLiveWaiverPregameEvidenceDossier(Database database) {
        this.database = Objects.requireNonNull(database, "database must not be null");
    }

    public DossierReport audit(String leagueId) throws SQLException {
        String normalizedLeagueId = requireText(leagueId, "leagueId");

        SleeperLiveWaiverProductionCoverageAudit.AuditReport production =
            new SleeperLiveWaiverProductionCoverageAudit(database).audit(normalizedLeagueId);
        if (production.unmappedCanonical() != 0) {
            throw new IllegalStateException("BF-608 BLOCKED: BF-604 has "
                + production.unmappedCanonical() + " unmapped canonical candidate(s)");
        }
        if (production.marketActiveCandidates() <= 0) {
            throw new IllegalStateException("BF-608 BLOCKED: latest BF-603 market-active frame is empty");
        }

        LiveWaiverAvailabilityRepository availabilityRepository =
            new LiveWaiverAvailabilityRepository(database);
        LiveWaiverAvailabilityRepository.Snapshot availability = availabilityRepository
            .latestForMarketSnapshot(production.marketSnapshotId())
            .orElseThrow(() -> new IllegalStateException(
                "BF-608 BLOCKED: no BF-606 availability snapshot references latest BF-603 frame "));
        if (!normalizedLeagueId.equals(availability.leagueId())) {
            throw new IllegalStateException("BF-608 BLOCKED: BF-606 league differs from requested league");
        }
        if (availability.candidateCount() != production.marketActiveCandidates()) {
            throw new IllegalStateException("BF-608 BLOCKED: BF-606 candidate count does not reconcile with BF-603");
        }

        CurrentWeekHeader weekHeader = latestCurrentWeekHeader(
            normalizedLeagueId, production.marketSnapshotId());
        if (!availability.id().equals(weekHeader.availabilitySnapshotId())) {
            throw new IllegalStateException("BF-608 BLOCKED: latest BF-607 snapshot does not reference selected BF-606 snapshot");
        }
        if (weekHeader.candidateCount() != production.marketActiveCandidates()) {
            throw new IllegalStateException("BF-608 BLOCKED: BF-607 candidate count does not reconcile with BF-603");
        }
        if (!availability.sleeperLeagueId().equals(weekHeader.sleeperLeagueId())) {
            throw new IllegalStateException("BF-608 BLOCKED: BF-606 and BF-607 Sleeper league identities disagree");
        }

        Map<String, LiveWaiverAvailabilityRepository.Entry> availabilityById = exactAvailabilityEntries(
            availabilityRepository.entries(availability.id()), production.marketActiveCandidates());
        LiveWaiverCurrentWeekStatRepository weekRepository = new LiveWaiverCurrentWeekStatRepository(database);
        Map<String, LiveWaiverCurrentWeekStatRepository.Entry> weekById = exactWeekEntries(
            weekRepository.entries(weekHeader.id()), production.marketActiveCandidates());

        List<CandidateDossier> candidates = new ArrayList<>();
        int teamKnown = 0;
        int providerStatusKnown = 0;
        int injuryFlagPresent = 0;
        int depthEvidencePresent = 0;
        int priorSeasonProductionPresent = 0;
        int currentWeekObserved = 0;

        for (SleeperLiveWaiverProductionCoverageAudit.CandidateCoverage candidate : production.candidates()) {
            String id = candidate.market().sleeperPlayerId();
            LiveWaiverAvailabilityRepository.Entry current = availabilityById.remove(id);
            LiveWaiverCurrentWeekStatRepository.Entry week = weekById.remove(id);
            if (current == null) {
                throw new IllegalStateException("BF-608 BLOCKED: BF-606 is missing exact candidate " + id);
            }
            if (week == null) {
                throw new IllegalStateException("BF-608 BLOCKED: BF-607 is missing exact candidate " + id);
            }
            reconcileMarketIdentity(candidate.market(), current, week);

            String teamState = usable(current.currentTeam()) ? "CURRENT_TEAM_KNOWN" : "CURRENT_TEAM_UNKNOWN";
            String providerStatusState = usable(current.currentStatus())
                ? "PROVIDER_STATUS_KNOWN" : "PROVIDER_STATUS_UNKNOWN";
            String injuryState = usable(current.injuryStatus())
                ? "INJURY_FLAG_PRESENT" : "INJURY_FLAG_ABSENT_OR_UNKNOWN";
            String depthState = usable(current.depthChartPosition()) || current.depthChartOrder() != null
                ? "DEPTH_EVIDENCE_PRESENT" : "DEPTH_EVIDENCE_MISSING";
            String priorProductionState = candidate.state()
                == SleeperLiveWaiverProductionCoverageAudit.CoverageState.MAPPED_WITH_2025_PRODUCTION
                ? "PRIOR_SEASON_PRODUCTION_PRESENT" : "PRIOR_SEASON_PRODUCTION_MISSING";
            String currentWeekState = "SOURCE_PRESENT".equals(week.sourceState())
                ? "CURRENT_WEEK_OBSERVED" : "CURRENT_WEEK_UNOBSERVED";

            if ("CURRENT_TEAM_KNOWN".equals(teamState)) teamKnown++;
            if ("PROVIDER_STATUS_KNOWN".equals(providerStatusState)) providerStatusKnown++;
            if ("INJURY_FLAG_PRESENT".equals(injuryState)) injuryFlagPresent++;
            if ("DEPTH_EVIDENCE_PRESENT".equals(depthState)) depthEvidencePresent++;
            if ("PRIOR_SEASON_PRODUCTION_PRESENT".equals(priorProductionState)) priorSeasonProductionPresent++;
            if ("CURRENT_WEEK_OBSERVED".equals(currentWeekState)) currentWeekObserved++;

            candidates.add(new CandidateDossier(
                candidate.market(), candidate.butlerPlayerId(), candidate.production(), current, week,
                teamState, providerStatusState, injuryState, depthState,
                priorProductionState, currentWeekState));
        }

        if (!availabilityById.isEmpty() || !weekById.isEmpty()) {
            throw new IllegalStateException("BF-608 BLOCKED: BF-606/BF-607 contain candidate identities outside BF-603 frame");
        }

        return new DossierReport(
            POLICY_ID,
            normalizedLeagueId,
            production.marketSnapshotId(),
            production.marketObservedAtUtc(),
            availability.id(),
            availability.observedAtUtc(),
            weekHeader.id(),
            weekHeader.observedAtUtc(),
            weekHeader.observationState(),
            weekHeader.stateSeason(),
            weekHeader.stateWeek(),
            weekHeader.stateSeasonType(),
            production.marketActiveCandidates(),
            teamKnown,
            providerStatusKnown,
            injuryFlagPresent,
            depthEvidencePresent,
            priorSeasonProductionPresent,
            currentWeekObserved,
            List.copyOf(candidates));
    }

    private CurrentWeekHeader latestCurrentWeekHeader(String leagueId, String marketSnapshotId) throws SQLException {
        try (Connection connection = database.openConnection()) {
            requireTable(connection, "live_waiver_current_week_stat_snapshots");
            try (var statement = connection.prepareStatement("""
                SELECT id, league_id, market_snapshot_id, availability_snapshot_id, sleeper_league_id,
                       state_season, state_week, state_season_type, observation_state,
                       observed_at_utc, candidate_count
                FROM live_waiver_current_week_stat_snapshots
                WHERE league_id = ? AND market_snapshot_id = ?
                ORDER BY observed_at_utc DESC, rowid DESC
                LIMIT 1
                """)) {
                statement.setString(1, leagueId);
                statement.setString(2, marketSnapshotId);
                try (ResultSet rs = statement.executeQuery()) {
                    if (!rs.next()) {
                        throw new IllegalStateException(
                            "BF-608 BLOCKED: no BF-607 current-week snapshot references latest BF-603 frame");
                    }
                    return new CurrentWeekHeader(
                        rs.getString("id"), rs.getString("league_id"), rs.getString("market_snapshot_id"),
                        rs.getString("availability_snapshot_id"), rs.getString("sleeper_league_id"),
                        rs.getInt("state_season"), rs.getInt("state_week"), rs.getString("state_season_type"),
                        rs.getString("observation_state"), Instant.parse(rs.getString("observed_at_utc")),
                        rs.getInt("candidate_count"));
                }
            }
        }
    }

    private static Map<String, LiveWaiverAvailabilityRepository.Entry> exactAvailabilityEntries(
        List<LiveWaiverAvailabilityRepository.Entry> entries, int expected) {
        if (entries.size() != expected) {
            throw new IllegalStateException("BF-608 BLOCKED: BF-606 entry count does not reconcile");
        }
        Map<String, LiveWaiverAvailabilityRepository.Entry> result = new LinkedHashMap<>();
        for (var entry : entries) {
            if (result.putIfAbsent(entry.sleeperPlayerId(), entry) != null) {
                throw new IllegalStateException("BF-608 BLOCKED: duplicate BF-606 candidate identity "
                    + entry.sleeperPlayerId());
            }
        }
        return result;
    }

    private static Map<String, LiveWaiverCurrentWeekStatRepository.Entry> exactWeekEntries(
        List<LiveWaiverCurrentWeekStatRepository.Entry> entries, int expected) {
        if (entries.size() != expected) {
            throw new IllegalStateException("BF-608 BLOCKED: BF-607 entry count does not reconcile");
        }
        Map<String, LiveWaiverCurrentWeekStatRepository.Entry> result = new LinkedHashMap<>();
        for (var entry : entries) {
            if (result.putIfAbsent(entry.sleeperPlayerId(), entry) != null) {
                throw new IllegalStateException("BF-608 BLOCKED: duplicate BF-607 candidate identity "
                    + entry.sleeperPlayerId());
            }
        }
        return result;
    }

    private static void reconcileMarketIdentity(
        SleeperLiveWaiverProductionCoverageAudit.MarketCandidate market,
        LiveWaiverAvailabilityRepository.Entry availability,
        LiveWaiverCurrentWeekStatRepository.Entry week) {
        if (!market.displayName().equals(availability.displayName())
            || !market.displayName().equals(week.displayName())
            || !Objects.equals(market.position(), availability.position())
            || !Objects.equals(market.position(), week.position())
            || market.addCount() != availability.addCount()
            || market.addCount() != week.addCount()
            || market.dropCount() != availability.dropCount()
            || market.dropCount() != week.dropCount()
            || market.netAddAttention() != availability.netAddAttention()
            || market.netAddAttention() != week.netAddAttention()
            || !market.frameMembership().equals(availability.frameMembership())
            || !market.frameMembership().equals(week.frameMembership())) {
            throw new IllegalStateException("BF-608 BLOCKED: evidence-lane market metadata mismatch for exact candidate "
                + market.sleeperPlayerId());
        }
    }

    private static void requireTable(Connection connection, String table) throws SQLException {
        try (var statement = connection.prepareStatement(
            "SELECT 1 FROM sqlite_master WHERE type='table' AND name=?")) {
            statement.setString(1, table);
            try (ResultSet rs = statement.executeQuery()) {
                if (!rs.next()) throw new IllegalStateException("BF-608 BLOCKED: required evidence table missing: " + table);
            }
        }
    }

    private static boolean usable(String value) {
        return value != null && !value.isBlank();
    }

    private static String requireText(String value, String field) {
        if (!usable(value)) throw new IllegalArgumentException(field + " must not be blank");
        return value.trim();
    }

    public record CandidateDossier(
        SleeperLiveWaiverProductionCoverageAudit.MarketCandidate market,
        String butlerPlayerId,
        List<SleeperLiveWaiverProductionCoverageAudit.ProductionObservation> priorSeasonProduction,
        LiveWaiverAvailabilityRepository.Entry availability,
        LiveWaiverCurrentWeekStatRepository.Entry currentWeek,
        String teamEvidenceState,
        String providerStatusEvidenceState,
        String injuryEvidenceState,
        String depthEvidenceState,
        String priorSeasonProductionState,
        String currentWeekEvidenceState) {
        public CandidateDossier {
            Objects.requireNonNull(market, "market must not be null");
            butlerPlayerId = requireText(butlerPlayerId, "butlerPlayerId");
            priorSeasonProduction = List.copyOf(Objects.requireNonNull(
                priorSeasonProduction, "priorSeasonProduction must not be null"));
            Objects.requireNonNull(availability, "availability must not be null");
            Objects.requireNonNull(currentWeek, "currentWeek must not be null");
        }
    }

    public record DossierReport(
        String policyId,
        String leagueId,
        String marketSnapshotId,
        String marketObservedAtUtc,
        String availabilitySnapshotId,
        Instant availabilityObservedAtUtc,
        String currentWeekSnapshotId,
        Instant currentWeekObservedAtUtc,
        String currentWeekObservationState,
        int stateSeason,
        int stateWeek,
        String stateSeasonType,
        int candidateCount,
        int teamKnownCount,
        int providerStatusKnownCount,
        int injuryFlagPresentCount,
        int depthEvidencePresentCount,
        int priorSeasonProductionPresentCount,
        int currentWeekObservedCount,
        List<CandidateDossier> candidates) {
        public DossierReport {
            if (!POLICY_ID.equals(policyId)) throw new IllegalArgumentException("unexpected policyId");
            candidates = List.copyOf(Objects.requireNonNull(candidates, "candidates must not be null"));
            if (candidateCount != candidates.size()) throw new IllegalArgumentException("candidate count must reconcile");
        }
    }

    private record CurrentWeekHeader(
        String id, String leagueId, String marketSnapshotId, String availabilitySnapshotId,
        String sleeperLeagueId, int stateSeason, int stateWeek, String stateSeasonType,
        String observationState, Instant observedAtUtc, int candidateCount) {}
}
