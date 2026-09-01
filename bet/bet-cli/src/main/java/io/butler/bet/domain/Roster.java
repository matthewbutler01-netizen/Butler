package io.butler.bet.domain;

import java.util.Objects;
import java.util.UUID;

public final class Roster {
    private final String id;
    private final String externalId;
    private final String teamId;
    private final String playerId;
    private final String slot;

    public Roster(String id, String externalId, String teamId, String playerId, String slot) {
        this.id = requireText(id, "id");
        this.externalId = normalizeOptional(externalId);
        this.teamId = requireText(teamId, "teamId");
        this.playerId = requireText(playerId, "playerId");
        this.slot = requireText(slot, "slot");
    }

    public static Roster create(String teamId, String playerId, String slot) {
        return new Roster(UUID.randomUUID().toString(), null, teamId, playerId, slot);
    }

    public String getId() { return id; }
    public String getExternalId() { return externalId; }
    public String getTeamId() { return teamId; }
    public String getPlayerId() { return playerId; }
    public String getSlot() { return slot; }

    private static String requireText(String value, String field) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(field + " must not be blank");
        return value.trim();
    }

    private static String normalizeOptional(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    @Override public boolean equals(Object o) {
        return this == o || (o instanceof Roster roster && id.equals(roster.id));
    }

    @Override public int hashCode() { return Objects.hash(id); }

    @Override public String toString() {
        return "Roster{id='" + id + "', externalId='" + externalId + "', teamId='" + teamId + "', playerId='" + playerId + "', slot='" + slot + "'}";
    }
}
