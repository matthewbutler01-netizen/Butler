package io.butler.bet.domain;

import java.time.LocalDate;
import java.util.Objects;
import java.util.UUID;

public final class DraftPickValue {
    private final String id;
    private final String draftPickId;
    private final double value;
    private final String source;
    private final LocalDate asOfDate;

    public DraftPickValue(String id, String draftPickId, double value, String source, LocalDate asOfDate) {
        this.id = requireText(id, "id");
        this.draftPickId = requireText(draftPickId, "draftPickId");
        if (!Double.isFinite(value) || value < 0) {
            throw new IllegalArgumentException("value must be finite and non-negative");
        }
        this.value = value;
        this.source = requireText(source, "source");
        this.asOfDate = Objects.requireNonNull(asOfDate, "asOfDate must not be null");
    }

    public static DraftPickValue create(String draftPickId, double value, String source, LocalDate asOfDate) {
        return new DraftPickValue(UUID.randomUUID().toString(), draftPickId, value, source, asOfDate);
    }

    public String getId() { return id; }
    public String getDraftPickId() { return draftPickId; }
    public double getValue() { return value; }
    public String getSource() { return source; }
    public LocalDate getAsOfDate() { return asOfDate; }

    private static String requireText(String value, String field) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(field + " must not be blank");
        return value.trim();
    }

    @Override public boolean equals(Object other) {
        return this == other || other instanceof DraftPickValue that && id.equals(that.id);
    }

    @Override public int hashCode() { return id.hashCode(); }
}
