package io.butler.bet.domain;

import java.util.Objects;
import java.util.UUID;

public final class Player {
    private final String id;
    private final String externalId;
    private final String displayName;
    private final String position;
    private final String nflTeam;

    public Player(String id, String externalId, String displayName, String position, String nflTeam) {
        this.id = requireText(id, "id");
        this.externalId = normalizeOptional(externalId);
        this.displayName = requireText(displayName, "displayName");
        this.position = requireText(position, "position");
        this.nflTeam = normalizeOptional(nflTeam);
    }

    public static Player create(String displayName, String position, String nflTeam) {
        return new Player(UUID.randomUUID().toString(), null, displayName, position, nflTeam);
    }

    public String getId() { return id; }
    public String getExternalId() { return externalId; }
    public String getDisplayName() { return displayName; }
    public String getPosition() { return position; }
    public String getNflTeam() { return nflTeam; }

    private static String requireText(String value, String field) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(field + " must not be blank");
        return value.trim();
    }

    private static String normalizeOptional(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    @Override public boolean equals(Object o) {
        return this == o || (o instanceof Player player && id.equals(player.id));
    }

    @Override public int hashCode() { return Objects.hash(id); }

    @Override public String toString() {
        return "Player{id='" + id + "', externalId='" + externalId + "', displayName='" + displayName + "', position='" + position + "', nflTeam='" + nflTeam + "'}";
    }
}
