package io.butler.bet.sleeper;

import io.butler.bet.data.Database;
import io.butler.bet.data.LeagueRepository;
import io.butler.bet.domain.League;

import java.io.IOException;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;

/**
 * Read-only corpus discovery across every Sleeper owner represented in an anchor league.
 * Candidate discovery is deterministic and intentionally blind to lineup outcomes and readiness impact.
 */
public final class SleeperCohortCorpusAcquisitionPlanner {
    public static final String POLICY_ID =
        "sleeper-cohort-corpus-acquisition-v1-all-anchor-owners-all-target-season-leagues-dedup-provider-id-order-no-outcome-selection";
    public static final String BOUNDARY =
        "DISCOVERY_ONLY_NO_IMPORT_NO_OUTCOME_BASED_SELECTION_NO_READINESS_TARGETING_NO_THRESHOLD_FITTING_NO_MANAGER_ATTRIBUTION";

    private final SleeperGateway gateway;
    private final LeagueRepository leagues;

    public SleeperCohortCorpusAcquisitionPlanner(Database database) {
        this(new SleeperApiGateway(), database);
    }

    SleeperCohortCorpusAcquisitionPlanner(SleeperGateway gateway, Database database) {
        Objects.requireNonNull(database, "database must not be null");
        this.gateway = Objects.requireNonNull(gateway, "gateway must not be null");
        this.leagues = new LeagueRepository(database);
    }

    public AcquisitionPlan plan(String anchorButlerLeagueId, int targetSeason)
        throws IOException, InterruptedException, SQLException {
        String leagueId = requireText(anchorButlerLeagueId, "anchorButlerLeagueId");
        if (targetSeason < 1999 || targetSeason > 2100) {
            throw new IllegalArgumentException("targetSeason must be between 1999 and 2100");
        }

        League anchorLeague = leagues.findById(leagueId)
            .orElseThrow(() -> new IllegalArgumentException("anchor league not found: " + leagueId));
        String anchorSleeperLeagueId = requireText(anchorLeague.getExternalId(), "anchor Sleeper league id");
        List<SleeperJsonParser.SleeperRoster> anchorRosters = gateway.fetchRosters(anchorSleeperLeagueId);
        if (anchorRosters == null) {
            throw new IllegalStateException("Sleeper anchor roster response must not be null");
        }

        Set<String> ownerIds = new TreeSet<>();
        int ownerlessRosters = 0;
        for (SleeperJsonParser.SleeperRoster roster : anchorRosters) {
            if (roster == null) throw new IllegalStateException("Sleeper anchor roster response contains null roster");
            if (roster.ownerId() == null || roster.ownerId().isBlank()) {
                ownerlessRosters++;
                continue;
            }
            ownerIds.add(roster.ownerId().trim());
        }
        if (ownerIds.isEmpty()) {
            throw new IllegalStateException("anchor Sleeper league has no roster owner identities");
        }

        Map<String, Accumulator> byLeagueId = new LinkedHashMap<>();
        for (String ownerId : ownerIds) {
            List<SleeperJsonParser.SleeperLeague> ownerLeagues = gateway.fetchUserLeagues(ownerId, targetSeason);
            if (ownerLeagues == null) {
                throw new IllegalStateException("Sleeper user-league response must not be null for owner " + ownerId);
            }
            List<SleeperJsonParser.SleeperLeague> ordered = new ArrayList<>(ownerLeagues);
            ordered.sort(Comparator.comparing(SleeperJsonParser.SleeperLeague::id));
            for (SleeperJsonParser.SleeperLeague candidate : ordered) {
                if (candidate == null) {
                    throw new IllegalStateException("Sleeper user-league response contains null league for owner " + ownerId);
                }
                String candidateId = requireText(candidate.id(), "candidate Sleeper league id");
                if (candidate.season() != targetSeason) {
                    throw new IllegalStateException(
                        "Sleeper user-league season mismatch for " + candidateId
                            + ": requested=" + targetSeason + " returned=" + candidate.season());
                }
                Accumulator accumulator = byLeagueId.get(candidateId);
                if (accumulator == null) {
                    accumulator = new Accumulator(candidate);
                    byLeagueId.put(candidateId, accumulator);
                } else {
                    accumulator.validateSameLeague(candidate);
                }
                accumulator.ownerIds.add(ownerId);
            }
        }

        List<String> candidateIds = new ArrayList<>(byLeagueId.keySet());
        candidateIds.sort(String::compareTo);
        List<Candidate> candidates = new ArrayList<>();
        for (String candidateId : candidateIds) {
            Accumulator accumulator = byLeagueId.get(candidateId);
            List<SleeperJsonParser.SleeperRoster> candidateRosters = gateway.fetchRosters(candidateId);
            if (candidateRosters == null) {
                throw new IllegalStateException("Sleeper roster response must not be null for " + candidateId);
            }
            String existingButlerLeagueId = leagues.findByExternalId(candidateId)
                .map(League::getId).orElse(null);
            SleeperJsonParser.SleeperLeague candidate = accumulator.league;
            candidates.add(new Candidate(
                candidateId,
                requireText(candidate.name(), "candidate league name"),
                candidate.season(),
                candidate.leagueType(),
                candidate.draftRounds(),
                candidateRosters.size(),
                candidate.rosterPositions(),
                List.copyOf(accumulator.ownerIds),
                existingButlerLeagueId == null
                    ? CandidateState.DISCOVERED_NOT_PERSISTED
                    : CandidateState.ALREADY_PERSISTED,
                existingButlerLeagueId));
        }

        return new AcquisitionPlan(
            POLICY_ID,
            BOUNDARY,
            leagueId,
            anchorSleeperLeagueId,
            anchorRosters.size(),
            ownerIds.size(),
            ownerlessRosters,
            List.copyOf(ownerIds),
            targetSeason,
            List.copyOf(candidates));
    }

