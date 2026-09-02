package io.butler.bet.domain;

import java.util.Objects;
import java.util.UUID;

public final class DraftPick {
    private final String id;
    private final String leagueId;
    private final int season;
    private final int round;
    private final String originalTeamId;
    private final String ownerTeamId;
    private final Integer pickNumber;

    public DraftPick(String id, String leagueId, int season, int round,
                     String originalTeamId, String ownerTeamId, Integer pickNumber) {
        this.id = requireText(id, "id");
        this.leagueId = requireText(leagueId, "leagueId");
        if (season <= 0) throw new IllegalArgumentException("season must be positive");
        if (round <= 0) throw new IllegalArgumentException("round must be positive");
        if (pickNumber != null && pickNumber <= 0) throw new IllegalArgumentException("pickNumber must be positive when present");
        this.season = season;
        this.round = round;
        this.originalTeamId = requireText(originalTeamId, "originalTeamId");
        this.ownerTeamId = requireText(ownerTeamId, "ownerTeamId");
        this.pickNumber = pickNumber;
    }

    public static DraftPick create(String leagueId, int season, int round,
                                   String originalTeamId, String ownerTeamId) {
        return new DraftPick(UUID.randomUUID().toString(), leagueId, season, round,
            originalTeamId, ownerTeamId, null);
    }

    public String getId() { return id; }
    public String getLeagueId() { return leagueId; }
    public int getSeason() { return season; }
    public int getRound() { return round; }
    public String getOriginalTeamId() { return originalTeamId; }
    public String getOwnerTeamId() { return ownerTeamId; }
    public Integer getPickNumber() { return pickNumber; }

    private static String requireText(String value, String field) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(field + " must not be blank");
        return value.trim();
    }

    @Override public boolean equals(Object o) {
        return this == o || (o instanceof DraftPick pick && id.equals(pick.id));
    }

    @Override public int hashCode() { return Objects.hash(id); }
}
