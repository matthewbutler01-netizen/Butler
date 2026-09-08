package io.butler.bet.sleeper;

import io.butler.bet.data.Database;

import java.io.IOException;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/** Read-only BF-599 gate for safely bootstrapping populated 2026 Sleeper rosters into Butler. */
public final class SleeperCurrentSeasonHydrationEligibilityAudit {
    public static final String POLICY_ID =
        "sleeper-current-season-hydration-eligibility-v1-2026-bf598-structural-preflight-read-only";
    public static final int TARGET_SEASON = 2026;

    private final Source source;

    public SleeperCurrentSeasonHydrationEligibilityAudit(Database database) {
        Objects.requireNonNull(database, "database must not be null");
        SleeperLiveSeasonOperationalReadinessAudit audit =
            new SleeperLiveSeasonOperationalReadinessAudit(database);
        this.source = audit::audit;
    }

    SleeperCurrentSeasonHydrationEligibilityAudit(Source source) {
        this.source = Objects.requireNonNull(source, "source must not be null");
    }

    public EligibilityReport audit(String leagueId)
        throws SQLException, IOException, InterruptedException {
        String normalizedLeagueId = requireText(leagueId, "leagueId");
        SleeperLiveSeasonOperationalReadinessAudit.AuditReport live = source.audit(normalizedLeagueId);

        List<String> blockers = new ArrayList<>();
        if (live.targetSeason() != TARGET_SEASON || live.providerSeason() != TARGET_SEASON) {
            blockers.add("Provider season is " + live.providerSeason()
                + "; hydration target is " + TARGET_SEASON);
        }
        if (!"in_season".equals(live.providerStatus())) {
            blockers.add("Provider league status is " + nullable(live.providerStatus())
                + "; hydration requires in_season");
        }
        if (live.providerDeclaredRosterCount() <= 0) {
            blockers.add("Provider declared roster count is missing or zero");
        }
        if (live.providerRosterCount() <= 0) {
            blockers.add("Provider returned no current rosters");
        }
        if (live.providerDeclaredRosterCount() != live.providerRosterCount()) {
            blockers.add("Provider declared roster count " + live.providerDeclaredRosterCount()
                + " does not match returned roster count " + live.providerRosterCount());
        }
        if (live.persistedTeamCount() != live.providerRosterCount()) {
            blockers.add("Persisted Butler team count " + live.persistedTeamCount()
                + " does not match provider roster count " + live.providerRosterCount());
        }
        if (live.rosterPositionCount() == 0) {
            blockers.add("Provider roster_positions are missing");
        }
        if (live.scoringSettingCount() == 0) {
            blockers.add("Provider scoring_settings are missing");
        }
        if (!live.providerRosterIdsMissingPersistedTeam().isEmpty()) {
            blockers.add("Provider roster ids missing persisted Butler teams: "
                + live.providerRosterIdsMissingPersistedTeam());
        }
        if (!live.persistedTeamRosterIdsMissingProvider().isEmpty()) {
            blockers.add("Persisted Butler team roster ids absent from provider: "
                + live.persistedTeamRosterIdsMissingProvider());
        }
        if (live.ownerlessRosters() > 0) {
            blockers.add(live.ownerlessRosters() + " provider roster(s) have no owner_id");
        }
        if (!live.unknownOwnerIds().isEmpty()) {
            blockers.add("Roster owner ids absent from provider user list: " + live.unknownOwnerIds());
        }
        if (live.rosterOwners().size() != live.providerRosterCount()) {
            blockers.add("Roster observation count " + live.rosterOwners().size()
                + " does not match provider roster count " + live.providerRosterCount());
        }

        List<Integer> playerlessRosterIds = live.rosterOwners().stream()
            .filter(roster -> roster.playerCount() == 0)
            .map(SleeperLiveSeasonOperationalReadinessAudit.RosterOwnerObservation::rosterId)
            .sorted()
            .toList();
        if (!playerlessRosterIds.isEmpty()) {
            blockers.add(playerlessRosterIds.size()
                + " provider roster(s) contain no current player identities: " + playerlessRosterIds);
        }

        List<Integer> starterlessRosterIds = live.rosterOwners().stream()
            .filter(roster -> roster.starterCount() == 0)
            .map(SleeperLiveSeasonOperationalReadinessAudit.RosterOwnerObservation::rosterId)
            .sorted()
            .toList();
        if (!starterlessRosterIds.isEmpty()) {
            blockers.add(starterlessRosterIds.size()
                + " provider roster(s) contain no starter identities: " + starterlessRosterIds);
        }

        EligibilityState state = blockers.isEmpty()
            ? EligibilityState.READY_TO_HYDRATE
            : EligibilityState.BLOCKED;

        return new EligibilityReport(
            POLICY_ID,
            live.leagueId(),
            live.leagueName(),
            live.sleeperLeagueId(),
            live.providerSeason(),
            live.providerStatus(),
            live.providerLeg(),
            live.providerDeclaredRosterCount(),
            live.providerRosterCount(),
            live.persistedTeamCount(),
            live.providerRosterEntries(),
            live.distinctCurrentPlayerIds(),
            live.exactMappedCurrentPlayerIds(),
            live.unmappedCurrentPlayerIds(),
            live.unmappedPlayerExamples(),
            playerlessRosterIds,
            starterlessRosterIds,
            List.copyOf(blockers),
            state,
            live.observedAtUtc());
    }

