package io.butler.bet.intelligence;

import io.butler.bet.domain.RawScoringProduction;

import java.util.Arrays;
import java.util.Map;
import java.util.function.ToIntFunction;
import java.util.stream.Collectors;

/** Exact mapping between supported Sleeper scoring keys and stored raw production fields. */
public enum SupportedScoringStat {
    PASSING_YARDS("pass_yd", "passingYards", RawScoringProduction::passingYards),
    PASSING_TOUCHDOWNS("pass_td", "passingTouchdowns", RawScoringProduction::passingTouchdowns),
    INTERCEPTIONS("pass_int", "interceptions", RawScoringProduction::interceptions),
    RUSHING_YARDS("rush_yd", "rushingYards", RawScoringProduction::rushingYards),
    RUSHING_TOUCHDOWNS("rush_td", "rushingTouchdowns", RawScoringProduction::rushingTouchdowns),
    RECEPTIONS("rec", "receptions", RawScoringProduction::receptions),
    RECEIVING_YARDS("rec_yd", "receivingYards", RawScoringProduction::receivingYards),
    RECEIVING_TOUCHDOWNS("rec_td", "receivingTouchdowns", RawScoringProduction::receivingTouchdowns),
    FUMBLES_LOST("fum_lost", "fumblesLost", RawScoringProduction::fumblesLost);

    private static final Map<String, SupportedScoringStat> BY_KEY = Arrays.stream(values())
        .collect(Collectors.toUnmodifiableMap(SupportedScoringStat::statKey, value -> value));

    private final String statKey;
    private final String productionField;
    private final ToIntFunction<RawScoringProduction> extractor;

    SupportedScoringStat(
        String statKey,
        String productionField,
        ToIntFunction<RawScoringProduction> extractor) {
        this.statKey = statKey;
        this.productionField = productionField;
        this.extractor = extractor;
    }

    public String statKey() {
        return statKey;
    }

    public String productionField() {
        return productionField;
    }

    public int value(RawScoringProduction production) {
        if (production == null) throw new IllegalArgumentException("production must not be null");
        return extractor.applyAsInt(production);
    }

    public static SupportedScoringStat find(String statKey) {
        return statKey == null ? null : BY_KEY.get(statKey);
    }
}
