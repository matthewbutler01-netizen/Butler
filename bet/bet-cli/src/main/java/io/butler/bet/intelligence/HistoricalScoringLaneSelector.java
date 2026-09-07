package io.butler.bet.intelligence;

import io.butler.bet.data.Database;
import io.butler.bet.data.ProviderPlayerWeekPointsEvidenceRepository;
import io.butler.bet.sleeper.SleeperProviderNativeSeasonScoringAudit;

import java.sql.SQLException;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Selects exactly one historical scoring lane for a league-season.
 *
 * <p>Persisted Sleeper provider points are authoritative only after the complete BF-566
 * league-season audit is READY. If any provider-native evidence exists but that audit is blocked,
 * selection fails closed and never falls back to nflverse. The exact nflverse lane is selected
 * only when no provider-native season evidence exists at all.</p>
 */
public final class HistoricalScoringLaneSelector {
    public static final String POLICY_ID =
        "historical-scoring-lane-selector-v1-provider-native-authoritative-else-exact-nflverse";
    public static final String PROVIDER_NATIVE_SCORING_POLICY_ID =
        "sleeper-provider-native-historical-score-consumption-v1-persisted-exact-no-recalculation";

    private final Database database;

    public HistoricalScoringLaneSelector(Database database) {
        this.database = Objects.requireNonNull(database, "database must not be null");
    }

    public Selection select(String leagueId, int season) throws SQLException {
        String normalizedLeagueId = requireText(leagueId, "leagueId");
        if (season < 1999 || season > 2100) {
            throw new IllegalArgumentException("season must be between 1999 and 2100");
        }

        var persistedProviderPoints = new ProviderPlayerWeekPointsEvidenceRepository(database)
            .findLatestByLeagueSeason(
                normalizedLeagueId,
                season,
                SleeperProviderNativeSeasonScoringAudit.SOURCE);
        if (persistedProviderPoints.isEmpty()) {
            return new Selection(
                POLICY_ID,
                normalizedLeagueId,
                season,
                Lane.NFLVERSE_EXACT,
                SelectionState.READY,
                null,
                null,
                null,
                null,
                List.of());
        }

        SleeperProviderNativeSeasonScoringAudit.AuditReport audit =
            new SleeperProviderNativeSeasonScoringAudit(database).audit(normalizedLeagueId, season);
        List<String> blockers = audit.state() == SleeperProviderNativeSeasonScoringAudit.AuditState.READY
            ? List.of()
            : collectAuditBlockers(audit);
        return new Selection(
            POLICY_ID,
            normalizedLeagueId,
            season,
            Lane.SLEEPER_PROVIDER_NATIVE,
            audit.state() == SleeperProviderNativeSeasonScoringAudit.AuditState.READY
                ? SelectionState.READY
                : SelectionState.BLOCKED,
            audit.sourceSurface(),
            audit.providerLeagueId(),
            audit.providerPointsAsOf(),
            audit,
            blockers);
    }

    private static List<String> collectAuditBlockers(
        SleeperProviderNativeSeasonScoringAudit.AuditReport audit) {
        List<String> blockers = new ArrayList<>();
        for (String blocker : audit.blockers()) {
            blockers.add("Provider-native season audit: " + blocker);
        }
        for (var teamWeek : audit.teamWeeks()) {
            if (teamWeek.state() != SleeperProviderNativeSeasonScoringAudit.TeamWeekState.BLOCKED) continue;
            for (String blocker : teamWeek.blockers()) {
                blockers.add("Provider-native season audit team=" + teamWeek.teamId()
                    + " week=" + teamWeek.week() + ": " + blocker);
            }
        }
        if (blockers.isEmpty()) {
            blockers.add("Provider-native season audit is BLOCKED without a reported blocker");
        }
        return List.copyOf(blockers);
    }

    private static String requireText(String value, String field) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(field + " must not be blank");
        return value.trim();
    }

    public enum Lane {
        SLEEPER_PROVIDER_NATIVE,
        NFLVERSE_EXACT
    }

    public enum SelectionState {
        READY,
        BLOCKED
    }

    public record Selection(
        String policyId,
        String leagueId,
        int season,
        Lane lane,
        SelectionState state,
        String providerSourceSurface,
        String providerLeagueId,
        LocalDate providerPointsAsOf,
        SleeperProviderNativeSeasonScoringAudit.AuditReport providerAudit,
        List<String> blockers) {
        public Selection {
            if (!POLICY_ID.equals(policyId)) throw new IllegalArgumentException("unexpected policyId");
            leagueId = requireText(leagueId, "leagueId");
            if (season < 1999 || season > 2100) throw new IllegalArgumentException("invalid season");
            Objects.requireNonNull(lane, "lane must not be null");
            Objects.requireNonNull(state, "state must not be null");
            blockers = List.copyOf(Objects.requireNonNull(blockers, "blockers must not be null"));

            if (lane == Lane.NFLVERSE_EXACT) {
                if (state != SelectionState.READY || providerSourceSurface != null
                    || providerLeagueId != null || providerPointsAsOf != null || providerAudit != null
                    || !blockers.isEmpty()) {
                    throw new IllegalArgumentException(
                        "nflverse selection requires absent provider evidence and no selector blockers");
                }
            } else {
                providerSourceSurface = requireText(providerSourceSurface, "providerSourceSurface");
                providerLeagueId = requireText(providerLeagueId, "providerLeagueId");
                Objects.requireNonNull(providerPointsAsOf, "providerPointsAsOf must not be null");
                Objects.requireNonNull(providerAudit, "providerAudit must not be null");
                if (!leagueId.equals(providerAudit.leagueId()) || season != providerAudit.season()) {
                    throw new IllegalArgumentException("provider audit identity must match selection");
                }
                if (state == SelectionState.READY) {
                    if (providerAudit.state() != SleeperProviderNativeSeasonScoringAudit.AuditState.READY
                        || !blockers.isEmpty()) {
                        throw new IllegalArgumentException("READY provider selection requires READY audit and no blockers");
                    }
                } else if (providerAudit.state() != SleeperProviderNativeSeasonScoringAudit.AuditState.BLOCKED
                    || blockers.isEmpty()) {
                    throw new IllegalArgumentException("BLOCKED provider selection requires audit blockers");
                }
            }
        }

        public boolean ready() {
            return state == SelectionState.READY;
        }

        public SleeperProviderNativeSeasonScoringAudit.TeamWeekAudit providerTeamWeek(
            String teamId, int week) {
            String normalizedTeamId = requireText(teamId, "teamId");
            if (week <= 0) throw new IllegalArgumentException("week must be positive");
            if (providerAudit == null) return null;
            return providerAudit.teamWeeks().stream()
                .filter(value -> normalizedTeamId.equals(value.teamId()) && week == value.week())
                .findFirst()
                .orElse(null);
        }

        public String scoringPolicyId() {
            return lane == Lane.SLEEPER_PROVIDER_NATIVE
                ? PROVIDER_NATIVE_SCORING_POLICY_ID
                : CoveredProductionScoringPolicy.POLICY_ID;
        }
    }
}
