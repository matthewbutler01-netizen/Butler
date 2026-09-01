package io.butler.bet.domain;

import java.util.Objects;
import java.util.UUID;

public final class Team {
    private final String id;
    private final String externalId;
    private final String leagueId;
    private final String name;

    public Team(String id, String externalId, String leagueId, String name) {
        this.id = requireText(id, "id");
        this.externalId = normalizeOptional(externalId);
        this.leagueId = requireText(leagueId, "leagueId");
        this.name = requireText(name, "name");
    }

    public static Team create(String leagueId, String name) {
        return new Team(UUID.randomUUID().toString(), null, leagueId, name);
    }

    public String getId() { return id; }
    public String getExternalId() { return externalId; }
    public String getLeagueId() { return leagueId; }
    public String getName() { return name; }

    private static String requireText(String value, String field) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(field + " must not be blank");
        return value.trim();
    }

    private static String normalizeOptional(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    @Override public boolean equals(Object o) {
        return this == o || (o instanceof Team team && id.equals(team.id));
    }

    @Override public int hashCode() { return Objects.hash(id); }

    @Override public String toString() {
        return "Team{id='" + id + "', externalId='" + externalId + "', leagueId='" + leagueId + "', name='" + name + "'}";
    }
}
