package io.butler.bet.sleeper;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SleeperLiveWaiverTargetRosterContextAuditBf732BatchPlayerTest {

    @Test
    void canonicalMappingUsesOneBoundedGlobalPlayerRead() throws Exception {
        String audit = source("bet/bet-cli/src/main/java/io/butler/bet/sleeper/SleeperLiveWaiverTargetRosterContextAudit.java");
        String repository = source("bet/bet-cli/src/main/java/io/butler/bet/data/PlayerRepository.java");

        assertTrue(audit.contains("new PlayerRepository(database).findByExternalIds(sleeperPlayerIds)"));
        assertTrue(audit.contains("exactCanonicalPlayers(target.playerIds())"));
        assertFalse(audit.contains("exactCanonicalPlayer(slot.sleeperPlayerId())"));
        assertFalse(audit.contains("WHERE external_id = ? ORDER BY id"));

        assertTrue(repository.contains("findByExternalIds(Collection<String> externalIds)"));
        assertTrue(repository.contains("WHERE external_id IN ("));
        assertTrue(repository.contains("ORDER BY external_id ASC, id ASC"));
    }

    @Test
    void liveRosterSafetyBoundariesRemainPresent() throws Exception {
        String audit = source("bet/bet-cli/src/main/java/io/butler/bet/sleeper/SleeperLiveWaiverTargetRosterContextAudit.java");

        assertTrue(audit.contains("source.league(frame.sleeperLeagueId())"));
        assertTrue(audit.contains("source.rosters(frame.sleeperLeagueId())"));
        assertTrue(audit.contains("source.users(frame.sleeperLeagueId())"));
        assertTrue(audit.contains("current roster membership drifted from BF-603/BF-602 frame"));
        assertTrue(audit.contains("exact Sleeper owner id resolves to"));
        assertTrue(audit.contains("duplicate exact Butler player mapping for Sleeper id"));
        assertTrue(audit.contains("new TeamRepository(database).findByExternalId(normalizedLeagueId, rosterExternalId)"));
    }

    private static String source(String relativePath) throws IOException {
        Path current = Path.of(System.getProperty("user.dir")).toAbsolutePath().normalize();
        for (int depth = 0; depth < 7 && current != null; depth++) {
            Path candidate = current.resolve(relativePath);
            if (Files.isRegularFile(candidate)) {
                return Files.readString(candidate, StandardCharsets.UTF_8);
            }
            current = current.getParent();
        }
        throw new IOException("BF-732 test could not locate " + relativePath);
    }
}
