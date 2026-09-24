package io.butler.bet.cli;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ButlerPlayerDetailPersistentWorkerBf916Test {

    @Test
    void playerDetailUsesExistingAuthenticatedPersistentWorkerRoute() throws Exception {
        String detail = source("scripts/butler-app-bf879-player-detail-transform.ps1");
        String sharedWorker = source("scripts/butler-core-bf742-transform.ps1");

        assertTrue(detail.contains(
                "$playerDetailPath = \"/__butler/internal/player-detail?player=\" + [System.Uri]::EscapeDataString($playerId)"));
        assertTrue(detail.contains(
                "Invoke-Bf742DashboardWorkerRead -Path $playerDetailPath -BoundaryName \"BF-916\""));
        assertFalse(detail.contains(
                "Invoke-ButlerReadOnly -Arguments \"league player-detail $LeagueId $playerId\""));

        assertTrue(sharedWorker.contains(
                "-not $Path.StartsWith(\"/__butler/internal/player-detail?\", [System.StringComparison]::Ordinal)"));
        assertTrue(sharedWorker.contains(
                "Invoke-Bf740PersistentCoreWorker -Operation 'PLAYER_DETAIL'"));
    }

    @Test
    void playerDetailStillValidatesExactLeagueAndPlayerResponse() throws Exception {
        String detail = source("scripts/butler-app-bf879-player-detail-transform.ps1");

        assertTrue(detail.contains("$playerDetail.LeagueId -cne $LeagueId"));
        assertTrue(detail.contains("$playerDetail.PlayerId -cne $playerId"));
        assertTrue(detail.contains("Get-PlayerDetailRequestId -RequestTarget $parts[1]"));
    }

    @Test
    void persistentRouteDoesNotAddWriteOrProviderBehavior() throws Exception {
        String detail = source("scripts/butler-app-bf879-player-detail-transform.ps1");
        String route = hereString(detail, "$playerRoute = @'", "'@\n\n$core = $core.Insert");

        assertFalse(route.contains("Method = \"POST\""));
        assertFalse(route.contains("submitTransaction"));
        assertFalse(route.contains("setFaab"));
        assertFalse(route.contains("https://api.sleeper.app"));
    }

    private static String hereString(String text, String startMarker, String endMarker) {
        int start = text.indexOf(startMarker);
        assertTrue(start >= 0, "start marker missing: " + startMarker);
        start += startMarker.length();
        int end = text.indexOf(endMarker, start);
        assertTrue(end > start, "end marker missing: " + endMarker);
        return text.substring(start, end);
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
        throw new IOException("BF-916 test could not locate " + relativePath);
    }
}
