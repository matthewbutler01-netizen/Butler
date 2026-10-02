package io.butler.bet.sleeper;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import java.time.Instant;
import java.util.List;

class NflExpertPickProviderTest {
    private static final Instant NOW = Instant.parse("2026-10-01T07:00:00Z");
    private static final String META = "<script type=\"application/ld+json\">{\"@type\":\"NewsArticle\","
        + "\"url\":\"https://www.nfl.com/news/nfl-fantasy-2026-start-em-sit-em-wide-receivers-for-week-4\","
        + "\"headline\":\"NFL Fantasy 2026 Start 'Em, Sit 'Em: Wide receivers for Week 4\","
        + "\"author\":[{\"@type\":\"Person\",\"name\":\"Test Author\"}],"
        + "\"datePublished\":\"2026-09-30T17:00:00Z\",\"dateModified\":\"2026-09-30T18:00:00Z\"}</script>";
    private static final String HTML = META + "<h2>Start &#x27;Em</h2>"
        + "<a aria-label=\"View details for Test Receiver\" href=\"/players/test-receiver/\">Test Receiver</a>"
        + "<p>Other Receiver is mentioned with the word start.</p><h2>Sit &#x27;Em</h2>"
        + "<a aria-label=\"View details for Other Receiver\" href=\"/players/other-receiver/\">Other Receiver</a>";
    private static SleeperLiveWaiverTargetRosterContextAudit.TargetPlayer player(String id, String name) {
        return new SleeperLiveWaiverTargetRosterContextAudit.TargetPlayer(id, "BENCH", null, null, id, name, "WR", "KC", "EXACT");
    }
    private List<SleeperLiveAutoFillLineupRecommendation.ExpertPick> parse(String html,
        List<SleeperLiveWaiverTargetRosterContextAudit.TargetPlayer> players) throws Exception {
        return NflExpertPickProvider.parse(html, NflExpertPickProvider.source(2026,4,"WR"),2026,4,"WR",players,NOW);
    }
    @Test void extractsExplicitCardsWithAttribution() throws Exception {
        var picks = parse(HTML, List.of(player("1","Test Receiver"),player("2","Other Receiver")));
        assertEquals("START",picks.get(0).selection()); assertEquals("SIT",picks.get(1).selection());
        assertEquals("Test Author",picks.get(0).author());
        assertEquals("2026-09-30T17:00:00Z",picks.get(0).publishedAt());
    }
    @Test void bodyMentionsDoNotQualify() throws Exception {
        assertEquals("UNVERIFIED", parse(HTML.replace("Other Receiver is mentioned", "Missing Receiver is mentioned"),
            List.of(player("1","Missing Receiver"))).getFirst().selection());
    }
    @Test void ambiguousRosterNamesRemainUnverified() throws Exception {
        assertTrue(parse(HTML,List.of(player("1","Test Receiver"),player("2","Test Receiver"))).stream()
            .allMatch(p -> "UNVERIFIED".equals(p.selection())));
    }
    @Test void staleFutureWrongFrameAndMissingAuthorFailClosed() {
        for(String html:List.of(HTML.replace("2026-09-30T17", "2026-09-20T17"),
            HTML.replace("2026-09-30T18", "2026-10-02T18"),HTML.replace("for Week 4", "for Week 3"),
            HTML.replace("Test Author", ""), HTML.replace("Sit &#x27;Em", "Other content"))) {
            assertThrows(IllegalStateException.class, () -> parse(html,List.of(player("1","Test Receiver"))));
        }
    }
    @Test void scriptsAndRelatedContentDoNotQualify() throws Exception {
        String html = HTML + "<h2>Related Content</h2><a aria-label=\"View details for Missing Receiver\" href=\"/players/missing\">Missing</a>"
            + "<script><h2>Start 'Em</h2><a aria-label=\"View details for Missing Receiver\" href=\"/players/missing\">Missing</a></script>";
        assertEquals("UNVERIFIED",parse(html,List.of(player("1","Missing Receiver"))).getFirst().selection());
    }
    @Test void duplicateConflictingCardsFailClosed() {
        assertThrows(IllegalStateException.class,() -> parse(HTML.replace("Other Receiver", "Test Receiver"),List.of(player("1","Test Receiver"))));
    }
}
