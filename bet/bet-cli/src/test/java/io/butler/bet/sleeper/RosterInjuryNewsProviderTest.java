package io.butler.bet.sleeper;

import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class RosterInjuryNewsProviderTest {
    private static final Instant NOW = Instant.parse("2026-10-01T05:00:00Z");
    private static final SleeperLiveWaiverTargetRosterContextAudit.TargetPlayer PRICE =
        new SleeperLiveWaiverTargetRosterContextAudit.TargetPlayer(
            "13286", "BENCH", null, null, "price", "Jadarian Price", "RB", "SEA", "EXACT_CANONICAL");

    private static String item(String title, String date, String link) {
        return "<rss><channel><item><title>" + title + "</title><pubDate>" + date
            + "</pubDate><link>" + link + "</link></item></channel></rss>";
    }

    @Test
    void exactFreshInjuryHeadlineRetainsSourceAndTimeWithoutDeclaringOut() throws Exception {
        var news = RosterInjuryNewsProvider.parse(item("Jadarian Price limited at practice",
            "Wed, 30 Sep 2026 20:00:00 GMT", "https://www.espn.com/nfl/story/_/id/123"), List.of(PRICE), NOW);
        assertTrue(news.get("13286").contains("published=2026-09-30T20:00:00Z"));
        assertTrue(news.get("13286").contains("source=https://www.espn.com"));
        assertTrue(news.get("13286").contains("does not establish an official Out"));
    }

    @Test
    void staleAmbiguousAndWrongSourceHeadlinesCannotHoldPlayer() throws Exception {
        assertTrue(RosterInjuryNewsProvider.parse(item("Jadarian Price injury",
            "Fri, 25 Sep 2026 20:00:00 GMT", "https://www.espn.com/nfl/story"), List.of(PRICE), NOW).isEmpty());
        assertTrue(RosterInjuryNewsProvider.parse(item("Price injury",
            "Wed, 30 Sep 2026 20:00:00 GMT", "https://www.espn.com/nfl/story"), List.of(PRICE), NOW).isEmpty());
        assertTrue(RosterInjuryNewsProvider.parse(item("Jadarian Price injury",
            "Wed, 30 Sep 2026 20:00:00 GMT", "https://espn.com.evil.example/story"), List.of(PRICE), NOW).isEmpty());
        assertTrue(RosterInjuryNewsProvider.parse(item("Jadarian Price injury",
            "Wed, 30 Sep 2026 20:00:00 GMT", "https://www.espn.com/nfl/story"), List.of(PRICE, PRICE), NOW).isEmpty());
    }

    @Test
    void externalEntitiesAreRejected() {
        assertThrows(java.io.IOException.class, () -> RosterInjuryNewsProvider.parse(
            "<!DOCTYPE rss [<!ENTITY x SYSTEM 'file:///etc/passwd'>]><rss>&x;</rss>", List.of(PRICE), NOW));
    }
}
