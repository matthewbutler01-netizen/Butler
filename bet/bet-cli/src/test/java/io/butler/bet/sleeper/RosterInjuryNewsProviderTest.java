package io.butler.bet.sleeper;

import java.time.Instant;
import java.time.Clock;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.io.IOException;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
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
    void oneReviewSharesFailedFetchButNextReviewRetries() {
        var calls = new AtomicInteger();
        var provider = new RosterInjuryNewsProvider(() -> {
            calls.incrementAndGet(); throw new IOException("fixture feed outage");
        }, Clock.fixed(NOW, ZoneOffset.UTC));
        var review = provider.newReview();
        assertThrows(IOException.class, () -> review.load(List.of(PRICE)));
        assertThrows(IOException.class, () -> review.loadAnalysis(List.of(PRICE)));
        assertEquals(1, calls.get(), "injury and analysis must not each wait for the failed feed");
        assertThrows(IOException.class, () -> provider.newReview().loadAnalysis(List.of(PRICE)));
        assertEquals(2, calls.get(), "a failed review must not suppress the next review's attempt");
    }

    @Test
    void sameSuccessfulFeedSupportsDistinctInjuryAndAnalysisParsing() throws Exception {
        var calls = new AtomicInteger();
        var provider = new RosterInjuryNewsProvider(() -> {
            calls.incrementAndGet();
            return item("Jadarian Price practice fantasy outlook", "Wed, 30 Sep 2026 20:00:00 GMT", "https://www.espn.com/nfl/story");
        }, Clock.fixed(NOW, ZoneOffset.UTC));
        var review = provider.newReview();
        assertTrue(review.load(List.of(PRICE)).get("13286").contains("does not establish an official Out"));
        assertTrue(review.loadAnalysis(List.of(PRICE)).get("13286").contains("Public ESPN analysis"));
        assertEquals(1, calls.get());
    }

    @Test
    void expiredSuccessIsNotUsedWhenRefreshFails() throws Exception {
        var clock = new MutableClock();
        var calls = new AtomicInteger();
        var provider = new RosterInjuryNewsProvider(() -> {
            if (calls.incrementAndGet() > 1) throw new IOException("fixture outage after expiry");
            return item("Jadarian Price practice fantasy outlook", "Wed, 30 Sep 2026 20:00:00 GMT", "https://www.espn.com/nfl/story");
        }, clock);
        assertFalse(provider.newReview().load(List.of(PRICE)).isEmpty());
        clock.now = NOW.plusSeconds(301);
        var expired = provider.newReview();
        assertThrows(IOException.class, () -> expired.load(List.of(PRICE)));
        assertThrows(IOException.class, () -> expired.loadAnalysis(List.of(PRICE)));
        assertEquals(2, calls.get());
    }

    @Test
    void interruptedFeedIsNotRetriedInsideSameReview() {
        var calls = new AtomicInteger();
        var provider = new RosterInjuryNewsProvider(() -> {
            calls.incrementAndGet(); throw new InterruptedException("fixture interruption");
        }, Clock.fixed(NOW, ZoneOffset.UTC));
        var review = provider.newReview();
        assertThrows(InterruptedException.class, () -> review.load(List.of(PRICE)));
        assertThrows(InterruptedException.class, () -> review.loadAnalysis(List.of(PRICE)));
        assertEquals(1, calls.get());
    }

    private static final class MutableClock extends Clock {
        private Instant now = NOW;
        public ZoneId getZone() { return ZoneOffset.UTC; }
        public Clock withZone(ZoneId zone) { return this; }
        public Instant instant() { return now; }
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
    void publicAnalysisIsAttributedButNotInventedAsAnExpertPick() throws Exception {
        var news = RosterInjuryNewsProvider.parseAnalysis(item("Jadarian Price fantasy outlook",
            "Wed, 30 Sep 2026 20:00:00 GMT", "https://www.espn.com/nfl/story"), List.of(PRICE), NOW);
        assertTrue(news.get("13286").contains("Public ESPN analysis"));
        assertTrue(news.get("13286").contains("no expert start/sit pick has been extracted"));
        assertTrue(news.get("13286").contains("author=not supplied"));
    }

    @Test
    void externalEntitiesAreRejected() {
        assertThrows(java.io.IOException.class, () -> RosterInjuryNewsProvider.parse(
            "<!DOCTYPE rss [<!ENTITY x SYSTEM 'file:///etc/passwd'>]><rss>&x;</rss>", List.of(PRICE), NOW));
    }
}
