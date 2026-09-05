package io.butler.bet.intelligence;

import io.butler.bet.domain.PlayerSeasonProduction;

import java.util.Arrays;
import java.util.Map;
import java.util.function.ToIntFunction;
import java.util.stream.Collectors;

/** Exact mapping between supported Sleeper scoring keys and stored raw production fields. */
public enum SupportedScoringStat {
    PASSING_YARDS("pass_yd", "passingYards", PlayerSeasonProduction::passingYards),
    PASSING_TOUCHDOWNS("pass_td", "passingTouchdowns", PlayerSeasonProduction::passingTouchdowns),
    INTERCEPTIONS("pass_int", "interceptions", PlayerSeasonProduction::interceptions),
    RUSHING_YARDS("rush_yd", "rushingYards", PlayerSeasonProduction::rushingYards),
    RUSHING_TOUCHDOWNS("rush_td", "rushingTouchdowns", PlayerSeasonProduction::rushingTouchdowns),
    RECEPTIONS("rec", "receptions", PlayerSeasonProduction::receptions),
    RECEIVING_YARDS("rec_yd", "receivingYards", PlayerSeasonProduction::receivingYards),
    RECEIVING_TOUCHDOWNS("rec_td", "receivingTouchdowns", PlayerSeasonProduction::receivingTouchdowns),
    FUMBLES_LOST("fum_lost", "fumblesLost", PlayerSeasonProduction::fumblesLost);

    private static final Map<String, SupportedScoringStat> BY_KEY = Arrays.stream(values())
        .collect(Collectors.toUnmodifiableMap(SupportedScoringStat::statKey, value -> value));

    private final String statKey;
    private final String productionField;
    private final ToIntFunction<PlayerSeasonProduction> extractor;

    SupportedScoringStat(
        String statKey,
        String productionField,
        ToIntFunction<PlayerSeasonProduction> extractor) {
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

    public int value(PlayerSeasonProduction production) {
        if (production == null) throw new IllegalArgumentException("production must not be null");
        return extractor.applyAsInt(production);
    }

    public static SupportedScoringStat find(String statKey) {
        return statKey == null ? null : BY_KEY.get(statKey);
    }
}
