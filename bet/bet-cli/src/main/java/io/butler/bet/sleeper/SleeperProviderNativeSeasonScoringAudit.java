package io.butler.bet.sleeper;

import io.butler.bet.data.Database;
import io.butler.bet.data.LeagueRepository;
import io.butler.bet.data.ProviderPlayerWeekPointsEvidenceRepository;
import io.butler.bet.data.TeamRepository;
import io.butler.bet.data.TeamWeekRosterEvidenceRepository;
import io.butler.bet.domain.ProviderPlayerWeekPointsEvidence;
import io.butler.bet.domain.Team;
import io.butler.bet.domain.TeamWeekRosterEvidence;

import java.math.BigDecimal;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;

/**
 * Read-only BF-566 proof that persisted Sleeper league-scored points form a complete single-source
 * scoring lane for every observed roster team-week in one historical league-season.
 */
public final class SleeperProviderNativeSeasonScoringAudit {
    public static final String POLICY_ID =
        "sleeper-provider-native-season-scoring-audit-v1-exact-roster-identity-parity-read-only";
    public static final String SOURCE = SleeperSeasonProviderPointsEvidenceImporter.SOURCE;
    public static final String SOURCE_SURFACE = SleeperSeasonProviderPointsEvidenceImporter.SOURCE_SURFACE;

    private final Database database;

    public SleeperProviderNativeSeasonScoringAudit(Database database) {
        this.database = Objects.requireNonNull(database, "database must not be null");
    }

    public AuditReport audit(String leagueId, int season) throws SQLException {
        String normalizedLeagueId = requireText(leagueId, "leagueId");
        if (season < 1999 || season > 2100) {
            throw new IllegalArgumentException("season must be between 1999 and 2100");
        }

        var league = new LeagueRepository(database).findById(normalizedLeagueId)
            .orElseThrow(() -> new IllegalArgumentException("League not found: " + normalizedLeagueId));
        var rosterRepository = new TeamWeekRosterEvidenceRepository(database);
        var pointsRepository = new ProviderPlayerWeekPointsEvidenceRepository(database);
        var teamRepository = new TeamRepository(database);

        List<TeamWeekRosterEvidence> rosters = rosterRepository.findLatestByLeagueSeason(
            normalizedLeagueId, season, SOURCE);
        List<ProviderPlayerWeekPointsEvidence> points = pointsRepository.findLatestByLeagueSeason(
            normalizedLeagueId, season, SOURCE);

        List<String> globalBlockers = new ArrayList<>();
        if (rosters.isEmpty()) {
            globalBlockers.add("No persisted Sleeper team-week roster evidence for requested league-season");
        }
        if (points.isEmpty()) {
            globalBlockers.add("No persisted Sleeper provider-points evidence for requested league-season");
        }

        LocalDate providerAsOf = null;
        String providerLeagueId = null;
        if (!points.isEmpty()) {
            providerAsOf = points.getFirst().asOfDate();
            providerLeagueId = points.getFirst().providerLeagueId();
            for (ProviderPlayerWeekPointsEvidence row : points) {
                if (!SOURCE.equals(row.source())) {
                    addOnce(globalBlockers, "Provider-points snapshot contains unexpected source: " + row.source());
                }
                if (!SOURCE_SURFACE.equals(row.sourceSurface())) {
                    addOnce(globalBlockers,
                        "Provider-points snapshot contains unexpected source surface: " + row.sourceSurface());
                }
                if (!providerAsOf.equals(row.asOfDate())) {
                    addOnce(globalBlockers, "Provider-points latest snapshot contains mixed as-of dates");
                }
                if (!providerLeagueId.equals(row.providerLeagueId())) {
                    addOnce(globalBlockers, "Provider-points latest snapshot contains mixed provider league ids");
                }
            }
        }

        Map<TeamWeekKey, TeamWeekRosterEvidence> rosterByKey = new LinkedHashMap<>();
        for (TeamWeekRosterEvidence roster : rosters) {
            TeamWeekKey key = new TeamWeekKey(roster.teamId(), roster.week());
            TeamWeekRosterEvidence existing = rosterByKey.putIfAbsent(key, roster);
            if (existing != null) {
                addOnce(globalBlockers,
                    "Multiple latest roster snapshots resolved for team=" + key.teamId() + " week=" + key.week());
            }
        }

        Map<TeamWeekKey, List<ProviderPlayerWeekPointsEvidence>> pointsByKey = new LinkedHashMap<>();
        for (ProviderPlayerWeekPointsEvidence row : points) {
            TeamWeekKey key = new TeamWeekKey(row.teamId(), row.week());
            pointsByKey.computeIfAbsent(key, ignored -> new ArrayList<>()).add(row);
        }

        Set<TeamWeekKey> keys = new TreeSet<>(Comparator
            .comparing(TeamWeekKey::teamId)
            .thenComparingInt(TeamWeekKey::week));
        keys.addAll(rosterByKey.keySet());
        keys.addAll(pointsByKey.keySet());

        List<TeamWeekAudit> teamWeeks = new ArrayList<>();
        for (TeamWeekKey key : keys) {
            TeamWeekRosterEvidence roster = rosterByKey.get(key);
            List<ProviderPlayerWeekPointsEvidence> rows = pointsByKey.getOrDefault(key, List.of());
            Team team = teamRepository.findById(key.teamId()).orElse(null);
            teamWeeks.add(auditTeamWeek(normalizedLeagueId, season, key, team, roster, rows));
        }

        int readyTeamWeeks = 0;
        int candidateIdentities = 0;
        int scoredIdentities = 0;
        for (TeamWeekAudit teamWeek : teamWeeks) {
            if (teamWeek.state() == TeamWeekState.READY) readyTeamWeeks++;
            candidateIdentities += teamWeek.candidateIdentityCount();
            scoredIdentities += teamWeek.scores().size();
        }

        AuditState state = globalBlockers.isEmpty() && readyTeamWeeks == teamWeeks.size() && !teamWeeks.isEmpty()
            ? AuditState.READY
            : AuditState.BLOCKED;
        return new AuditReport(
            POLICY_ID,
            normalizedLeagueId,
            league.getName(),
            season,
            SOURCE,
            points.isEmpty() ? null : SOURCE_SURFACE,
            providerLeagueId,
            providerAsOf,
            state,
            teamWeeks.size(),
            readyTeamWeeks,
            candidateIdentities,
            scoredIdentities,
            List.copyOf(teamWeeks),
            List.copyOf(globalBlockers));
    }

