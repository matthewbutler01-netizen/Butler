package io.butler.bet.sleeper;

import io.butler.bet.data.Database;
import io.butler.bet.data.LeagueRepository;
import io.butler.bet.data.TeamRepository;
import io.butler.bet.domain.League;
import io.butler.bet.domain.Team;

import java.io.IOException;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * Read-only planner for deterministic historical corpus acquisition from a known Sleeper owner.
 * The planner intentionally does not inspect lineup-capture outcomes, sensitivity, readiness, or
 * calibration evidence and performs no repository writes.
 */
public final class SleeperCorpusAcquisitionPlanner {
    public static final String POLICY_ID =
        "sleeper-corpus-acquisition-plan-v1-anchor-owner-all-target-season-leagues-provider-id-order-no-outcome-selection";
    public static final String BOUNDARY =
        "DISCOVERY_ONLY_NO_IMPORT_NO_OUTCOME_BASED_SELECTION_NO_THRESHOLD_FITTING_NO_MANAGER_ATTRIBUTION";

    private final SleeperGateway gateway;
    private final LeagueRepository leagues;
    private final TeamRepository teams;

    public SleeperCorpusAcquisitionPlanner(Database database) {
        this(new SleeperApiGateway(), database);
    }

    SleeperCorpusAcquisitionPlanner(SleeperGateway gateway, Database database) {
        Objects.requireNonNull(database, "database must not be null");
        this.gateway = Objects.requireNonNull(gateway, "gateway must not be null");
        this.leagues = new LeagueRepository(database);
        this.teams = new TeamRepository(database);
    }

    public AcquisitionPlan plan(String anchorButlerLeagueId, String anchorButlerTeamId, int targetSeason)
        throws IOException, InterruptedException, SQLException {
        String leagueId = requireText(anchorButlerLeagueId, "anchorButlerLeagueId");
        String teamId = requireText(anchorButlerTeamId, "anchorButlerTeamId");
        if (targetSeason < 1999 || targetSeason > 2100) {
            throw new IllegalArgumentException("targetSeason must be between 1999 and 2100");
        }

        League anchorLeague = leagues.findById(leagueId)
            .orElseThrow(() -> new IllegalArgumentException("anchor league not found: " + leagueId));
        Team anchorTeam = teams.findById(teamId)
            .orElseThrow(() -> new IllegalArgumentException("anchor team not found: " + teamId));
        if (!leagueId.equals(anchorTeam.getLeagueId())) {
            throw new IllegalArgumentException("anchor team does not belong to anchor league");
        }

        String anchorSleeperLeagueId = requireText(anchorLeague.getExternalId(), "anchor Sleeper league id");
        int anchorRosterId = parseRosterId(anchorTeam.getExternalId());
        String ownerId = resolveOwnerId(anchorSleeperLeagueId, anchorRosterId);

        List<SleeperJsonParser.SleeperLeague> providerLeagues =
            gateway.fetchUserLeagues(ownerId, targetSeason);
        if (providerLeagues == null) {
            throw new IllegalStateException("Sleeper user-league response must not be null");
        }

        List<SleeperJsonParser.SleeperLeague> ordered = new ArrayList<>(providerLeagues);
        ordered.sort(Comparator.comparing(SleeperJsonParser.SleeperLeague::id));
        Set<String> seenLeagueIds = new HashSet<>();
        List<Candidate> candidates = new ArrayList<>();
        for (SleeperJsonParser.SleeperLeague candidate : ordered) {
            if (candidate == null) throw new IllegalStateException("Sleeper user-league response contains null league");
            String sleeperLeagueId = requireText(candidate.id(), "candidate Sleeper league id");
            if (!seenLeagueIds.add(sleeperLeagueId)) {
                throw new IllegalStateException("duplicate Sleeper candidate league id: " + sleeperLeagueId);
            }
            if (candidate.season() != targetSeason) {
                throw new IllegalStateException(
                    "Sleeper user-league season mismatch for " + sleeperLeagueId
                        + ": requested=" + targetSeason + " returned=" + candidate.season());
            }

            List<SleeperJsonParser.SleeperRoster> candidateRosters = gateway.fetchRosters(sleeperLeagueId);
            if (candidateRosters == null) {
                throw new IllegalStateException("Sleeper roster response must not be null for " + sleeperLeagueId);
            }
            String existingButlerLeagueId = leagues.findByExternalId(sleeperLeagueId)
                .map(League::getId).orElse(null);
            candidates.add(new Candidate(
                sleeperLeagueId,
                requireText(candidate.name(), "candidate league name"),
                candidate.season(),
                candidate.leagueType(),
                candidate.draftRounds(),
                candidateRosters.size(),
                candidate.rosterPositions(),
                existingButlerLeagueId == null
                    ? CandidateState.DISCOVERED_NOT_PERSISTED
                    : CandidateState.ALREADY_PERSISTED,
                existingButlerLeagueId));
        }

        return new AcquisitionPlan(
            POLICY_ID,
            BOUNDARY,
            leagueId,
            teamId,
            anchorSleeperLeagueId,
            anchorRosterId,
            ownerId,
            targetSeason,
            List.copyOf(candidates));
    }

