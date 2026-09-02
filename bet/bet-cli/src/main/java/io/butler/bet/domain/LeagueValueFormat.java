package io.butler.bet.domain;

import java.util.List;
import java.util.Locale;
import java.util.Objects;

public enum LeagueValueFormat {
    UNKNOWN,
    ONE_QB,
    TWO_QB;

    public static LeagueValueFormat fromRosterPositions(List<String> rosterPositions) {
        Objects.requireNonNull(rosterPositions, "rosterPositions must not be null");
        if (rosterPositions.isEmpty()) return UNKNOWN;

        int quarterbackSlots = 0;
        for (String position : rosterPositions) {
            if (position == null) continue;
            String normalized = position.trim().toUpperCase(Locale.ROOT);
            if (normalized.equals("SUPER_FLEX")) return TWO_QB;
            if (normalized.equals("QB")) quarterbackSlots++;
        }
        return quarterbackSlots >= 2 ? TWO_QB : ONE_QB;
    }
}
