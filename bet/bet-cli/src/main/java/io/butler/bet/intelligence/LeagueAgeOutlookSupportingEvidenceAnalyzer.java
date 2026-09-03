package io.butler.bet.intelligence;

import io.butler.bet.data.Database;

import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Converts governed league age-outlook metrics into neutral supporting-evidence flags.
 * No flag is assigned a numeric weight and no recommendation is created.
 */
public final class LeagueAgeOutlookSupportingEvidenceAnalyzer {
    private static final String CATEGORY = "AGE_OUTLOOK";

    private final LeagueAgeOutlookEvidenceAnalyzer outlook;

    public LeagueAgeOutlookSupportingEvidenceAnalyzer(Database database) {
        this.outlook = new LeagueAgeOutlookEvidenceAnalyzer(
            Objects.requireNonNull(database, "database must not be null"));
    }

    public SupportingEvidenceReport analyze(String leagueId, int season) throws SQLException {
        return adapt(outlook.analyze(leagueId, season));
    }

    static SupportingEvidenceReport adapt(LeagueAgeOutlookEvidenceAnalyzer.LeagueAgeOutlookReport report) {
        Objects.requireNonNull(report, "report must not be null");

        List<PlayerSupportingEvidence> players = new ArrayList<>();
        for (var team : report.teams()) {
            for (var player : team.players()) {
                var base = player.player().player();
                List<DecisionSupportingEvidenceFlag> flags = new ArrayList<>();
                for (var metric : player.metrics()) {
                    if (!metric.available()) continue;
                    var metricName = metric.metric().metric().metric().name();
                    flags.add(new DecisionSupportingEvidenceFlag(
                        base.playerId(),
                        CATEGORY,
                        metricName,
                        toSignal(metric.label()),
                        summary(metricName, metric.label()),
                        report.outlookPolicyId(),
                        report.modelProfileSource() + "+" + report.modelProductionSource()));
                }
                players.add(new PlayerSupportingEvidence(
                    team.teamId(), team.teamName(), base.playerId(), base.playerName(), base.position(),
                    base.modelAge(), List.copyOf(flags)));
            }
        }

        return new SupportingEvidenceReport(
            report.leagueId(), report.season(), report.modelAgeAsOf(), report.supportPolicyId(),
            report.outlookPolicyId(), report.modelProfileSource(), report.modelProductionSource(),
            List.copyOf(players));
    }

    private static DecisionSupportingEvidenceFlag.Signal toSignal(AgingModelAgeOutlookPolicy.MetricOutlook outlook) {
        return switch (Objects.requireNonNull(outlook, "outlook must not be null")) {
            case FAVORABLE -> DecisionSupportingEvidenceFlag.Signal.FAVORABLE;
            case UNFAVORABLE -> DecisionSupportingEvidenceFlag.Signal.UNFAVORABLE;
            case NEUTRAL_OR_MIXED -> DecisionSupportingEvidenceFlag.Signal.INCONCLUSIVE;
        };
    }

    private static String summary(String metric, AgingModelAgeOutlookPolicy.MetricOutlook outlook) {
        return switch (outlook) {
            case FAVORABLE -> "Validated historical aging evidence is favorable for " + metric + ".";
            case UNFAVORABLE -> "Validated historical aging evidence is unfavorable for " + metric + ".";
            case NEUTRAL_OR_MIXED -> "Validated historical aging evidence is inconclusive for " + metric + ".";
        };
    }

    public record PlayerSupportingEvidence(String teamId,
                                           String teamName,
                                           String playerId,
                                           String playerName,
                                           String position,
                                           Integer modelAge,
                                           List<DecisionSupportingEvidenceFlag> flags) {
        public PlayerSupportingEvidence {
            Objects.requireNonNull(teamId, "teamId must not be null");
            Objects.requireNonNull(teamName, "teamName must not be null");
            Objects.requireNonNull(playerId, "playerId must not be null");
            Objects.requireNonNull(playerName, "playerName must not be null");
            Objects.requireNonNull(position, "position must not be null");
            flags = List.copyOf(Objects.requireNonNull(flags, "flags must not be null"));
        }

        public int favorableFlags() { return count(DecisionSupportingEvidenceFlag.Signal.FAVORABLE); }
        public int unfavorableFlags() { return count(DecisionSupportingEvidenceFlag.Signal.UNFAVORABLE); }
        public int inconclusiveFlags() { return count(DecisionSupportingEvidenceFlag.Signal.INCONCLUSIVE); }

        private int count(DecisionSupportingEvidenceFlag.Signal signal) {
            return (int) flags.stream().filter(flag -> flag.signal() == signal).count();
        }
    }

    public record SupportingEvidenceReport(String leagueId,
                                           int season,
                                           java.time.LocalDate modelAgeAsOf,
                                           String supportPolicyId,
                                           String outlookPolicyId,
                                           String modelProfileSource,
                                           String modelProductionSource,
                                           List<PlayerSupportingEvidence> players) {
        public SupportingEvidenceReport {
            Objects.requireNonNull(leagueId, "leagueId must not be null");
            Objects.requireNonNull(modelAgeAsOf, "modelAgeAsOf must not be null");
            Objects.requireNonNull(supportPolicyId, "supportPolicyId must not be null");
            Objects.requireNonNull(outlookPolicyId, "outlookPolicyId must not be null");
            Objects.requireNonNull(modelProfileSource, "modelProfileSource must not be null");
            Objects.requireNonNull(modelProductionSource, "modelProductionSource must not be null");
            players = List.copyOf(Objects.requireNonNull(players, "players must not be null"));
        }

        public int totalFlags() { return players.stream().mapToInt(player -> player.flags().size()).sum(); }
        public int directionalFlags() {
            return players.stream().mapToInt(player -> player.favorableFlags() + player.unfavorableFlags()).sum();
        }
    }
}