    private static TeamWeekAudit auditTeamWeek(
        String leagueId,
        int season,
        TeamWeekKey key,
        Team team,
        TeamWeekRosterEvidence roster,
        List<ProviderPlayerWeekPointsEvidence> rows) {
        List<String> blockers = new ArrayList<>();
        String teamName = team == null ? "<missing-team>" : team.getName();
        String expectedProviderRosterId = team == null ? null : team.getExternalId();

        if (team == null) {
            blockers.add("Provider/roster evidence references missing canonical team");
        } else if (!leagueId.equals(team.getLeagueId())) {
            blockers.add("Canonical team belongs to a different league");
        } else if (expectedProviderRosterId == null) {
            blockers.add("Canonical team has no provider roster id");
        }

        if (roster == null) {
            blockers.add("Provider-points team-week has no persisted Sleeper roster evidence");
        } else {
            if (!leagueId.equals(roster.leagueId()) || season != roster.season() || key.week() != roster.week()) {
                blockers.add("Roster evidence provenance does not match requested team-week");
            }
            if (!SOURCE.equals(roster.source())) {
                blockers.add("Roster evidence source is not Sleeper");
            }
        }

        List<String> rosterIds = roster == null ? List.of() : roster.providerPlayerIds();
        Set<String> candidateIds = new LinkedHashSet<>();
        List<String> duplicateRosterIds = new ArrayList<>();
        for (String providerPlayerId : rosterIds) {
            if (!candidateIds.add(providerPlayerId)) duplicateRosterIds.add(providerPlayerId);
        }
        if (!duplicateRosterIds.isEmpty()) {
            blockers.add("Duplicate provider player ids in roster evidence: " + sortedDistinct(duplicateRosterIds));
        }

        Map<String, ProviderPlayerWeekPointsEvidence> rowByPlayer = new LinkedHashMap<>();
        List<String> duplicatePointIds = new ArrayList<>();
        Set<String> providerRosterIds = new LinkedHashSet<>();
        for (ProviderPlayerWeekPointsEvidence row : rows) {
            providerRosterIds.add(row.providerRosterId());
            ProviderPlayerWeekPointsEvidence existing = rowByPlayer.putIfAbsent(row.providerPlayerId(), row);
            if (existing != null) duplicatePointIds.add(row.providerPlayerId());
        }
        if (!duplicatePointIds.isEmpty()) {
            blockers.add("Duplicate provider player ids in provider-points evidence: "
                + sortedDistinct(duplicatePointIds));
        }
        if (providerRosterIds.size() > 1) {
            blockers.add("Provider-points team-week contains mixed provider roster ids: "
                + new TreeSet<>(providerRosterIds));
        }

        String observedProviderRosterId = providerRosterIds.size() == 1
            ? providerRosterIds.iterator().next()
            : null;
        if (expectedProviderRosterId != null && observedProviderRosterId != null
            && !expectedProviderRosterId.equals(observedProviderRosterId)) {
            blockers.add("Provider roster id mismatch: canonical=" + expectedProviderRosterId
                + " evidence=" + observedProviderRosterId);
        }

        Set<String> providerIds = new LinkedHashSet<>(rowByPlayer.keySet());
        List<String> missing = difference(candidateIds, providerIds);
        List<String> extra = difference(providerIds, candidateIds);
        if (!missing.isEmpty()) blockers.add("Missing provider-points identities: " + missing);
        if (!extra.isEmpty()) blockers.add("Extra provider-points identities: " + extra);

        List<PlayerScoreEvidence> scores = new ArrayList<>();
        for (String providerPlayerId : rosterIds) {
            ProviderPlayerWeekPointsEvidence row = rowByPlayer.get(providerPlayerId);
            if (row != null && scores.stream().noneMatch(score -> score.providerPlayerId().equals(providerPlayerId))) {
                scores.add(new PlayerScoreEvidence(providerPlayerId, row.points(), row.id()));
            }
        }

        TeamWeekState state = blockers.isEmpty() ? TeamWeekState.READY : TeamWeekState.BLOCKED;
        return new TeamWeekAudit(
            key.teamId(),
            teamName,
            key.week(),
            roster == null ? null : roster.id(),
            roster == null ? null : roster.asOfDate(),
            expectedProviderRosterId,
            observedProviderRosterId,
            state,
            rosterIds.size(),
            rows.size(),
            List.copyOf(scores),
            missing,
            extra,
            List.copyOf(blockers));
    }

