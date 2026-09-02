package io.butler.bet.intelligence;

import io.butler.bet.data.Database;

import java.sql.SQLException;
import java.time.LocalDate;
import java.util.Objects;

/**
 * Presents Butler's existing decision-readiness and player-evidence-readiness dimensions together
 * without combining them into a score or changing either analyzer's semantics.
 */
public final class LeagueEvidenceOverviewAnalyzer {
    private final LeagueDecisionReadinessAnalyzer decisions;
    private final LeaguePlayerEvidenceReadinessAnalyzer playerEvidence;

    public LeagueEvidenceOverviewAnalyzer(Database database) {
        Objects.requireNonNull(database, "database must not be null");
        this.decisions = new LeagueDecisionReadinessAnalyzer(database);
        this.playerEvidence = new LeaguePlayerEvidenceReadinessAnalyzer(database);
    }

    public EvidenceOverviewReport analyze(String leagueId) throws SQLException {
        return new EvidenceOverviewReport(
            decisions.analyze(leagueId),
            playerEvidence.analyze(leagueId));
    }

    public EvidenceOverviewReport analyze(String leagueId, int playerSeason) throws SQLException {
        return new EvidenceOverviewReport(
            decisions.analyze(leagueId),
            playerEvidence.analyze(leagueId, playerSeason));
    }

    public EvidenceOverviewReport analyze(String leagueId, LocalDate minimumValueAsOf,
                                          LocalDate minimumProfileAsOf) throws SQLException {
        return new EvidenceOverviewReport(
            minimumValueAsOf == null ? decisions.analyze(leagueId) : decisions.analyze(leagueId, minimumValueAsOf),
            minimumProfileAsOf == null ? playerEvidence.analyze(leagueId) : playerEvidence.analyze(leagueId, minimumProfileAsOf));
    }

    public EvidenceOverviewReport analyze(String leagueId, int playerSeason,
                                          LocalDate minimumValueAsOf,
                                          LocalDate minimumProfileAsOf) throws SQLException {
        return new EvidenceOverviewReport(
            minimumValueAsOf == null ? decisions.analyze(leagueId) : decisions.analyze(leagueId, minimumValueAsOf),
            minimumProfileAsOf == null
                ? playerEvidence.analyze(leagueId, playerSeason)
                : playerEvidence.analyze(leagueId, playerSeason, minimumProfileAsOf));
    }

    public record EvidenceOverviewReport(
        LeagueDecisionReadinessAnalyzer.DecisionReadinessReport decisionReadiness,
        LeaguePlayerEvidenceReadinessAnalyzer.ReadinessReport playerEvidenceReadiness) {

        public EvidenceOverviewReport {
            Objects.requireNonNull(decisionReadiness, "decisionReadiness must not be null");
            Objects.requireNonNull(playerEvidenceReadiness, "playerEvidenceReadiness must not be null");
            if (!decisionReadiness.health().leagueId().equals(playerEvidenceReadiness.leagueId())) {
                throw new IllegalArgumentException("readiness reports must refer to the same league");
            }
        }

        public String leagueId() { return decisionReadiness.health().leagueId(); }
        public int playerSeason() { return playerEvidenceReadiness.season(); }
        public boolean currentValueDecisionsReady() { return decisionReadiness.currentValueDecisionsReady(); }
        public boolean trendAwareDecisionsReady() { return decisionReadiness.trendAwareDecisionsReady(); }
        public boolean playerEvidenceReady() { return playerEvidenceReadiness.ready(); }
    }
}
