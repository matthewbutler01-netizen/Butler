package io.butler.bet.intelligence;

import io.butler.bet.data.Database;

import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Carries the governed publication-validation envelope into league/player aging evidence.
 * No score, strategic label, dynasty adjustment, or recommendation is created here.
 */
public final class LeagueValidatedAgingModelEvidenceAnalyzer {
    private final LeagueAgingModelEvidenceAnalyzer leagueEvidence;
    private final AgingModelPublicationValidationAnalyzer validation;

    public LeagueValidatedAgingModelEvidenceAnalyzer(Database database) {
        Objects.requireNonNull(database, "database must not be null");
        this.leagueEvidence = new LeagueAgingModelEvidenceAnalyzer(database);
        this.validation = new AgingModelPublicationValidationAnalyzer(database);
    }

    public ValidatedLeagueReport analyze(String leagueId, int season) throws SQLException {
        return compose(leagueEvidence.analyze(leagueId, season), validation.analyze());
    }

    static ValidatedLeagueReport compose(LeagueAgingModelEvidenceAnalyzer.LeagueAgingModelEvidenceReport league,
                                         AgingModelPublicationValidationAnalyzer.ValidationReport validation) {
        Objects.requireNonNull(league, "league must not be null");
        Objects.requireNonNull(validation, "validation must not be null");
        if (!league.supportPolicyId().equals(validation.supportPolicyId())) {
            throw new IllegalStateException("league evidence and validation use different support policies");
        }
        if (!league.modelProfileSource().equals(validation.profileSource())
            || !league.modelProductionSource().equals(validation.productionSource())) {
            throw new IllegalStateException("league evidence and validation use different model sources");
        }

        Map<CellKey, AgingModelPublicationValidationAnalyzer.ValidatedCell> validationByCell = new HashMap<>();
        for (var cell : validation.cells()) {
            validationByCell.put(new CellKey(cell.cell().position(), cell.cell().metric(), cell.cell().age()), cell);
        }

        List<ValidatedTeamEvidence> teams = new ArrayList<>();
        for (var team : league.teams()) {
            List<ValidatedPlayerEvidence> players = new ArrayList<>();
            for (var player : team.players()) {
                List<ValidatedMetricEvidence> metrics = new ArrayList<>();
                if (player.evidence() != null) {
                    for (var metric : player.evidence().metrics()) {
                        AgingModelPublicationValidationAnalyzer.ValidatedCell cell = null;
                        if (metric.available()) {
                            cell = validationByCell.get(new CellKey(player.position(), metric.metric(), player.modelAge()));
                            if (cell == null) {
                                throw new IllegalStateException("published league metric missing validation envelope: "
                                    + player.position() + " " + metric.metric() + " age=" + player.modelAge());
                            }
                        }
                        metrics.add(new ValidatedMetricEvidence(metric, cell));
                    }
                }
                players.add(new ValidatedPlayerEvidence(player, List.copyOf(metrics)));
            }
            players.sort(Comparator.comparing((ValidatedPlayerEvidence value) -> value.player().position())
                .thenComparing(value -> value.player().playerName(), String.CASE_INSENSITIVE_ORDER)
                .thenComparing(value -> value.player().playerId()));
            teams.add(new ValidatedTeamEvidence(team.teamId(), team.teamName(), List.copyOf(players)));
        }
        teams.sort(Comparator.comparing(ValidatedTeamEvidence::teamName, String.CASE_INSENSITIVE_ORDER)
            .thenComparing(ValidatedTeamEvidence::teamId));

        return new ValidatedLeagueReport(
            league.leagueId(), league.season(), league.modelAgeAsOf(), league.leagueProfileSource(),
            league.supportPolicyId(), league.minimumDistinctSeasonTransitions(),
            league.modelProfileSource(), league.modelProductionSource(),
            validation.publishedCells(), validation.validationCompleteCells(), List.copyOf(teams));
    }

    private record CellKey(String position, AgingModelSampleAuditAnalyzer.Metric metric, int age) {}

    public record ValidatedMetricEvidence(
        AgingModelPositionAgeEvidenceAnalyzer.MetricEvidence metric,
        AgingModelPublicationValidationAnalyzer.ValidatedCell validation) {
        public ValidatedMetricEvidence {
            Objects.requireNonNull(metric, "metric must not be null");
            if (metric.available() != (validation != null)) {
                throw new IllegalArgumentException("published metric availability must match validation presence");
            }
        }

        public boolean available() { return validation != null && validation.validationComplete(); }
    }

    public record ValidatedPlayerEvidence(LeagueAgingModelEvidenceAnalyzer.PlayerAgingModelEvidence player,
                                          List<ValidatedMetricEvidence> metrics) {
        public ValidatedPlayerEvidence {
            Objects.requireNonNull(player, "player must not be null");
            metrics = List.copyOf(Objects.requireNonNull(metrics, "metrics must not be null"));
        }
        public boolean validationComplete() {
            return metrics.stream().filter(value -> value.metric().available()).allMatch(ValidatedMetricEvidence::available);
        }
    }

    public record ValidatedTeamEvidence(String teamId, String teamName, List<ValidatedPlayerEvidence> players) {
        public ValidatedTeamEvidence {
            Objects.requireNonNull(teamId, "teamId must not be null");
            Objects.requireNonNull(teamName, "teamName must not be null");
            players = List.copyOf(Objects.requireNonNull(players, "players must not be null"));
        }
    }

    public record ValidatedLeagueReport(String leagueId,
                                        int season,
                                        java.time.LocalDate modelAgeAsOf,
                                        String leagueProfileSource,
                                        String supportPolicyId,
                                        int minimumDistinctSeasonTransitions,
                                        String modelProfileSource,
                                        String modelProductionSource,
                                        int publishedModelCells,
                                        int validationCompleteModelCells,
                                        List<ValidatedTeamEvidence> teams) {
        public ValidatedLeagueReport {
            Objects.requireNonNull(leagueId, "leagueId must not be null");
            Objects.requireNonNull(modelAgeAsOf, "modelAgeAsOf must not be null");
            Objects.requireNonNull(leagueProfileSource, "leagueProfileSource must not be null");
            Objects.requireNonNull(supportPolicyId, "supportPolicyId must not be null");
            Objects.requireNonNull(modelProfileSource, "modelProfileSource must not be null");
            Objects.requireNonNull(modelProductionSource, "modelProductionSource must not be null");
            teams = List.copyOf(Objects.requireNonNull(teams, "teams must not be null"));
        }
        public boolean allPublishedModelCellsValidationComplete() {
            return publishedModelCells > 0 && publishedModelCells == validationCompleteModelCells;
        }
        public int totalPlayers() { return teams.stream().mapToInt(team -> team.players().size()).sum(); }
    }
}
