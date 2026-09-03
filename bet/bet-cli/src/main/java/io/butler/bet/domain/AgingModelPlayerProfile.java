package io.butler.bet.domain;

import java.time.LocalDate;
import java.util.Objects;

/** Provider-backed player identity/profile snapshot for model training, independent of fantasy rosters. */
public record AgingModelPlayerProfile(String gsisId, String displayName, LocalDate birthDate,
                                      String position, String source, LocalDate asOfDate) {
    public AgingModelPlayerProfile {
        gsisId = requireText(gsisId, "gsisId");
        displayName = requireText(displayName, "displayName");
        position = requireText(position, "position");
        source = requireText(source, "source");
        Objects.requireNonNull(asOfDate, "asOfDate must not be null");
        if (birthDate != null && birthDate.isAfter(asOfDate)) {
            throw new IllegalArgumentException("birthDate must not be after asOfDate");
        }
    }

    private static String requireText(String value, String field) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(field + " must not be blank");
        return value.trim();
    }
}
