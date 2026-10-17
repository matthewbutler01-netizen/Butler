package io.butler.bet.sleeper;

import com.sun.net.httpserver.HttpServer;
import io.butler.bet.intelligence.NflversePlayerWeekProductionImporter;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class SharedLineupResourceDownloadsTest {
    private static final String SCHEDULE =
        "game_id,season,game_type,week,home_team,away_team,home_score,away_score,gameday,gametime\n"
        + "g1,2026,REG,3,DEN,NYJ,21,14,2026-09-20,13:00\n"
        + "g2,2026,REG,4,KC,DEN,,,2026-09-27,13:00\n";
    private static final String STATS =
        "player_id,season,season_type,game_id,team,opponent_team,position,passing_yards,rushing_yards,receiving_yards,passing_tds,rushing_tds,receiving_tds\n"
        + "qb,2026,REG,g1,NYJ,DEN,QB,250,10,0,2,0,0\n"
        + "wr,2026,REG,g1,NYJ,DEN,WR,0,5,100,0,0,1\n";
    private static final List<SleeperLiveWaiverTargetRosterContextAudit.TargetPlayer> PLAYERS = List.of(
        new SleeperLiveWaiverTargetRosterContextAudit.TargetPlayer(
            "1", "BENCH", null, null, "p", "Synthetic player", "WR", "KC", "EXACT"));

    @Test
    void scheduleAndStatsAreDownloadedOnceAndIndependentlyValidated() throws Exception {
        try (var fixture = new Fixture()) {
            // Usage reads this same stats resource before matchup composition.
            assertEquals(STATS, fixture.source.download(NflversePlayerWeekProductionImporter.statsUri(2026)));
            var locks = new NflverseGameLockProvider(fixture.source).load(2026, 4, PLAYERS);
            var matchups = new NflverseDefensiveMatchupProvider(fixture.source).load(2026, 4, PLAYERS);
            assertTrue(locks.get("1").verified());
            assertTrue(matchups.get("1").contains("week 4 vs DEN"));
            assertTrue(matchups.get("1").contains("5 rushing yards, 100 receiving yards, 1 offensive TDs"));
            assertEquals(1, fixture.scheduleRequests.get(), "lock and matchup must share the successful schedule fetch");
            assertEquals(1, fixture.statsRequests.get(), "matchup must reuse stats already read for usage");
            // Reusing the resource must not reuse an interpretation for another week.
            assertFalse(new NflverseGameLockProvider(fixture.source).load(2026, 5, PLAYERS).get("1").verified());
            assertTrue(new NflverseDefensiveMatchupProvider(fixture.source).load(2026, 5, PLAYERS)
                .get("1").contains("no unique scheduled NFL opponent"));
            assertEquals(1, fixture.scheduleRequests.get());
            assertEquals(1, fixture.statsRequests.get());
        }
    }

    @Test
    void failedStatsStayUnavailableAndCanBeFetchedOnLaterAttempt() throws Exception {
        try (var fixture = new Fixture()) {
            fixture.failStats.set(true);
            var matchups = new NflverseDefensiveMatchupProvider(fixture.source);
            assertThrows(IOException.class, () -> matchups.load(2026, 4, PLAYERS));
            fixture.failStats.set(false);
            assertTrue(matchups.load(2026, 4, PLAYERS).get("1").contains("100 receiving yards"));
            assertEquals(2, fixture.statsRequests.get(), "a failed fetch must not become cached evidence");
            assertEquals(1, fixture.scheduleRequests.get());
        }
    }

    private static final class Fixture implements AutoCloseable {
        final AtomicInteger scheduleRequests = new AtomicInteger();
        final AtomicInteger statsRequests = new AtomicInteger();
        final AtomicBoolean failStats = new AtomicBoolean();
        final HttpServer server;
        final NflverseRosterUsageProvider.TextSource source;

        Fixture() throws IOException {
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext("/schedule", exchange -> {
                scheduleRequests.incrementAndGet();
                var bytes = SCHEDULE.getBytes(StandardCharsets.UTF_8);
                exchange.sendResponseHeaders(200, bytes.length);
                try (var body = exchange.getResponseBody()) { body.write(bytes); }
            });
            server.createContext("/stats", exchange -> {
                statsRequests.incrementAndGet();
                var bytes = STATS.getBytes(StandardCharsets.UTF_8);
                exchange.sendResponseHeaders(failStats.get() ? 403 : 200, bytes.length);
                try (var body = exchange.getResponseBody()) { body.write(bytes); }
            });
            server.start();
            var cache = new NflverseRosterUsageProvider();
            String base = "http://127.0.0.1:" + server.getAddress().getPort();
            source = uri -> {
                if (NflverseDefensiveMatchupProvider.SCHEDULE_URI.equals(uri)) return cache.download(URI.create(base + "/schedule"));
                if (NflversePlayerWeekProductionImporter.statsUri(2026).equals(uri)) return cache.download(URI.create(base + "/stats"));
                throw new IOException("Unexpected fixture resource");
            };
        }

        public void close() { server.stop(0); }
    }
}
