package io.butler.bet.intelligence;

import io.butler.bet.data.Database;

import java.sql.SQLException;
import java.util.List;
import java.util.Objects;

/** Applies the governed support policy at the aging-model publication boundary. */
public final class AgingModelPublishedSmootherAnalyzer {
    private final AgingModelLocalSmootherAnalyzer smoother;

    public AgingModelPublishedSmootherAnalyzer(Database database) {
        this.smoother = new AgingModelLocalSmootherAnalyzer(
            Objects.requireNonNull(database, "database must not be null"));
    }

    public PublishedSmootherReport analyze() throws SQLException {
        return applyPolicy(smoother.analyze());
    }

    static PublishedSmootherReport applyPolicy(AgingModelLocalSmootherAnalyzer.LocalSmootherReport report) {
        Objects.requireNonNull(report, "report must not be null");
        List<AgingModelLocalSmootherAnalyzer.SmoothedCell> published = report.cells().stream()
            .filter(cell -> AgingModelSupportPolicy.isPublicationEligible(cell.distinctSeasonTransitions()))
            .toList();
        int excluded = report.cells().size() - published.size();
        return new PublishedSmootherReport(
            report.profileSource(),
            report.productionSource(),
            AgingModelSupportPolicy.POLICY_ID,
            AgingModelSupportPolicy.MINIMUM_DISTINCT_SEASON_TRANSITIONS,
            report.cells().size(),
            excluded,
            published);
    }

    public record PublishedSmootherReport(String profileSource,
                                          String productionSource,
                                          String supportPolicyId,
                                          int minimumDistinctSeasonTransitions,
                                          int diagnosticCells,
                                          int excludedCells,
                                          List<AgingModelLocalSmootherAnalyzer.SmoothedCell> cells) {
        public PublishedSmootherReport {
            Objects.requireNonNull(profileSource, "profileSource must not be null");
            Objects.requireNonNull(productionSource, "productionSource must not be null");
            Objects.requireNonNull(supportPolicyId, "supportPolicyId must not be null");
            cells = List.copyOf(Objects.requireNonNull(cells, "cells must not be null"));
        }

        public int publishedCells() {
            return cells.size();
        }
    }
}