    @FunctionalInterface
    interface Source {
        SleeperLiveSeasonOperationalReadinessAudit.AuditReport audit(String leagueId)
            throws SQLException, IOException, InterruptedException;
    }

    public enum EligibilityState {
        READY_TO_HYDRATE,
        BLOCKED
    }

    public record EligibilityReport(
        String policyId,
        String leagueId,
        String leagueName,
        String sleeperLeagueId,
        int providerSeason,
        String providerStatus,
        Integer providerLeg,
        int providerDeclaredRosterCount,
        int providerRosterCount,
        int persistedTeamCount,
        int providerRosterEntries,
        int distinctCurrentPlayerIds,
        int exactMappedCurrentPlayerIds,
        int unmappedCurrentPlayerIds,
        List<String> unmappedPlayerExamples,
        List<Integer> playerlessRosterIds,
        List<Integer> starterlessRosterIds,
        List<String> blockers,
        EligibilityState state,
        Instant observedAtUtc) {
        public EligibilityReport {
            if (!POLICY_ID.equals(policyId)) throw new IllegalArgumentException("unexpected policyId");
            leagueId = requireText(leagueId, "leagueId");
            leagueName = requireText(leagueName, "leagueName");
            sleeperLeagueId = requireText(sleeperLeagueId, "sleeperLeagueId");
            unmappedPlayerExamples = List.copyOf(Objects.requireNonNull(
                unmappedPlayerExamples, "unmappedPlayerExamples must not be null"));
            playerlessRosterIds = List.copyOf(Objects.requireNonNull(
                playerlessRosterIds, "playerlessRosterIds must not be null"));
            starterlessRosterIds = List.copyOf(Objects.requireNonNull(
                starterlessRosterIds, "starterlessRosterIds must not be null"));
            blockers = List.copyOf(Objects.requireNonNull(blockers, "blockers must not be null"));
            Objects.requireNonNull(state, "state must not be null");
            Objects.requireNonNull(observedAtUtc, "observedAtUtc must not be null");
            if (state == EligibilityState.READY_TO_HYDRATE && !blockers.isEmpty()) {
                throw new IllegalArgumentException("READY_TO_HYDRATE must have no blockers");
            }
            if (state == EligibilityState.BLOCKED && blockers.isEmpty()) {
                throw new IllegalArgumentException("BLOCKED must retain explicit blockers");
            }
        }
    }

    private static String nullable(String value) {
        return value == null || value.isBlank() ? "missing" : value;
    }

    private static String requireText(String value, String field) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(field + " must not be blank");
        return value.trim();
    }
}
