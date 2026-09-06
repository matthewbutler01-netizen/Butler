package io.butler.bet.intelligence;

import io.butler.bet.domain.RawScoringProduction;

import java.util.Arrays;
import java.util.Map;
import java.util.function.ToIntFunction;
import java.util.stream.Collectors;

/** Exact mapping between supported Sleeper scoring keys and stored raw production fields. */
public enum SupportedScoringStat {
    PASSING_YARDS("pass_yd", "passingYards", 1, RawScoringProduction::passingYards),
    PASSING_TOUCHDOWNS("pass_td", "passingTouchdowns", 1, RawScoringProduction::passingTouchdowns),
    INTERCEPTIONS("pass_int", "interceptions", 1, RawScoringProduction::interceptions),
    RUSHING_YARDS("rush_yd", "rushingYards", 1, RawScoringProduction::rushingYards),
    RUSHING_TOUCHDOWNS("rush_td", "rushingTouchdowns", 1, RawScoringProduction::rushingTouchdowns),
    RECEPTIONS("rec", "receptions", 1, RawScoringProduction::receptions),
    RECEIVING_YARDS("rec_yd", "receivingYards", 1, RawScoringProduction::receivingYards),
    RECEIVING_TOUCHDOWNS("rec_td", "receivingTouchdowns", 1, RawScoringProduction::receivingTouchdowns),
    FUMBLES_LOST("fum_lost", "fumblesLost", 1, RawScoringProduction::fumblesLost),
    PASSING_TWO_POINT_CONVERSIONS("pass_2pt", "passingTwoPointConversions", 2, RawScoringProduction::passingTwoPointConversions),
    RUSHING_ATTEMPTS("rush_att", "rushingAttempts", 2, RawScoringProduction::rushingAttempts),
    RUSHING_TWO_POINT_CONVERSIONS("rush_2pt", "rushingTwoPointConversions", 2, RawScoringProduction::rushingTwoPointConversions),
    RECEIVING_TWO_POINT_CONVERSIONS("rec_2pt", "receivingTwoPointConversions", 2, RawScoringProduction::receivingTwoPointConversions),
    FUMBLE_RECOVERY_TOUCHDOWNS("fum_rec_td", "fumbleRecoveryTouchdowns", 2, RawScoringProduction::fumbleRecoveryTouchdowns),
    SPECIAL_TEAMS_TOUCHDOWNS("st_td", "specialTeamsTouchdowns", 2, RawScoringProduction::specialTeamsTouchdowns);

    private static final Map<String, SupportedScoringStat> BY_KEY = Arrays.stream(values())
        .collect(Collectors.toUnmodifiableMap(SupportedScoringStat::statKey, value -> value));

    private final String statKey;
    private final String productionField;
    private final int minimumRawScoringSchemaVersion;
    private final ToIntFunction<RawScoringProduction> extractor;

    SupportedScoringStat(String statKey, String productionField, int minimumRawScoringSchemaVersion,
                         ToIntFunction<RawScoringProduction> extractor) {
        this.statKey = statKey;
        this.productionField = productionField;
        this.minimumRawScoringSchemaVersion = minimumRawScoringSchemaVersion;
        this.extractor = extractor;
    }

    public String statKey() { return statKey; }
    public String productionField() { return productionField; }
    public int minimumRawScoringSchemaVersion() { return minimumRawScoringSchemaVersion; }

    public int value(RawScoringProduction production) {
        if (production == null) throw new IllegalArgumentException("production must not be null");
        if (production.rawScoringSchemaVersion() < minimumRawScoringSchemaVersion) {
            throw new IllegalStateException("Exact scoring for Sleeper stat '" + statKey
                + "' requires refreshed raw production schema v" + minimumRawScoringSchemaVersion
                + "; production row " + production.id() + " is v" + production.rawScoringSchemaVersion()
                + ". Refresh nflverse production for this season.");
        }
        return extractor.applyAsInt(production);
    }

    public static SupportedScoringStat find(String statKey) {
        return statKey == null ? null : BY_KEY.get(statKey);
    }
}
