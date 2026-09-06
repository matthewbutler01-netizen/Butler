package io.butler.bet.intelligence;

import io.butler.bet.domain.PlayerSeasonProduction;
import io.butler.bet.domain.PlayerWeekProduction;
import io.butler.bet.domain.RawScoringProduction;

import java.util.Arrays;
import java.util.Map;
import java.util.function.ToIntFunction;
import java.util.stream.Collectors;

/** Exact mapping between supported Sleeper scoring keys and stored raw production evidence. */
public enum SupportedScoringStat {
    PASSING_YARDS("pass_yd", "passingYards", 1, ProductionGrain.SEASON_AND_WEEK, RawScoringProduction::passingYards),
    PASSING_TOUCHDOWNS("pass_td", "passingTouchdowns", 1, ProductionGrain.SEASON_AND_WEEK, RawScoringProduction::passingTouchdowns),
    INTERCEPTIONS("pass_int", "interceptions", 1, ProductionGrain.SEASON_AND_WEEK, RawScoringProduction::interceptions),
    PASSING_SACKS_SUFFERED("pass_sack", "sacksSuffered", 3, ProductionGrain.WEEK_ONLY, RawScoringProduction::sacksSuffered),
    RUSHING_YARDS("rush_yd", "rushingYards", 1, ProductionGrain.SEASON_AND_WEEK, RawScoringProduction::rushingYards),
    RUSHING_TOUCHDOWNS("rush_td", "rushingTouchdowns", 1, ProductionGrain.SEASON_AND_WEEK, RawScoringProduction::rushingTouchdowns),
    RECEPTIONS("rec", "receptions", 1, ProductionGrain.SEASON_AND_WEEK, RawScoringProduction::receptions),
    RECEIVING_YARDS("rec_yd", "receivingYards", 1, ProductionGrain.SEASON_AND_WEEK, RawScoringProduction::receivingYards),
    RECEIVING_TOUCHDOWNS("rec_td", "receivingTouchdowns", 1, ProductionGrain.SEASON_AND_WEEK, RawScoringProduction::receivingTouchdowns),
    FUMBLES_LOST("fum_lost", "fumblesLost", 1, ProductionGrain.SEASON_AND_WEEK, RawScoringProduction::fumblesLost),
    PASSING_TWO_POINT_CONVERSIONS("pass_2pt", "passingTwoPointConversions", 2, ProductionGrain.SEASON_AND_WEEK, RawScoringProduction::passingTwoPointConversions),
    RUSHING_ATTEMPTS("rush_att", "rushingAttempts", 2, ProductionGrain.SEASON_AND_WEEK, RawScoringProduction::rushingAttempts),
    RUSHING_TWO_POINT_CONVERSIONS("rush_2pt", "rushingTwoPointConversions", 2, ProductionGrain.SEASON_AND_WEEK, RawScoringProduction::rushingTwoPointConversions),
    RECEIVING_TWO_POINT_CONVERSIONS("rec_2pt", "receivingTwoPointConversions", 2, ProductionGrain.SEASON_AND_WEEK, RawScoringProduction::receivingTwoPointConversions),
    FUMBLE_RECOVERY_TOUCHDOWNS("fum_rec_td", "fumbleRecoveryTouchdowns", 2, ProductionGrain.SEASON_AND_WEEK, RawScoringProduction::fumbleRecoveryTouchdowns),
    SPECIAL_TEAMS_TOUCHDOWNS("st_td", "specialTeamsTouchdowns", 2, ProductionGrain.SEASON_AND_WEEK, RawScoringProduction::specialTeamsTouchdowns),
    BONUS_PASSING_YARDS_300("bonus_pass_yd_300", "weeklyPassingYards>=300", 1, ProductionGrain.WEEK_ONLY,
        production -> threshold(production.passingYards(), 300)),
    BONUS_PASSING_YARDS_400("bonus_pass_yd_400", "weeklyPassingYards>=400", 1, ProductionGrain.WEEK_ONLY,
        production -> threshold(production.passingYards(), 400)),
    BONUS_RUSHING_YARDS_100("bonus_rush_yd_100", "weeklyRushingYards>=100", 1, ProductionGrain.WEEK_ONLY,
        production -> threshold(production.rushingYards(), 100)),
    BONUS_RUSHING_YARDS_200("bonus_rush_yd_200", "weeklyRushingYards>=200", 1, ProductionGrain.WEEK_ONLY,
        production -> threshold(production.rushingYards(), 200)),
    BONUS_RECEIVING_YARDS_100("bonus_rec_yd_100", "weeklyReceivingYards>=100", 1, ProductionGrain.WEEK_ONLY,
        production -> threshold(production.receivingYards(), 100)),
    BONUS_RECEIVING_YARDS_200("bonus_rec_yd_200", "weeklyReceivingYards>=200", 1, ProductionGrain.WEEK_ONLY,
        production -> threshold(production.receivingYards(), 200));

    private static final Map<String, SupportedScoringStat> BY_KEY = Arrays.stream(values())
        .collect(Collectors.toUnmodifiableMap(SupportedScoringStat::statKey, value -> value));

    private final String statKey;
    private final String productionField;
    private final int minimumRawScoringSchemaVersion;
    private final ProductionGrain productionGrain;
    private final ToIntFunction<RawScoringProduction> extractor;

    SupportedScoringStat(String statKey, String productionField, int minimumRawScoringSchemaVersion,
                         ProductionGrain productionGrain, ToIntFunction<RawScoringProduction> extractor) {
        this.statKey = statKey;
        this.productionField = productionField;
        this.minimumRawScoringSchemaVersion = minimumRawScoringSchemaVersion;
        this.productionGrain = productionGrain;
        this.extractor = extractor;
    }

    public String statKey() { return statKey; }
    public String productionField() { return productionField; }
    public int minimumRawScoringSchemaVersion() { return minimumRawScoringSchemaVersion; }
    public ProductionGrain productionGrain() { return productionGrain; }

    public boolean supports(ProductionGrain requestedGrain) {
        if (requestedGrain == null) throw new IllegalArgumentException("requestedGrain must not be null");
        return productionGrain == ProductionGrain.SEASON_AND_WEEK || requestedGrain == ProductionGrain.WEEK_ONLY;
    }

    public int value(RawScoringProduction production) {
        if (production == null) throw new IllegalArgumentException("production must not be null");
        ProductionGrain actualGrain = grainOf(production);
        if (!supports(actualGrain)) {
            throw new IllegalStateException("Exact scoring for Sleeper stat '" + statKey
                + "' requires player-week production; season aggregates cannot prove this weekly-only scoring rule.");
        }
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

    private static ProductionGrain grainOf(RawScoringProduction production) {
        if (production instanceof PlayerWeekProduction) return ProductionGrain.WEEK_ONLY;
        if (production instanceof PlayerSeasonProduction) return ProductionGrain.SEASON_AND_WEEK;
        throw new IllegalStateException("Unsupported raw scoring production type: " + production.getClass().getName());
    }

    private static int threshold(int value, int minimum) {
        return value >= minimum ? 1 : 0;
    }

    public enum ProductionGrain {
        SEASON_AND_WEEK,
        WEEK_ONLY
    }
}