    private String resolveOwnerId(String anchorSleeperLeagueId, int anchorRosterId)
        throws IOException, InterruptedException {
        List<SleeperJsonParser.SleeperRoster> anchorRosters = gateway.fetchRosters(anchorSleeperLeagueId);
        if (anchorRosters == null) {
            throw new IllegalStateException("Sleeper anchor roster response must not be null");
        }
        String ownerId = null;
        for (SleeperJsonParser.SleeperRoster roster : anchorRosters) {
            if (roster.rosterId() != anchorRosterId) continue;
            if (ownerId != null) {
                throw new IllegalStateException("duplicate anchor Sleeper roster id: " + anchorRosterId);
            }
            ownerId = requireText(roster.ownerId(), "anchor Sleeper owner id");
        }
        if (ownerId == null) {
            throw new IllegalStateException(
                "anchor Sleeper roster id not found in league " + anchorSleeperLeagueId + ": " + anchorRosterId);
        }
        return ownerId;
    }

    private static int parseRosterId(String externalId) {
        String value = requireText(externalId, "anchor team external roster id");
        try {
            int parsed = Integer.parseInt(value);
            if (parsed <= 0) throw new NumberFormatException("non-positive");
            return parsed;
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("anchor team external id must be a positive Sleeper roster id");
        }
    }

    private static String requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
        return value.trim();
    }

    public enum CandidateState {
        ALREADY_PERSISTED,
        DISCOVERED_NOT_PERSISTED
    }

    public record Candidate(
        String sleeperLeagueId,
        String leagueName,
        int season,
        int leagueType,
        int draftRounds,
        int rosterCount,
        List<String> rosterPositions,
        CandidateState state,
        String existingButlerLeagueId) {

        public Candidate {
            sleeperLeagueId = requireText(sleeperLeagueId, "sleeperLeagueId");
            leagueName = requireText(leagueName, "leagueName");
            if (season < 1999 || season > 2100) throw new IllegalArgumentException("season must be between 1999 and 2100");
            if (leagueType < 0 || leagueType > 2) throw new IllegalArgumentException("leagueType must be between 0 and 2");
            if (draftRounds < 0) throw new IllegalArgumentException("draftRounds must not be negative");
            if (rosterCount < 0) throw new IllegalArgumentException("rosterCount must not be negative");
            rosterPositions = List.copyOf(Objects.requireNonNull(rosterPositions, "rosterPositions must not be null"));
            Objects.requireNonNull(state, "state must not be null");
            if (state == CandidateState.ALREADY_PERSISTED) {
                existingButlerLeagueId = requireText(existingButlerLeagueId, "existingButlerLeagueId");
            } else if (existingButlerLeagueId != null) {
                throw new IllegalArgumentException("new candidate cannot carry existing Butler league id");
            }
        }
    }

    public record AcquisitionPlan(
        String policyId,
        String boundary,
        String anchorButlerLeagueId,
        String anchorButlerTeamId,
        String anchorSleeperLeagueId,
        int anchorSleeperRosterId,
        String sleeperOwnerId,
        int targetSeason,
        List<Candidate> candidates) {

        public AcquisitionPlan {
            if (!POLICY_ID.equals(policyId)) throw new IllegalArgumentException("unexpected policyId");
            if (!BOUNDARY.equals(boundary)) throw new IllegalArgumentException("unexpected boundary");
            anchorButlerLeagueId = requireText(anchorButlerLeagueId, "anchorButlerLeagueId");
            anchorButlerTeamId = requireText(anchorButlerTeamId, "anchorButlerTeamId");
            anchorSleeperLeagueId = requireText(anchorSleeperLeagueId, "anchorSleeperLeagueId");
            if (anchorSleeperRosterId <= 0) throw new IllegalArgumentException("anchorSleeperRosterId must be positive");
            sleeperOwnerId = requireText(sleeperOwnerId, "sleeperOwnerId");
            if (targetSeason < 1999 || targetSeason > 2100) {
                throw new IllegalArgumentException("targetSeason must be between 1999 and 2100");
            }
            candidates = List.copyOf(Objects.requireNonNull(candidates, "candidates must not be null"));
            String previous = null;
            for (Candidate candidate : candidates) {
                if (previous != null && previous.compareTo(candidate.sleeperLeagueId()) >= 0) {
                    throw new IllegalArgumentException("candidates must be strictly ordered by Sleeper league id");
                }
                previous = candidate.sleeperLeagueId();
            }
        }
    }
}
