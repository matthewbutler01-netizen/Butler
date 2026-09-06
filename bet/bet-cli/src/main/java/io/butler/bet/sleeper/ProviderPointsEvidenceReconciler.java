package io.butler.bet.sleeper;

import io.butler.bet.domain.ProviderPlayerWeekPointsEvidence;

import java.util.Comparator;
import java.util.List;
import java.util.Objects;

/** Exact identity/provenance reconciliation with numeric BigDecimal equality for provider points. */
final class ProviderPointsEvidenceReconciler {
    private static final Comparator<ProviderPlayerWeekPointsEvidence> ORDER = Comparator
        .comparingInt(ProviderPlayerWeekPointsEvidence::week)
        .thenComparing(ProviderPlayerWeekPointsEvidence::teamId)
        .thenComparing(ProviderPlayerWeekPointsEvidence::providerPlayerId);

    private ProviderPointsEvidenceReconciler() {}

    static void reconcile(
        List<ProviderPlayerWeekPointsEvidence> expected,
        List<ProviderPlayerWeekPointsEvidence> actual) {
        Objects.requireNonNull(expected, "expected must not be null");
        Objects.requireNonNull(actual, "actual must not be null");

        if (expected.size() != actual.size()) {
            throw new IllegalStateException(
                "Provider-points read-back reconciliation failed: expected=" + expected.size()
                    + " readBack=" + actual.size());
        }
        assertDeterministicOrder(actual);

        for (int index = 0; index < expected.size(); index++) {
            var left = expected.get(index);
            var right = actual.get(index);
            String mismatch = mismatch(left, right);
            if (mismatch != null) {
                throw new IllegalStateException(
                    "Provider-points read-back reconciliation failed at row " + index
                        + " key=" + key(left) + " mismatch=" + mismatch);
            }
        }
    }

    private static void assertDeterministicOrder(List<ProviderPlayerWeekPointsEvidence> rows) {
        for (int index = 1; index < rows.size(); index++) {
            if (ORDER.compare(rows.get(index - 1), rows.get(index)) > 0) {
                throw new IllegalStateException(
                    "Provider-points read-back reconciliation failed: non-deterministic read-back order at row "
                        + index + " previous=" + key(rows.get(index - 1)) + " current=" + key(rows.get(index)));
            }
        }
    }

    private static String mismatch(
        ProviderPlayerWeekPointsEvidence expected,
        ProviderPlayerWeekPointsEvidence actual) {
        if (!Objects.equals(expected.id(), actual.id())) return values("id", expected.id(), actual.id());
        if (!Objects.equals(expected.leagueId(), actual.leagueId())) return values("leagueId", expected.leagueId(), actual.leagueId());
        if (!Objects.equals(expected.teamId(), actual.teamId())) return values("teamId", expected.teamId(), actual.teamId());
        if (!Objects.equals(expected.providerRosterId(), actual.providerRosterId())) return values("providerRosterId", expected.providerRosterId(), actual.providerRosterId());
        if (!Objects.equals(expected.providerLeagueId(), actual.providerLeagueId())) return values("providerLeagueId", expected.providerLeagueId(), actual.providerLeagueId());
        if (expected.season() != actual.season()) return values("season", expected.season(), actual.season());
        if (expected.week() != actual.week()) return values("week", expected.week(), actual.week());
        if (!Objects.equals(expected.providerPlayerId(), actual.providerPlayerId())) return values("providerPlayerId", expected.providerPlayerId(), actual.providerPlayerId());
        if (expected.points().compareTo(actual.points()) != 0) return values("points", expected.points().toPlainString(), actual.points().toPlainString());
        if (!Objects.equals(expected.source(), actual.source())) return values("source", expected.source(), actual.source());
        if (!Objects.equals(expected.sourceSurface(), actual.sourceSurface())) return values("sourceSurface", expected.sourceSurface(), actual.sourceSurface());
        if (!Objects.equals(expected.asOfDate(), actual.asOfDate())) return values("asOfDate", expected.asOfDate(), actual.asOfDate());
        return null;
    }

    private static String key(ProviderPlayerWeekPointsEvidence row) {
        return row.week() + "/" + row.teamId() + "/" + row.providerPlayerId();
    }

    private static String values(String field, Object expected, Object actual) {
        return field + " expected=" + expected + " actual=" + actual;
    }
}
