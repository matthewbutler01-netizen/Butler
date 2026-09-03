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
 * Carries validation-complete per-metric age outlooks into league/player evidence.
 * No cross-metric aggregation, player grade, dynasty adjustment, or recommendation is produced.
 */
public final class LeagueAgeOutlookEvidenceAnalyzer {
    private final LeagueValidatedAgingModelEvidenceAnalyzer leagueEvidence;
    private final AgingModelAgeOutlookAnalyzer outlook;

    public LeagueAgeOutlookEvidenceAnalyzer(Database database) {
        Objects.requireNonNull(database, "database must not be null");
        this.leagueEvidence = new LeagueValidatedAgingModelEvidenceAnalyzer(database);
        this.outlook = new AgingModelAgeOutlookAnalyzer(database);
    }

    public LeagueAgeOutlookReport analyze(String leagueId, int season) throws SQLException {
        return compose(leagueEvidence.analyze(leagueId, season), outlook.analyze());
    }

    static LeagueAgeOutlookReport compose(
        LeagueValidatedAgingModelEvidenceAnalyzer.ValidatedLeagueReport league,
        AgingModelAgeOutlookAnalyzer.AgeOutlookReport outlook) {
        Objects.requireNonNull(league, "league must not be null");
        Objects.requireNonNull(outlook, "outlook must not be null");

        if (!league.supportPolicyId().equals(outlook.supportPolicyId())) {
            throw new IllegalStateException("league evidence and outlook use different support policies");
        }
        if (!league.modelProfileSource().equals(outlook.profileSource())
            || !league.modelProductionSource().equals(outlook.productionSource())) {
            throw new IllegalStateException("league evidence and outlook use different model sources");
        }
        if (league.publishedModelCells() != outlook.publishedCells()) {
            throw new IllegalStateException("league validation and outlook reference different published model cell counts");
        }

        Map<CellKey, AgingModelAgeOutlookAnalyzer.OutlookCell> outlookByCell = new HashMap<>();
        for (var cell : outlook.cells()) {
            var modelCell = cell.validation().cell();
            outlookByCell.put(new CellKey(modelCell.position(), modelCell.metric(), modelCell.age()), cell);
        }

        List<TeamAgeOutlookEvidence> teams = new ArrayList<>();
        for (var team : league.teams()) {
            List<PlayerAgeOutlookEvidence> players = new ArrayList<>();
            for (var player : team.players()) {
                List<MetricAgeOutlookEvidence> metrics = new ArrayList<>();
                for (var metric : player.metrics()) {
                    AgingModelAgeOutlookAnalyzer.OutlookCell outlookCell = null;
                    if (metric.available()) {
                        var base = player.player();
                        if (base.modelAge() == null) {
                            throw new IllegalStateException("validated published metric requires model age");
                        }
                        outlookCell = outlookByCell.get(new CellKey(
                            base.position(), metric.metric().metric(), base.modelAge()));
                        if (outlookCell == null) {
                            throw new IllegalStateException("validated league metric missing age outlook: "
                                + base.position() + " " + metric.metric().metric() + " age=" + base.modelAge());
                        }
                        if (!outlookCell.outlookAvailable()) {
                            throw new IllegalStateException("validated league metric has unavailable age outlook: "
                                + base.position() + " " + metric.metric().metric() + " age=" + base.modelAge());
                        }
                    }
                    metrics.add(new MetricAgeOutlookEvidence(metric, outlookCell));
                }
                players.add(new PlayerAgeOutlookEvidence(player, List.copyOf(metrics)));
            }
            players.sort(Comparator.comparing((PlayerAgeOutlookEvidence value) -> value.player().player().position())
                .thenComparing(value -> value.player().player().playerName(), String.CASE_INSENSITIVE_ORDER)
                .thenComparing(value -> value.player().player().playerId()));
            teams.add(new TeamAgeOutlookEvidence(team.teamId(), team.teamName(), List.copyOf(players)));
        }
        teams.sort(Comparator.comparing(TeamAgeOutlookEvidence::teamName, String.CASE_INSENSITIVE_ORDER)
            .thenComparing(TeamAgeOutlookEvidence::teamId));

        return new LeagueAgeOutlookReport(
            league.leagueId(), league.season(), league.modelAgeAsOf(), league.leagueProfileSource(),
            league.supportPolicyId(), outlook.outlookPolicyId(), league.minimumDistinctSeasonTransitions(),
            league.modelProfileSource(), league.modelProductionSource(), league.publishedModelCells(),
            outlook.outlookAvailableCells(), List.copyOf(teams));
    }

