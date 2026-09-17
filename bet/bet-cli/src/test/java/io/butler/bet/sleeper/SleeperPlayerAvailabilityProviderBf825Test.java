package io.butler.bet.sleeper;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

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
    void absentExactPlayerIdReturnsNoAvailabilityEvidence() throws Exception {
        var provider = new SleeperPlayerAvailabilityProvider(
            () -> "{\"other\":{\"status\":\"Inactive\"}}",
            Clock.fixed(Instant.parse("2026-09-17T04:00:00Z"), ZoneOffset.UTC),
            Duration.ofMinutes(5),
            new ObjectMapper());

        assertTrue(provider.load(Set.of("target")).isEmpty());
    }
}
