package io.butler.bet.domain;

import java.util.Objects;
import java.util.UUID;

public final class League {
    private final String id;
    private final String externalId;
    private final String name;

    public League(String id, String externalId, String name) {
        this.id = requireText(id, "id");
        this.externalId = normalizeOptional(externalId);
        this.name = requireText(name, "name");
    }

    public static League create(String name) {
        return new League(UUID.randomUUID().toString(), null, name);
    }

    public String getId() { return id; }
    public String getExternalId() { return externalId; }
    public String getName() { return name; }

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
        return "League{id='" + id + "', externalId='" + externalId + "', name='" + name + "'}";
    }
}
