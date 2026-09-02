package io.butler.bet.domain;

import java.time.LocalDate;
import java.time.Period;
import java.util.Objects;

/** Optional biographical metadata kept separate from core player identity. */
public record PlayerProfile(String playerId, LocalDate birthDate, Integer yearsExperience) {
    public PlayerProfile {
        if (playerId == null || playerId.isBlank()) throw new IllegalArgumentException("playerId must not be blank");
        playerId = playerId.trim();
        if (yearsExperience != null && yearsExperience < 0) {
            throw new IllegalArgumentException("yearsExperience must not be negative");
        }
    }

    public Integer ageOn(LocalDate date) {
        Objects.requireNonNull(date, "date must not be null");
        if (birthDate == null) return null;
        if (date.isBefore(birthDate)) throw new IllegalArgumentException("date must not predate birthDate");
        return Period.between(birthDate, date).getYears();
    }
}
