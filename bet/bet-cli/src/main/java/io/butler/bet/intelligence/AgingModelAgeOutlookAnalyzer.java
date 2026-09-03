package io.butler.bet.intelligence;

import io.butler.bet.data.Database;

import java.sql.SQLException;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;

/** Applies the governed per-metric age-outlook policy only to validation-complete published cells. */
public final class AgingModelAgeOutlookAnalyzer {
    private final AgingModelPublicationValidationAnalyzer validation;

    public AgingModelAgeOutlookAnalyzer(Database database) {
        this.validation = new AgingModelPublicationValidationAnalyzer(
            Objects.requireNonNull(database, "database must not be null"));
    }

    public AgeOutlookReport analyze() throws SQLException {
        return apply(validation.analyze());
    }

    static AgeOutlookReport apply(AgingModelPublicationValidationAnalyzer.ValidationReport validationReport) {
        Objects.requireNonNull(validationReport, "validationReport must not be null");

        List<OutlookCell> cells = validationReport.cells().stream()
            .map(AgingModelAgeOutlookAnalyzer::toOutlook)
            .sorted(Comparator.comparing((OutlookCell value) -> value.validation().cell().position())
                .thenComparing(value -> value.validation().cell().metric().name())
                .thenComparingInt(value -> value.validation().cell().age()))
            .toList();

        return new AgeOutlookReport(
            validationReport.profileSource(),
            validationReport.productionSource(),
            validationReport.supportPolicyId(),
            AgingModelAgeOutlookPolicy.POLICY_ID,
            cells.size(),
            (int) cells.stream().filter(OutlookCell::outlookAvailable).count(),
            List.copyOf(cells));
    }

    private static OutlookCell toOutlook(AgingModelPublicationValidationAnalyzer.ValidatedCell validation) {
        if (!validation.validationComplete()) {
            return new OutlookCell(validation, null, null);
        }
        var metric = validation.cell().metric();
        return new OutlookCell(
            validation,
            AgingModelAgeOutlookPolicy.direction(metric),
            AgingModelAgeOutlookPolicy.classify(metric, validation.deltaP25(), validation.deltaP75()));
    }

    public record OutlookCell(AgingModelPublicationValidationAnalyzer.ValidatedCell validation,
                              AgingModelAgeOutlookPolicy.Direction direction,
                              AgingModelAgeOutlookPolicy.MetricOutlook outlook) {
        public OutlookCell {
            Objects.requireNonNull(validation, "validation must not be null");
            boolean available = direction != null && outlook != null;
            if (available != validation.validationComplete()) {
                throw new IllegalArgumentException("outlook availability must match validation completeness");
            }
        }

        public boolean outlookAvailable() { return outlook != null; }
    }

    public record AgeOutlookReport(String profileSource,
                                   String productionSource,
                                   String supportPolicyId,
                                   String outlookPolicyId,
                                   int publishedCells,
                                   int outlookAvailableCells,
                                   List<OutlookCell> cells) {
        public AgeOutlookReport {
            Objects.requireNonNull(profileSource, "profileSource must not be null");
            Objects.requireNonNull(productionSource, "productionSource must not be null");
            Objects.requireNonNull(supportPolicyId, "supportPolicyId must not be null");
            Objects.requireNonNull(outlookPolicyId, "outlookPolicyId must not be null");
            cells = List.copyOf(Objects.requireNonNull(cells, "cells must not be null"));
            if (outlookAvailableCells < 0 || outlookAvailableCells > publishedCells) {
                throw new IllegalArgumentException("outlook-available count must be within published count");
            }
        }

        public int outlookUnavailableCells() { return publishedCells - outlookAvailableCells; }
        public int favorableCells() {
            return count(AgingModelAgeOutlookPolicy.MetricOutlook.FAVORABLE);
        }
        public int neutralOrMixedCells() {
            return count(AgingModelAgeOutlookPolicy.MetricOutlook.NEUTRAL_OR_MIXED);
        }
        public int unfavorableCells() {
            return count(AgingModelAgeOutlookPolicy.MetricOutlook.UNFAVORABLE);
        }
        private int count(AgingModelAgeOutlookPolicy.MetricOutlook target) {
            return (int) cells.stream().filter(cell -> cell.outlook() == target).count();
        }
    }
}