    private record CellKey(String position, AgingModelSampleAuditAnalyzer.Metric metric, int age) {}

    public record MetricAgeOutlookEvidence(
        LeagueValidatedAgingModelEvidenceAnalyzer.ValidatedMetricEvidence metric,
        AgingModelAgeOutlookAnalyzer.OutlookCell outlook) {
        public MetricAgeOutlookEvidence {
            Objects.requireNonNull(metric, "metric must not be null");
            if (metric.available() != (outlook != null)) {
                throw new IllegalArgumentException("validated metric availability must match outlook presence");
            }
        }

        public boolean available() { return outlook != null && outlook.outlookAvailable(); }
        public AgingModelAgeOutlookPolicy.MetricOutlook label() { return outlook == null ? null : outlook.outlook(); }
        public AgingModelAgeOutlookPolicy.Direction direction() { return outlook == null ? null : outlook.direction(); }
    }

    public record PlayerAgeOutlookEvidence(
        LeagueValidatedAgingModelEvidenceAnalyzer.ValidatedPlayerEvidence player,
        List<MetricAgeOutlookEvidence> metrics) {
        public PlayerAgeOutlookEvidence {
            Objects.requireNonNull(player, "player must not be null");
            metrics = List.copyOf(Objects.requireNonNull(metrics, "metrics must not be null"));
        }

        public int favorableMetrics() { return count(AgingModelAgeOutlookPolicy.MetricOutlook.FAVORABLE); }
        public int neutralOrMixedMetrics() { return count(AgingModelAgeOutlookPolicy.MetricOutlook.NEUTRAL_OR_MIXED); }
        public int unfavorableMetrics() { return count(AgingModelAgeOutlookPolicy.MetricOutlook.UNFAVORABLE); }
        public int outlookAvailableMetrics() { return (int) metrics.stream().filter(MetricAgeOutlookEvidence::available).count(); }

        private int count(AgingModelAgeOutlookPolicy.MetricOutlook target) {
            return (int) metrics.stream().filter(value -> value.label() == target).count();
        }
    }

    public record TeamAgeOutlookEvidence(String teamId, String teamName, List<PlayerAgeOutlookEvidence> players) {
        public TeamAgeOutlookEvidence {
            Objects.requireNonNull(teamId, "teamId must not be null");
            Objects.requireNonNull(teamName, "teamName must not be null");
            players = List.copyOf(Objects.requireNonNull(players, "players must not be null"));
        }
    }

    public record LeagueAgeOutlookReport(String leagueId,
                                         int season,
                                         java.time.LocalDate modelAgeAsOf,
                                         String leagueProfileSource,
                                         String supportPolicyId,
                                         String outlookPolicyId,
                                         int minimumDistinctSeasonTransitions,
                                         String modelProfileSource,
                                         String modelProductionSource,
                                         int publishedModelCells,
                                         int outlookAvailableModelCells,
                                         List<TeamAgeOutlookEvidence> teams) {
        public LeagueAgeOutlookReport {
            Objects.requireNonNull(leagueId, "leagueId must not be null");
            Objects.requireNonNull(modelAgeAsOf, "modelAgeAsOf must not be null");
            Objects.requireNonNull(leagueProfileSource, "leagueProfileSource must not be null");
            Objects.requireNonNull(supportPolicyId, "supportPolicyId must not be null");
            Objects.requireNonNull(outlookPolicyId, "outlookPolicyId must not be null");
            Objects.requireNonNull(modelProfileSource, "modelProfileSource must not be null");
            Objects.requireNonNull(modelProductionSource, "modelProductionSource must not be null");
            teams = List.copyOf(Objects.requireNonNull(teams, "teams must not be null"));
        }

        public boolean allPublishedModelCellsHaveOutlook() {
            return publishedModelCells > 0 && publishedModelCells == outlookAvailableModelCells;
        }
        public int totalPlayers() { return teams.stream().mapToInt(team -> team.players().size()).sum(); }
    }
}