    private static List<String> difference(Set<String> left, Set<String> right) {
        TreeSet<String> result = new TreeSet<>(left);
        result.removeAll(right);
        return List.copyOf(result);
    }

    private static List<String> sortedDistinct(List<String> values) {
        return List.copyOf(new TreeSet<>(values));
    }

    private static void addOnce(List<String> blockers, String blocker) {
        if (!blockers.contains(blocker)) blockers.add(blocker);
    }

    private static String requireText(String value, String field) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(field + " must not be blank");
        return value.trim();
    }

    private record TeamWeekKey(String teamId, int week) {
        private TeamWeekKey {
            teamId = requireText(teamId, "teamId");
            if (week <= 0) throw new IllegalArgumentException("week must be positive");
        }
    }

    public enum AuditState {
        READY,
        BLOCKED
    }

    public enum TeamWeekState {
        READY,
        BLOCKED
    }

    public record PlayerScoreEvidence(
        String providerPlayerId,
        BigDecimal points,
        String evidenceId) {
        public PlayerScoreEvidence {
            providerPlayerId = requireText(providerPlayerId, "providerPlayerId");
            Objects.requireNonNull(points, "points must not be null");
            evidenceId = requireText(evidenceId, "evidenceId");
        }
    }

    public record TeamWeekAudit(
        String teamId,
        String teamName,
        int week,
        String rosterEvidenceId,
        LocalDate rosterAsOf,
        String expectedProviderRosterId,
        String observedProviderRosterId,
        TeamWeekState state,
        int candidateIdentityCount,
        int providerPointRowCount,
        List<PlayerScoreEvidence> scores,
        List<String> missingProviderPlayerIds,
        List<String> extraProviderPlayerIds,
        List<String> blockers) {
        public TeamWeekAudit {
            teamId = requireText(teamId, "teamId");
            teamName = requireText(teamName, "teamName");
            if (week <= 0) throw new IllegalArgumentException("week must be positive");
            if (rosterEvidenceId != null) rosterEvidenceId = requireText(rosterEvidenceId, "rosterEvidenceId");
            if (expectedProviderRosterId != null) {
                expectedProviderRosterId = requireText(expectedProviderRosterId, "expectedProviderRosterId");
            }
            if (observedProviderRosterId != null) {
                observedProviderRosterId = requireText(observedProviderRosterId, "observedProviderRosterId");
            }
            Objects.requireNonNull(state, "state must not be null");
            if (candidateIdentityCount < 0 || providerPointRowCount < 0) {
                throw new IllegalArgumentException("identity counts must not be negative");
            }
            scores = List.copyOf(Objects.requireNonNull(scores, "scores must not be null"));
            missingProviderPlayerIds = List.copyOf(Objects.requireNonNull(
                missingProviderPlayerIds, "missingProviderPlayerIds must not be null"));
            extraProviderPlayerIds = List.copyOf(Objects.requireNonNull(
                extraProviderPlayerIds, "extraProviderPlayerIds must not be null"));
            blockers = List.copyOf(Objects.requireNonNull(blockers, "blockers must not be null"));
            if ((state == TeamWeekState.READY) != blockers.isEmpty()) {
                throw new IllegalArgumentException("READY team-week must have no blockers and BLOCKED must have blockers");
            }
            if (state == TeamWeekState.READY) {
                if (rosterEvidenceId == null || rosterAsOf == null
                    || expectedProviderRosterId == null || observedProviderRosterId == null) {
                    throw new IllegalArgumentException("READY team-week requires complete roster provenance");
                }
                if (candidateIdentityCount != providerPointRowCount || candidateIdentityCount != scores.size()
                    || !missingProviderPlayerIds.isEmpty() || !extraProviderPlayerIds.isEmpty()) {
                    throw new IllegalArgumentException("READY team-week requires exact identity-set parity");
                }
            }
        }
    }

    public record AuditReport(
        String policyId,
        String leagueId,
        String leagueName,
        int season,
        String source,
        String sourceSurface,
        String providerLeagueId,
        LocalDate providerPointsAsOf,
        AuditState state,
        int observedTeamWeeks,
        int readyTeamWeeks,
        int candidateIdentities,
        int scoredIdentities,
        List<TeamWeekAudit> teamWeeks,
        List<String> blockers) {
        public AuditReport {
            if (!POLICY_ID.equals(policyId)) throw new IllegalArgumentException("unexpected policyId");
            leagueId = requireText(leagueId, "leagueId");
            leagueName = requireText(leagueName, "leagueName");
            if (season < 1999 || season > 2100) throw new IllegalArgumentException("invalid season");
            source = requireText(source, "source");
            if (sourceSurface != null) sourceSurface = requireText(sourceSurface, "sourceSurface");
            if (providerLeagueId != null) providerLeagueId = requireText(providerLeagueId, "providerLeagueId");
            Objects.requireNonNull(state, "state must not be null");
            if (observedTeamWeeks < 0 || readyTeamWeeks < 0 || candidateIdentities < 0 || scoredIdentities < 0
                || readyTeamWeeks > observedTeamWeeks || scoredIdentities > candidateIdentities) {
                throw new IllegalArgumentException("audit counts are inconsistent");
            }
            teamWeeks = List.copyOf(Objects.requireNonNull(teamWeeks, "teamWeeks must not be null"));
            blockers = List.copyOf(Objects.requireNonNull(blockers, "blockers must not be null"));
            if (teamWeeks.size() != observedTeamWeeks) {
                throw new IllegalArgumentException("observed team-week count must match report rows");
            }
            if (state == AuditState.READY) {
                if (!blockers.isEmpty() || observedTeamWeeks == 0 || readyTeamWeeks != observedTeamWeeks
                    || sourceSurface == null || providerLeagueId == null || providerPointsAsOf == null
                    || scoredIdentities != candidateIdentities) {
                    throw new IllegalArgumentException("READY audit requires complete provider-native provenance and parity");
                }
            } else if (blockers.isEmpty()
                && teamWeeks.stream().noneMatch(teamWeek -> teamWeek.state() == TeamWeekState.BLOCKED)) {
                throw new IllegalArgumentException("BLOCKED audit requires at least one blocker");
            }
        }

        public boolean ready() {
            return state == AuditState.READY;
        }
    }
}
