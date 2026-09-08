package io.butler.bet.sleeper;

import io.butler.bet.data.Database;
import io.butler.bet.data.PlayerRepository;
import io.butler.bet.domain.Player;

import java.io.IOException;
import java.sql.SQLException;
import java.util.Objects;

/** BF-630 compact read-only presentation of the latest governed waiver decision and BF-629 actionability. */
public final class SleeperLiveWaiverLatestGovernedDecisionSummary {
    public static final String POLICY_ID =
        "sleeper-live-waiver-latest-governed-decision-summary-v1-bf623-bf628-bf629-read-only";

    private final RevalidationSource revalidationSource;
    private final PlayerLookup playerLookup;

    public SleeperLiveWaiverLatestGovernedDecisionSummary(Database database) {
        Objects.requireNonNull(database, "database must not be null");
        PlayerRepository players = new PlayerRepository(database);
        this.revalidationSource = target ->
            new SleeperLiveWaiverRecommendationActionabilityRevalidation(database).revalidate(target);
        this.playerLookup = sleeperId -> players.findByExternalId(sleeperId)
            .map(SleeperLiveWaiverLatestGovernedDecisionSummary::display)
            .orElse(null);
    }

    SleeperLiveWaiverLatestGovernedDecisionSummary(
        RevalidationSource revalidationSource,
        PlayerLookup playerLookup) {
        this.revalidationSource = Objects.requireNonNull(revalidationSource, "revalidationSource must not be null");
        this.playerLookup = Objects.requireNonNull(playerLookup, "playerLookup must not be null");
    }

    public SummaryReport summarize(SleeperPersonalizedTargetService.VerifiedTarget target)
        throws SQLException, IOException, InterruptedException {
        validateVerifiedTarget(target);
        var revalidation = revalidationSource.revalidate(target);
        validateRevalidation(target, revalidation);

        if (revalidation.state()
            == SleeperLiveWaiverRecommendationActionabilityRevalidation.ActionabilityState.NO_AUDITED_DECISION) {
            return report(target, revalidation, SummaryState.NO_AUDITED_DECISION, null, null);
        }
        if (revalidation.state()
            == SleeperLiveWaiverRecommendationActionabilityRevalidation.ActionabilityState.NO_TRANSACTION_TO_REVALIDATE) {
            return report(target, revalidation, SummaryState.NO_TRANSACTION_TO_ACT_ON, null, null);
        }

        String addId = requireText(revalidation.addSleeperPlayerId(), "addSleeperPlayerId");
        String dropId = requireText(revalidation.dropSleeperPlayerId(), "dropSleeperPlayerId");
        PlayerDisplay add = requirePlayer(addId, "add");
        PlayerDisplay drop = requirePlayer(dropId, "drop");

        SummaryState state = revalidation.state()
            == SleeperLiveWaiverRecommendationActionabilityRevalidation.ActionabilityState.LIVE_ACTIONABLE_VERIFIED
            ? SummaryState.CURRENT_AND_ACTIONABLE
            : SummaryState.STALE_DO_NOT_ACT;
        return report(target, revalidation, state, add, drop);
    }

    private PlayerDisplay requirePlayer(String sleeperId, String role) throws SQLException {
        PlayerDisplay player = playerLookup.find(sleeperId);
        if (player == null) {
            throw new IllegalStateException("BF-630 BLOCKED: exact persisted Butler player is missing for "
                + role + " Sleeper id " + sleeperId);
        }
        if (!sleeperId.equals(player.sleeperPlayerId())) {
            throw new IllegalStateException("BF-630 BLOCKED: exact persisted Butler player identity mismatch for "
                + role + " Sleeper id " + sleeperId);
        }
        return player;
    }

