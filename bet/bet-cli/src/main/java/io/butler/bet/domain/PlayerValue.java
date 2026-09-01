package io.butler.bet.domain;

import java.time.LocalDate;
import java.util.Objects;
import java.util.UUID;

public final class PlayerValue {
    private final String id;
    private final String playerId;
    private final double value;
    private final String source;
    private final LocalDate asOfDate;

    public PlayerValue(String id, String playerId, double value, String source, LocalDate asOfDate) {
        this.id = requireText(id, "id");
        this.playerId = requireText(playerId, "playerId");
        if (!Double.isFinite(value) || value < 0) throw new IllegalArgumentException("value must be finite and non-negative");
        this.value = value;
        this.source = requireText(source, "source");
        this.asOfDate = Objects.requireNonNull(asOfDate, "asOfDate must not be null");
    }

    public static PlayerValue create(String playerId, double value, String source, LocalDate asOfDate) {
        return new PlayerValue(UUID.randomUUID().toString(), playerId, value, source, asOfDate);
    }

    public String getId() { return id; }
    public String getPlayerId() { return playerId; }
    public double getValue() { return value; }
    public String getSource() { return source; }
    public LocalDate getAsOfDate() { return asOfDate; }

    private static String requireText(String value, String field) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(field + " must not be blank");
        return value.trim();
    }

    @Override
    public boolean equals(Object other) {
        return this == other || other instanceof PlayerValue that && id.equals(that.id);
    }

    @Override
    public int hashCode() { return id.hashCode(); }
}
