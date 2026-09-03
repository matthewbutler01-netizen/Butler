package io.butler.bet.intelligence;

import io.butler.bet.data.Database;

import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Composes persisted trade market values with governed supporting evidence.
 * Supporting flags remain descriptive context and never modify market value, completeness,
 * value difference, or produce a winner/fairness/recommendation label.
 */
public final class TradeSupportingEvidenceAnalyzer {
    private final TradeValueAnalyzer tradeValues;
    private final LeagueAgeOutlookSupportingEvidenceAnalyzer supportingEvidence;

    public TradeSupportingEvidenceAnalyzer(Database database) {
        Objects.requireNonNull(database, "database must not be null");
        this.tradeValues = new TradeValueAnalyzer(database);
        this.supportingEvidence = new LeagueAgeOutlookSupportingEvidenceAnalyzer(database);
    }

    public TradeEvidencePackage analyze(String leagueId, int season,
                                        List<String> sideAPlayerIds, List<String> sideBPlayerIds)
        throws SQLException {
        return compose(
            tradeValues.analyze(leagueId, sideAPlayerIds, sideBPlayerIds),
            supportingEvidence.analyze(leagueId, season));
    }

    public TradeEvidencePackage analyze(String leagueId, int season,
                                        List<String> sideAPlayerIds, List<String> sideBPlayerIds,
                                        String source) throws SQLException {
        return compose(
            tradeValues.analyze(leagueId, sideAPlayerIds, sideBPlayerIds, source),
            supportingEvidence.analyze(leagueId, season));
    }

    static TradeEvidencePackage compose(
        TradeValueAnalyzer.TradeReport trade,
        LeagueAgeOutlookSupportingEvidenceAnalyzer.SupportingEvidenceReport supporting) {
        Objects.requireNonNull(trade, "trade must not be null");
        Objects.requireNonNull(supporting, "supporting must not be null");
        if (!trade.leagueId().equals(supporting.leagueId())) {
            throw new IllegalStateException("trade value and supporting evidence reference different leagues");
        }

        Map<String, LeagueAgeOutlookSupportingEvidenceAnalyzer.PlayerSupportingEvidence> byPlayer = new HashMap<>();
        for (var player : supporting.players()) {
            var previous = byPlayer.putIfAbsent(player.playerId(), player);
            if (previous != null) {
                throw new IllegalStateException("duplicate supporting evidence for player: " + player.playerId());
            }
        }

        return new TradeEvidencePackage(
            trade,
            supporting.season(),
            supporting.modelAgeAsOf(),
            supporting.supportPolicyId(),
            supporting.outlookPolicyId(),
            supporting.modelProfileSource(),
            supporting.modelProductionSource(),
            attach(trade.sideA(), byPlayer),
            attach(trade.sideB(), byPlayer));
    }

    private static TradeEvidenceSide attach(
        TradeValueAnalyzer.TradeSide side,
        Map<String, LeagueAgeOutlookSupportingEvidenceAnalyzer.PlayerSupportingEvidence> byPlayer) {
        List<TradePlayerEvidence> players = new ArrayList<>();
        for (var player : side.players()) {
            var supporting = byPlayer.get(player.playerId());
            List<DecisionSupportingEvidenceFlag> flags = supporting == null
                ? List.of()
                : supporting.flags();
            players.add(new TradePlayerEvidence(player, flags));
        }
        return new TradeEvidenceSide(side, List.copyOf(players));
    }

    public record TradePlayerEvidence(TradeValueAnalyzer.TradePlayer player,
                                      List<DecisionSupportingEvidenceFlag> supportingFlags) {
        public TradePlayerEvidence {
            Objects.requireNonNull(player, "player must not be null");
            supportingFlags = List.copyOf(Objects.requireNonNull(supportingFlags, "supportingFlags must not be null"));
            for (var flag : supportingFlags) {
                if (!player.playerId().equals(flag.subjectId())) {
                    throw new IllegalArgumentException("supporting flag subject must match trade player");
                }
            }
        }

        public int favorableFlags() { return count(DecisionSupportingEvidenceFlag.Signal.FAVORABLE); }
        public int unfavorableFlags() { return count(DecisionSupportingEvidenceFlag.Signal.UNFAVORABLE); }
        public int inconclusiveFlags() { return count(DecisionSupportingEvidenceFlag.Signal.INCONCLUSIVE); }

        private int count(DecisionSupportingEvidenceFlag.Signal signal) {
            return (int) supportingFlags.stream().filter(flag -> flag.signal() == signal).count();
        }
    }

    public record TradeEvidenceSide(TradeValueAnalyzer.TradeSide value,
                                    List<TradePlayerEvidence> players) {
        public TradeEvidenceSide {
            Objects.requireNonNull(value, "value must not be null");
            players = List.copyOf(Objects.requireNonNull(players, "players must not be null"));
            if (players.size() != value.players().size()) {
                throw new IllegalArgumentException("trade evidence player count must match value side");
            }
        }

        public int supportingFlags() {
            return players.stream().mapToInt(player -> player.supportingFlags().size()).sum();
        }
        public int directionalSupportingFlags() {
            return players.stream().mapToInt(player -> player.favorableFlags() + player.unfavorableFlags()).sum();
        }
    }

    public record TradeEvidencePackage(TradeValueAnalyzer.TradeReport tradeValue,
                                       int season,
                                       java.time.LocalDate modelAgeAsOf,
                                       String supportPolicyId,
                                       String outlookPolicyId,
                                       String modelProfileSource,
                                       String modelProductionSource,
                                       TradeEvidenceSide sideA,
                                       TradeEvidenceSide sideB) {
        public TradeEvidencePackage {
            Objects.requireNonNull(tradeValue, "tradeValue must not be null");
            Objects.requireNonNull(modelAgeAsOf, "modelAgeAsOf must not be null");
            Objects.requireNonNull(supportPolicyId, "supportPolicyId must not be null");
            Objects.requireNonNull(outlookPolicyId, "outlookPolicyId must not be null");
            Objects.requireNonNull(modelProfileSource, "modelProfileSource must not be null");
            Objects.requireNonNull(modelProductionSource, "modelProductionSource must not be null");
            Objects.requireNonNull(sideA, "sideA must not be null");
            Objects.requireNonNull(sideB, "sideB must not be null");
            if (sideA.value() != tradeValue.sideA() || sideB.value() != tradeValue.sideB()) {
                throw new IllegalArgumentException("trade evidence sides must wrap the trade value sides");
            }
        }

        public boolean complete() { return tradeValue.complete(); }
        public Double valueDifference() { return tradeValue.valueDifference(); }
        public int supportingFlags() { return sideA.supportingFlags() + sideB.supportingFlags(); }
        public int directionalSupportingFlags() {
            return sideA.directionalSupportingFlags() + sideB.directionalSupportingFlags();
        }
    }
}
