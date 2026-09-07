package io.butler.bet.sleeper;

import io.butler.bet.data.Database;
import io.butler.bet.data.LeagueRepository;
import io.butler.bet.domain.League;

import java.io.IOException;
import java.sql.SQLException;
import java.util.List;
import java.util.Objects;

/** Governed BF-597 write path from a BF-596-proven 2025 link to its unique 2026 successor. */
public final class SleeperCurrentSeasonSuccessorRelink {
    public static final String POLICY_ID =
        "sleeper-current-season-successor-relink-v1-bf596-unique-lineage-cas-verified";

    private final Database database;
    private final DiscoverySource discoverySource;

    public SleeperCurrentSeasonSuccessorRelink(Database database) {
        this(database, new SleeperCurrentSeasonSuccessorDiscovery(database)::discover);
    }

    SleeperCurrentSeasonSuccessorRelink(Database database, DiscoverySource discoverySource) {
        this.database = Objects.requireNonNull(database, "database must not be null");
        this.discoverySource = Objects.requireNonNull(discoverySource, "discoverySource must not be null");
    }

    public RelinkReport relink(String butlerLeagueId, String expectedSuccessorSleeperLeagueId)
        throws SQLException, IOException, InterruptedException {
        String leagueId = requireText(butlerLeagueId, "butlerLeagueId");
        String expectedSuccessor = requireText(expectedSuccessorSleeperLeagueId,
            "expectedSuccessorSleeperLeagueId");

        // Re-run BF-596 at write time. A copied result from an earlier run is never sufficient by itself.
        var discovery = discoverySource.discover(leagueId);
        if (discovery.state() != SleeperCurrentSeasonSuccessorDiscovery.DiscoveryState.UNIQUE_SUCCESSOR) {
            throw new IllegalStateException(
                "BF-597 requires BF-596 state UNIQUE_SUCCESSOR but found " + discovery.state());
        }

        String discoveredSuccessor = requireText(discovery.uniqueSuccessorSleeperLeagueId(),
            "BF-596 unique successor Sleeper league id");
        if (!expectedSuccessor.equals(discoveredSuccessor)) {
            throw new IllegalStateException(
                "Expected successor " + expectedSuccessor
                    + " does not match BF-596 unique successor " + discoveredSuccessor);
        }
        if (discovery.targetSeason() != SleeperCurrentSeasonSuccessorDiscovery.TARGET_SEASON) {
            throw new IllegalStateException(
                "BF-596 target season changed unexpectedly: " + discovery.targetSeason());
        }

        List<SleeperCurrentSeasonSuccessorDiscovery.CandidateObservation> matches = discovery.matchingCandidates();
        if (matches.size() != 1) {
            throw new IllegalStateException(
                "UNIQUE_SUCCESSOR must contain exactly one matching candidate; found " + matches.size());
        }
        var candidate = matches.get(0);
        if (!candidate.sleeperLeagueId().equals(discoveredSuccessor)
            || candidate.season() != SleeperCurrentSeasonSuccessorDiscovery.TARGET_SEASON
            || candidate.lineageNewestToOldest().isEmpty()
            || !candidate.lineageNewestToOldest().get(0).equals(discoveredSuccessor)
            || !candidate.lineageNewestToOldest().contains(discovery.linkedSleeperLeagueId())) {
            throw new IllegalStateException("BF-596 unique successor lineage proof is internally inconsistent");
        }

        LeagueRepository leagues = new LeagueRepository(database);
        League before = leagues.findById(leagueId)
            .orElseThrow(() -> new IllegalArgumentException("League not found: " + leagueId));
        if (!Objects.equals(before.getExternalId(), discovery.linkedSleeperLeagueId())) {
            throw new IllegalStateException(
                "Persisted Sleeper link changed after BF-596 discovery: expected "
                    + discovery.linkedSleeperLeagueId() + " but found " + before.getExternalId());
        }

        var collision = leagues.findByExternalId(discoveredSuccessor);
        if (collision.isPresent() && !collision.get().getId().equals(leagueId)) {
            throw new IllegalStateException(
                "Successor Sleeper league is already linked to another Butler league: "
                    + collision.get().getId());
        }

        boolean changed = leagues.updateExternalIdAndSeasonIfCurrent(
            leagueId,
            discovery.linkedSleeperLeagueId(),
            discoveredSuccessor,
            SleeperCurrentSeasonSuccessorDiscovery.TARGET_SEASON);
        if (!changed) {
            throw new IllegalStateException(
                "Compare-and-set relink failed because the persisted Sleeper link no longer equals "
                    + discovery.linkedSleeperLeagueId());
        }

        League after = leagues.findById(leagueId)
            .orElseThrow(() -> new IllegalStateException("League disappeared after BF-597 relink: " + leagueId));
        if (!before.getId().equals(after.getId())
            || !before.getName().equals(after.getName())
            || !discoveredSuccessor.equals(after.getExternalId())
            || !Integer.valueOf(SleeperCurrentSeasonSuccessorDiscovery.TARGET_SEASON).equals(after.getSeason())) {
            throw new IllegalStateException("BF-597 persisted read-back verification failed");
        }

        return new RelinkReport(
            POLICY_ID,
            after.getId(),
            after.getName(),
            discovery.linkedSleeperLeagueId(),
            after.getExternalId(),
            after.getSeason(),
            candidate.status(),
            candidate.lineageNewestToOldest(),
            RelinkState.RELINKED_VERIFIED);
    }

    @FunctionalInterface
    interface DiscoverySource {
        SleeperCurrentSeasonSuccessorDiscovery.DiscoveryReport discover(String butlerLeagueId)
            throws SQLException, IOException, InterruptedException;
    }

    public enum RelinkState {
        RELINKED_VERIFIED
    }

    public record RelinkReport(
        String policyId,
        String butlerLeagueId,
        String butlerLeagueName,
        String previousSleeperLeagueId,
        String newSleeperLeagueId,
        int persistedSeason,
        String successorProviderStatus,
        List<String> lineageNewestToOldest,
        RelinkState state) {
        public RelinkReport {
            if (!POLICY_ID.equals(policyId)) throw new IllegalArgumentException("unexpected policyId");
            butlerLeagueId = requireText(butlerLeagueId, "butlerLeagueId");
            butlerLeagueName = requireText(butlerLeagueName, "butlerLeagueName");
            previousSleeperLeagueId = requireText(previousSleeperLeagueId, "previousSleeperLeagueId");
            newSleeperLeagueId = requireText(newSleeperLeagueId, "newSleeperLeagueId");
            if (persistedSeason != SleeperCurrentSeasonSuccessorDiscovery.TARGET_SEASON) {
                throw new IllegalArgumentException(
                    "persistedSeason must be " + SleeperCurrentSeasonSuccessorDiscovery.TARGET_SEASON);
            }
            successorProviderStatus = normalizeOptional(successorProviderStatus);
            lineageNewestToOldest = List.copyOf(Objects.requireNonNull(lineageNewestToOldest,
                "lineageNewestToOldest must not be null"));
            if (lineageNewestToOldest.isEmpty()
                || !newSleeperLeagueId.equals(lineageNewestToOldest.get(0))
                || !lineageNewestToOldest.contains(previousSleeperLeagueId)) {
                throw new IllegalArgumentException("lineage proof must connect new and previous Sleeper league ids");
            }
            Objects.requireNonNull(state, "state must not be null");
        }
    }

    private static String normalizeOptional(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private static String requireText(String value, String field) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(field + " must not be blank");
        return value.trim();
    }
}