    private static SummaryReport report(
        SleeperPersonalizedTargetService.VerifiedTarget target,
        SleeperLiveWaiverRecommendationActionabilityRevalidation.RevalidationReport revalidation,
        SummaryState state,
        PlayerDisplay add,
        PlayerDisplay drop) {
        return new SummaryReport(
            POLICY_ID,
            target.butlerLeagueId(),
            target.sleeperUserId(),
            target.sleeperLeagueId(),
            target.leagueName(),
            target.rosterId(),
            target.teamName(),
            revalidation.auditId(),
            revalidation.capturedAtUtc(),
            revalidation.recommendationState(),
            add,
            drop,
            revalidation.state(),
            state);
    }

    private static void validateVerifiedTarget(SleeperPersonalizedTargetService.VerifiedTarget target) {
        Objects.requireNonNull(target, "target must not be null");
        if (!SleeperPersonalizedTargetService.BF623_POLICY_ID.equals(target.policyId())
            || target.state() != SleeperPersonalizedTargetService.VerificationState.BOUND_TARGET_LIVE_VERIFIED) {
            throw new IllegalStateException("BF-630 BLOCKED: target is not BF-623 live verified");
        }
    }

    private static void validateRevalidation(
        SleeperPersonalizedTargetService.VerifiedTarget target,
        SleeperLiveWaiverRecommendationActionabilityRevalidation.RevalidationReport report) {
        Objects.requireNonNull(report, "BF-629 report must not be null");
        if (!SleeperLiveWaiverRecommendationActionabilityRevalidation.POLICY_ID.equals(report.policyId())
            || !target.butlerLeagueId().equals(report.leagueId())
            || !target.sleeperUserId().equals(report.sleeperOwnerId())
            || !target.sleeperLeagueId().equals(report.sleeperLeagueId())
            || target.rosterId() != report.rosterId()) {
            throw new IllegalStateException("BF-630 BLOCKED: BF-629 actionability does not reconcile to BF-623 target");
        }
    }

    private static PlayerDisplay display(Player player) {
        return new PlayerDisplay(
            player.getExternalId(),
            player.getDisplayName(),
            player.getPosition(),
            player.getNflTeam());
    }

    private static String requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalStateException("BF-630 BLOCKED: " + field + " is blank");
        }
        return value.trim();
    }

    @FunctionalInterface
    interface RevalidationSource {
        SleeperLiveWaiverRecommendationActionabilityRevalidation.RevalidationReport revalidate(
            SleeperPersonalizedTargetService.VerifiedTarget target)
            throws SQLException, IOException, InterruptedException;
    }

    @FunctionalInterface
    interface PlayerLookup {
        PlayerDisplay find(String sleeperPlayerId) throws SQLException;
    }

    public enum SummaryState {
        NO_AUDITED_DECISION,
        NO_TRANSACTION_TO_ACT_ON,
        CURRENT_AND_ACTIONABLE,
        STALE_DO_NOT_ACT
    }

    public record PlayerDisplay(
        String sleeperPlayerId,
        String displayName,
        String position,
        String nflTeam) {
        public PlayerDisplay {
            sleeperPlayerId = requireText(sleeperPlayerId, "sleeperPlayerId");
            displayName = requireText(displayName, "displayName");
            position = requireText(position, "position");
            nflTeam = nflTeam == null || nflTeam.isBlank() ? null : nflTeam.trim();
        }
    }

    public record SummaryReport(
        String policyId,
        String leagueId,
        String sleeperOwnerId,
        String sleeperLeagueId,
        String leagueName,
        int rosterId,
        String teamName,
        String auditId,
        String capturedAtUtc,
        String recommendationState,
        PlayerDisplay addPlayer,
        PlayerDisplay dropPlayer,
        SleeperLiveWaiverRecommendationActionabilityRevalidation.ActionabilityState bf629State,
        SummaryState state) {
        public SummaryReport {
            if (!POLICY_ID.equals(policyId)) throw new IllegalArgumentException("unexpected BF-630 policyId");
            Objects.requireNonNull(bf629State, "bf629State must not be null");
            Objects.requireNonNull(state, "state must not be null");
        }
    }
}
