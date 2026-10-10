package io.butler.bet.sleeper;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SleeperPlayerAvailabilityProviderBf825Test {

    @Test
    void exactStatusesUseNarrowUnavailableAllowlistAndBoundedCache() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        String payload = """
            {
              "p-inactive": {"status":"Inactive","injury_status":null},
              "p-commissioner": {"status":"Commissioner_Exempt","injury_status":null},
              "p-active-out": {"status":"Active","injury_status":"Out"},
              "p-questionable": {"status":null,"injury_status":"Questionable"},
              "p-out": {"status":null,"injury_status":"Out"}
            }
            """;

        var provider = new SleeperPlayerAvailabilityProvider(
            () -> {
                calls.incrementAndGet();
                return payload;
            },
            Clock.fixed(Instant.parse("2026-09-17T04:00:00Z"), ZoneOffset.UTC),
            Duration.ofMinutes(5),
            new ObjectMapper());

        var first = provider.load(Set.of(
            "p-inactive", "p-commissioner", "p-active-out", "p-questionable", "p-out"));
        var second = provider.load(Set.of("p-out"));

        assertEquals(1, calls.get());
        assertTrue(first.get("p-inactive").explicitlyUnavailable());
        assertTrue(first.get("p-commissioner").explicitlyUnavailable());
        assertTrue(first.get("p-out").explicitlyUnavailable());
        assertFalse(first.get("p-active-out").explicitlyUnavailable());
        assertFalse(first.get("p-questionable").explicitlyUnavailable());
        assertEquals(Set.of("p-out"), second.keySet());
    }

    @Test
    void malformedOrDuplicateAvailabilityNeverCertifiesCurrentInjuryStatus() {
        var bad = new String[] {
            "{\"target\":{\"status\":\"Out\",\"status\":\"Active\"}}",
            "{\"target\":{\"injury_status\":\"Out\",\"injury_status\":\"Healthy\"}}",
            "{\"target\":{\"status\":\"Active\"},\"target\":{\"status\":\"Out\"}}",
            "{\"target\":{\"status\":\"Active\"}} {\"target\":{\"status\":\"Out\"}}"
        };
        for (String payload : bad) {
            var provider = new SleeperPlayerAvailabilityProvider(
                () -> payload,
                Clock.fixed(Instant.parse("2026-10-10T08:00:00Z"), ZoneOffset.UTC),
                Duration.ofMinutes(5),
                new ObjectMapper());
            assertThrows(java.io.IOException.class, () -> provider.load(Set.of("target")),
                "Conflicting injury evidence must never select the last status.");
        }
    }

    @Test
    void unknownOrMissingPlayerStatusCannotMasqueradeAsClearedInjury() {
        var active = new SleeperPlayerAvailabilityProvider.PlayerAvailability("p-active", "Active", null);
        var healthy = new SleeperPlayerAvailabilityProvider.PlayerAvailability("p-healthy", "Active", "Healthy");
        var unknown = new SleeperPlayerAvailabilityProvider.PlayerAvailability("p-unknown", "Reserved", null);
        var absent = new SleeperPlayerAvailabilityProvider.PlayerAvailability("p-absent", null, "Healthy");
        var questionable = new SleeperPlayerAvailabilityProvider.PlayerAvailability(
            "p-questionable", "Active", "Questionable");
        assertFalse(active.requiresInjuryReview());
        assertFalse(healthy.requiresInjuryReview());
        assertTrue(unknown.requiresInjuryReview());
        assertFalse(unknown.confirmedUnavailable(), "Unrecognized status is a hold, not a confirmed Out.");
        assertTrue(absent.requiresInjuryReview(), "Missing roster status cannot certify healthy.");
        assertTrue(questionable.requiresInjuryReview(), "Questionable requires a separate review.");
    }

    @Test
    void clockRollbackMustRefetchInsteadOfServingFutureDatedInjuryCache() throws Exception {
        AtomicReference<Instant> now = new AtomicReference<>(Instant.parse("2026-10-10T08:00:00Z"));
        Clock clock = new Clock() {
            @Override public ZoneId getZone() { return ZoneOffset.UTC; }
            @Override public Clock withZone(ZoneId zone) { return this; }
            @Override public Instant instant() { return now.get(); }
        };
        AtomicInteger calls = new AtomicInteger();
        var provider = new SleeperPlayerAvailabilityProvider(
            () -> {
                int attempt = calls.incrementAndGet();
                return attempt == 1
                    ? "{\"target\":{\"status\":\"Active\",\"injury_status\":null}}"
                    : "{\"target\":{\"status\":\"Inactive\",\"injury_status\":null}}";
            }, clock, Duration.ofMinutes(5), new ObjectMapper());
        assertFalse(provider.load(Set.of("target")).get("target").confirmedUnavailable());
        now.set(Instant.parse("2026-10-10T08:02:00Z"));
        assertFalse(provider.load(Set.of("target")).get("target").confirmedUnavailable());
        assertEquals(1, calls.get(), "Normal visits within 5 minutes share one source read.");
        now.set(Instant.parse("2026-10-10T07:59:00Z"));
        assertTrue(provider.load(Set.of("target")).get("target").confirmedUnavailable());
        assertEquals(2, calls.get(), "Clock moved backward: the old healthy-looking cache must be rejected.");
    }

    @Test
    void absentExactPlayerIdReturnsNoAvailabilityEvidence() throws Exception {
        var provider = new SleeperPlayerAvailabilityProvider(
            () -> "{\"other\":{\"status\":\"Inactive\"}}",
            Clock.fixed(Instant.parse("2026-09-17T04:00:00Z"), ZoneOffset.UTC),
            Duration.ofMinutes(5),
            new ObjectMapper());

        assertTrue(provider.load(Set.of("target")).isEmpty());
    }
}
