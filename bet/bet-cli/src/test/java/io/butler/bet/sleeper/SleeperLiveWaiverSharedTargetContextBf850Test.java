package io.butler.bet.sleeper;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SleeperLiveWaiverSharedTargetContextBf850Test {

    @Test
    void liveLoaderFetchesEachSharedProviderSurfaceExactlyOnce() throws Exception {
        String source = source(
            "bet/bet-cli/src/main/java/io/butler/bet/sleeper/SleeperLiveWaiverSharedTargetContext.java");

        assertEquals(1, occurrences(source, "client.getUser(username)"));
        assertEquals(1, occurrences(source, "client.getUserLeagues("));
        assertEquals(1, occurrences(source, "client.getLeague(leagueId)"));
        assertEquals(1, occurrences(source, "client.getLeagueRosters(leagueId)"));
        assertEquals(1, occurrences(source, "client.getLeagueUsers(leagueId)"));
        assertTrue(source.contains("implements SleeperPersonalizedTargetService.Source, SleeperLiveWaiverTargetRosterContextAudit.Source"));
        assertTrue(source.contains("new SleeperPersonalizedTargetService(database, targets, snapshot)"));
        assertTrue(source.contains("new SleeperLiveWaiverTargetRosterContextAudit(database, snapshot)"));
    }

    @Test
    void sharedSourceIsPinnedToOneExactUserLeagueFrame() {
        var source = new SleeperLiveWaiverSharedTargetContext.SnapshotSource(
            "manager",
            "owner-1",
            "league-1",
            "{\"user_id\":\"owner-1\"}",
            "[]",
            "{}",
            "[]",
            "[]");

        assertEquals("{\"user_id\":\"owner-1\"}", source.user("manager"));
        assertEquals("{\"user_id\":\"owner-1\"}", source.user("owner-1"));
        assertEquals("[]", source.userLeagues("owner-1", SleeperPersonalizedTargetService.TARGET_SEASON));
        assertEquals("{}", source.league("league-1"));
        assertEquals("[]", source.rosters("league-1"));
        assertEquals("[]", source.users("league-1"));

        assertThrows(IllegalStateException.class, () -> source.user("other"));
        assertThrows(IllegalStateException.class, () -> source.userLeagues("other", SleeperPersonalizedTargetService.TARGET_SEASON));
        assertThrows(IllegalStateException.class, () -> source.userLeagues("owner-1", 2025));
        assertThrows(IllegalStateException.class, () -> source.league("other-league"));
    }

    @Test
    void focusedAcceptanceMeasuresExactWaiverColdWarmPath() throws Exception {
        String acceptance = source("scripts/butler-waiver-shared-snapshot-acceptance.ps1");

        assertTrue(acceptance.contains("Cold Waivers:"));
        assertTrue(acceptance.contains("Warm Waivers:"));
        assertTrue(acceptance.contains("$root + '/waivers'"));
        assertTrue(acceptance.contains("Warm cache: VERIFIED"));
        assertTrue(acceptance.contains("Shared live snapshot boundary: PRESERVED"));
        assertFalse(acceptance.contains("/refresh"));
    }

    @Test
    void standaloneLiveSourcesRemainAvailableOutsideBundleComposition() throws Exception {
        String target = source(
            "bet/bet-cli/src/main/java/io/butler/bet/sleeper/SleeperPersonalizedTargetService.java");
        String roster = source(
            "bet/bet-cli/src/main/java/io/butler/bet/sleeper/SleeperLiveWaiverTargetRosterContextAudit.java");
        String comparison = source(
            "bet/bet-cli/src/main/java/io/butler/bet/sleeper/SleeperLiveWaiverCoalescedComparisonEvidence.java");

        assertTrue(target.contains("public SleeperPersonalizedTargetService(Database database)"));
        assertTrue(target.contains("new LiveSource()"));
        assertTrue(roster.contains("public SleeperLiveWaiverTargetRosterContextAudit(Database database)"));
        assertTrue(roster.contains("new LiveSource()"));
        assertTrue(comparison.contains("public CoalescedReport run(String leagueId, String sleeperOwnerId)"));
        assertTrue(comparison.contains("rosterContextSource.audit(requestedLeagueId, requestedOwnerId)"));
        assertTrue(comparison.contains("SleeperLiveWaiverTargetRosterContextAudit.AuditReport rosterContext"));
    }

    private static int occurrences(String text, String needle) {
        int count = 0;
        for (int at = 0; (at = text.indexOf(needle, at)) >= 0; at += needle.length()) count++;
        return count;
    }

    private static String source(String relativePath) throws Exception {
        Path current = Path.of(System.getProperty("user.dir")).toAbsolutePath().normalize();
        for (int depth = 0; depth < 7 && current != null; depth++) {
            Path candidate = current.resolve(relativePath);
            if (Files.isRegularFile(candidate)) {
                return Files.readString(candidate, StandardCharsets.UTF_8);
            }
            current = current.getParent();
        }
        throw new IllegalStateException("BF-850 test could not locate " + relativePath);
    }
}
