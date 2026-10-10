package io.butler.bet.cli;

import org.junit.jupiter.api.Test;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertFalse;

class ButlerWeeklyMatchupLiveWeekBf1054Test {
    @Test
    void liveStateRequiresStrictRegularSeasonAndExactSeasonWeek() {
        String source = "{\"season\":\"2026\",\"season_type\":\"regular\",\"week\":5}";
        String match = ButlerWeeklyMatchupEvidenceBundleCli.renderWeekFreshness(2026, 5, source);
        assertTrue(match.contains("State: MATCH"));
        assertTrue(match.contains("Saved season/week: 2026/5"));
        assertTrue(match.contains("Provider season/week: 2026/5"));

        String staleWeek = ButlerWeeklyMatchupEvidenceBundleCli.renderWeekFreshness(2026, 4, source);
        String staleSeason = ButlerWeeklyMatchupEvidenceBundleCli.renderWeekFreshness(2025, 5, source);
        assertTrue(staleWeek.contains("State: MISMATCH"));
        assertTrue(staleSeason.contains("State: MISMATCH"));
        assertTrue(staleWeek.contains("Provider season/week: 2026/5"));
    }

    @Test
    void malformedOrUnreachablePublicStateNeverCertifiesFreshness() {
        for (String payload : new String[] {
            "", "not-json", "{}", "{\"season\":\"2026\",\"week\":5}",
            "{\"season\":\"2026\",\"season_type\":\"post\",\"week\":5}",
            "{\"season\":\"2026\",\"season_type\":\"regular\",\"week\":25}",
            "{\"season\":\"2026\",\"season_type\":\"regular\",\"week\":\"5\"}",
            "{\"season\":\"2026\",\"season_type\":\"regular\",\"week\":5"
        }) {
            String text = ButlerWeeklyMatchupEvidenceBundleCli.renderWeekFreshness(2026, 5, payload);
            assertTrue(text.contains("State: UNVERIFIED"), payload);
            assertFalse(text.contains("State: MATCH"), payload);
        }
    }

    @Test
    void passivePageUsesOnlyReadOnlyPublicEndpointAndKeepsSavedPairingImmutable() throws IOException {
        String client = source("bet/bet-cli/src/main/java/io/butler/bet/sleeper/SleeperClient.java");
        String bundle = source("bet/bet-cli/src/main/java/io/butler/bet/cli/ButlerWeeklyMatchupEvidenceBundleCli.java");
        String renderer = source("scripts/butler-app-bf840-weekly-matchup-transform.ps1");
        assertTrue(client.contains("return get(\"state/nfl\", timeout)"));
        assertTrue(client.contains("timeout.compareTo(MAX_PREWARM_TIMEOUT) > 0"));
        assertTrue(bundle.contains("new SleeperClient().getNflState(Duration.ofSeconds(4))"));
        assertTrue(bundle.contains("Future<String> weekFuture"));
        assertTrue(bundle.contains("get(6, TimeUnit.SECONDS)"));
        assertTrue(bundle.contains("emit(WEEK_FRESHNESS, weekProof)"));
        assertTrue(renderer.contains("Get-TeamEvidenceBundleSection -Text $bundleText -Name \"WEEK_FRESHNESS\""));
        assertTrue(renderer.contains("if ($weekProof.State -ceq 'MISMATCH')"));
        assertTrue(renderer.contains("New-AutoFillIdleView"));
        assertTrue(renderer.contains("Add-MatchupPublicWeekNotice"));
        assertFalse(bundle.contains("importWeek("), "GET may not sync the persisted pairing");
    }

    private static String source(String name) throws IOException {
        Path cursor = Path.of(System.getProperty("user.dir")).toAbsolutePath();
        for (int i = 0; i < 7 && cursor != null; i++, cursor = cursor.getParent()) {
            Path candidate = cursor.resolve(name);
            if (Files.isRegularFile(candidate)) return Files.readString(candidate, StandardCharsets.UTF_8);
        }
        throw new IOException("BF-1054 could not load source " + name);
    }
}
