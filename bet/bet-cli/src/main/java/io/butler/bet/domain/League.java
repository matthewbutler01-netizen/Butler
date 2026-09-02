package io.butler.bet.domain;

import java.util.Objects;
import java.util.UUID;

public final class League {
    private final String id;
    private final String externalId;
    private final String name;
    private final Integer season;

    public League(String id, String externalId, String name) {
        this(id, externalId, name, null);
    }

    public League(String id, String externalId, String name, Integer season) {
        this.id = requireText(id, "id");
        this.externalId = normalizeOptional(externalId);
        this.name = requireText(name, "name");
        if (season != null && season <= 0) throw new IllegalArgumentException("season must be positive");
        this.season = season;
    }

    public static League create(String name) {
        return new League(UUID.randomUUID().toString(), null, name, null);
    }

    public String getId() { return id; }
    public String getExternalId() { return externalId; }
    public String getName() { return name; }
    public Integer getSeason() { return season; }

    private static String requireText(String value, String field) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(field + " must not be blank");
        return value.trim();
    }

    private static String normalizeOptional(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    @Override public boolean equals(Object o) {
        return this == o || (o instanceof League league && id.equals(league.id));
    }

    @Override public int hashCode() { return Objects.hash(id); }

    @Override public String toString() {
        return "League{id='" + id + "', externalId='" + externalId + "', name='" + name + "', season=" + season + "}";
    }
}
