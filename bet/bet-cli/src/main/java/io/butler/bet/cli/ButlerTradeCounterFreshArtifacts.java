package io.butler.bet.cli;

import io.butler.bet.data.Database;
import io.butler.bet.intelligence.TradeCounterCandidateSelectionPolicy;
import io.butler.bet.intelligence.TradeCounterMaterializedPackagePolicy;
import io.butler.bet.intelligence.TradeCounterNegotiationMessagePolicy;
import io.butler.bet.intelligence.TradeCounterOpportunityPolicy;
import io.butler.bet.intelligence.TradeCounterProposalEnvelopePolicy;
import io.butler.bet.intelligence.TradeCounterProposalIdentityPolicy;
import io.butler.bet.intelligence.TradeCounterProposalPolicy;
import io.butler.bet.intelligence.TradeCounterStrategicCandidateVettingAnalyzer;
import io.butler.bet.intelligence.TradeCounterStrategicEligibilityPolicy;
import io.butler.bet.intelligence.TradeFlexibleRecommendationContextAnalyzer;
import io.butler.bet.intelligence.TradeTeamPerspectiveRecommendationPolicy;

import java.sql.SQLException;
import java.util.Objects;

/** Package-private fresh counter artifact builder for trusted execution replay. */
final class ButlerTradeCounterFreshArtifacts {
    private ButlerTradeCounterFreshArtifacts() {}

    static Artifacts build(
        Database database,
        ButlerTradeCounterDecisionCli.Options options) throws SQLException {
        Objects.requireNonNull(database, "database must not be null");
        Objects.requireNonNull(options, "options must not be null");

        var recommendationContext = analyzeRecommendation(
            new TradeFlexibleRecommendationContextAnalyzer(database), options);
        var strategic = recommendationContext.trade().strategic();
        String sideATeamId = strategic.sideA().identity().teamId();
        String sideBTeamId = strategic.sideB().identity().teamId();
        var v5 = ButlerTradeRecommendationV5Cli.recommend(
            recommendationContext, options.perspective());

        boolean eligibilityEvaluated = v5.evidenceStatus().complete()
            && v5.action() == TradeTeamPerspectiveRecommendationPolicy.Action.REJECT;
        TradeCounterStrategicEligibilityPolicy.EligibilityReport eligibility;
        if (eligibilityEvaluated) {
            var strategicCandidates = analyzeStrategic(
                new TradeCounterStrategicCandidateVettingAnalyzer(database), options);
            eligibility = TradeCounterStrategicEligibilityPolicy.classify(strategicCandidates);
        } else {
            eligibility = notEvaluatedEligibility(recommendationContext, options.season());
        }

        var opportunity = TradeCounterOpportunityPolicy.classify(
            v5.packageRecommendation(),
            v5.action(),
            options.perspective(),
            v5.evidenceStatus().complete(),
            eligibility);
        var selection = TradeCounterCandidateSelectionPolicy.classify(opportunity, eligibility);
        var proposal = TradeCounterProposalPolicy.classify(opportunity, selection);
        var envelope = TradeCounterProposalEnvelopePolicy.bind(
            proposal, options.perspective(), options.sideA(), options.sideB());
        var materialized = TradeCounterMaterializedPackagePolicy.materialize(envelope);
        var identity = TradeCounterProposalIdentityPolicy.identify(envelope, materialized);
        var message = TradeCounterNegotiationMessagePolicy.compose(envelope);
        return new Artifacts(sideATeamId, sideBTeamId, materialized, identity, message);
    }

    private static TradeFlexibleRecommendationContextAnalyzer.TradeFlexibleRecommendationContextReport analyzeRecommendation(
        TradeFlexibleRecommendationContextAnalyzer analyzer,
        ButlerTradeCounterDecisionCli.Options options) throws SQLException {
        if (options.minimumAsOf() != null) {
            return options.source() == null
                ? analyzer.analyze(options.leagueId(), options.season(), options.sideA(), options.sideB(), options.minimumAsOf())
                : analyzer.analyze(options.leagueId(), options.season(), options.sideA(), options.sideB(), options.source(), options.minimumAsOf());
        }
        return options.source() == null
            ? analyzer.analyze(options.leagueId(), options.season(), options.sideA(), options.sideB())
            : analyzer.analyze(options.leagueId(), options.season(), options.sideA(), options.sideB(), options.source());
    }

    private static TradeCounterStrategicCandidateVettingAnalyzer.StrategicCandidateReport analyzeStrategic(
        TradeCounterStrategicCandidateVettingAnalyzer analyzer,
        ButlerTradeCounterDecisionCli.Options options) throws SQLException {
        if (options.minimumAsOf() != null) {
            return options.source() == null
                ? analyzer.analyze(options.leagueId(), options.season(), options.sideA(), options.sideB(), options.minimumAsOf())
                : analyzer.analyze(options.leagueId(), options.season(), options.sideA(), options.sideB(), options.source(), options.minimumAsOf());
        }
        return options.source() == null
            ? analyzer.analyze(options.leagueId(), options.season(), options.sideA(), options.sideB())
            : analyzer.analyze(options.leagueId(), options.season(), options.sideA(), options.sideB(), options.source());
    }

    private static TradeCounterStrategicEligibilityPolicy.EligibilityReport notEvaluatedEligibility(
        TradeFlexibleRecommendationContextAnalyzer.TradeFlexibleRecommendationContextReport context,
        int season) {
        var trade = context.trade().strategic().trade();
        return new TradeCounterStrategicEligibilityPolicy.EligibilityReport(
            TradeCounterStrategicEligibilityPolicy.POLICY_ID,
            TradeCounterStrategicCandidateVettingAnalyzer.POLICY_ID,
            trade.leagueId(), season, trade.source(), trade.minimumAsOfDate(), false,
            "Strategic eligibility was not evaluated because the v5 action did not require a counter gate.",
            java.util.List.of(), java.util.List.of());
    }

    record Artifacts(
        String sideATeamId,
        String sideBTeamId,
        TradeCounterMaterializedPackagePolicy.MaterializedCounter materialized,
        TradeCounterProposalIdentityPolicy.Identity identity,
        TradeCounterNegotiationMessagePolicy.MessageResult message) {
        Artifacts {
            if (sideATeamId == null || sideATeamId.isBlank()) {
                throw new IllegalArgumentException("sideATeamId must not be blank");
            }
            if (sideBTeamId == null || sideBTeamId.isBlank()) {
                throw new IllegalArgumentException("sideBTeamId must not be blank");
            }
            if (sideATeamId.equals(sideBTeamId)) {
                throw new IllegalArgumentException("trade teams must differ");
            }
            Objects.requireNonNull(materialized, "materialized must not be null");
            Objects.requireNonNull(identity, "identity must not be null");
            Objects.requireNonNull(message, "message must not be null");
        }
    }
}
