package io.butler.bet.intelligence;

import io.butler.bet.domain.Player;

import java.util.Locale;
import java.util.Objects;

/**
 * Baseline dynasty player-value model.
 *
 * This deliberately produces a transparent internal value rather than pretending
 * Butler already has market/projection data. Later value sources can replace or
 * blend with this model without changing the ranking API.
 */
public final class PlayerValueModel {
    public PlayerValue value(Player player) {
        Objects.requireNonNull(player, "player must not be null");
        String position = player.getPosition().trim().toUpperCase(Locale.ROOT);
        double score = switch (position) {
            case "QB" -> 100.0;
            case "WR" -> 90.0;
            case "RB" -> 85.0;
            case "TE" -> 75.0;
            case "K", "DEF", "DST" -> 20.0;
            default -> 35.0;
        };
        return new PlayerValue(player.getId(), player.getDisplayName(), position, score);
    }

    public record PlayerValue(String playerId, String playerName, String position, double score) {}
}