    private static String requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
        return value.trim();
    }

    private static final class Accumulator {
        private final SleeperJsonParser.SleeperLeague league;
        private final Set<String> ownerIds = new TreeSet<>();

        private Accumulator(SleeperJsonParser.SleeperLeague league) {
            this.league = Objects.requireNonNull(league, "league must not be null");
        }

        private void validateSameLeague(SleeperJsonParser.SleeperLeague other) {
            if (!league.id().equals(other.id())
                || league.season() != other.season()
                || league.leagueType() != other.leagueType()
                || league.draftRounds() != other.draftRounds()
                || !league.rosterPositions().equals(other.rosterPositions())) {
                throw new IllegalStateException("conflicting Sleeper metadata for candidate league " + league.id());
            }
        }
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
        List<String> exposingOwnerIds,
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
            exposingOwnerIds = List.copyOf(Objects.requireNonNull(exposingOwnerIds, "exposingOwnerIds must not be null"));
            if (exposingOwnerIds.isEmpty()) throw new IllegalArgumentException("exposingOwnerIds must not be empty");
            String previous = null;
            for (String ownerId : exposingOwnerIds) {
                String current = requireText(ownerId, "exposing owner id");
                if (previous != null && previous.compareTo(current) >= 0) {
                    throw new IllegalArgumentException("exposingOwnerIds must be strictly ordered");
                }
                previous = current;
            }
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
        String anchorSleeperLeagueId,
        int anchorRosterCount,
        int anchorOwnerCount,
        int ownerlessAnchorRosters,
        List<String> anchorOwnerIds,
        int targetSeason,
        List<Candidate> candidates) {

        public AcquisitionPlan {
            if (!POLICY_ID.equals(policyId)) throw new IllegalArgumentException("unexpected policyId");
            if (!BOUNDARY.equals(boundary)) throw new IllegalArgumentException("unexpected boundary");
            anchorButlerLeagueId = requireText(anchorButlerLeagueId, "anchorButlerLeagueId");
            anchorSleeperLeagueId = requireText(anchorSleeperLeagueId, "anchorSleeperLeagueId");
            if (anchorRosterCount < 0 || anchorOwnerCount <= 0 || ownerlessAnchorRosters < 0) {
                throw new IllegalArgumentException("invalid anchor cohort counts");
            }
            anchorOwnerIds = List.copyOf(Objects.requireNonNull(anchorOwnerIds, "anchorOwnerIds must not be null"));
            if (anchorOwnerIds.size() != anchorOwnerCount) {
                throw new IllegalArgumentException("anchorOwnerIds size must equal anchorOwnerCount");
            }
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
