package io.butler.bet.cli;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ButlerDashboardBf650AsciiTest {

    @Test
    void dashboardSourceRemainsAsciiOnlyForWindowsPowerShell51() throws Exception {
        Path path = locateScript();
        byte[] bytes = Files.readAllBytes(path);
        for (int i = 0; i < bytes.length; i++) {
            int value = bytes[i] & 0xff;
            assertTrue(value <= 0x7f, "BF-650 non-ASCII byte at offset " + i + ": " + value);
        }

        String script = new String(bytes, StandardCharsets.US_ASCII);
        assertFalse(script.contains("\u00b7"));
        assertTrue(script.contains("Starter - $($Player.LineupSlot)"));
        assertTrue(script.contains("Already-audited current ADD &middot; this marker is not a board rank."));
        assertTrue(script.contains("Newcomer review lane &middot; nonnumeric"));
        assertTrue(script.contains("READ ONLY &middot; NOT A RANKING."));
        assertTrue(script.contains("READ ONLY &middot; EXACT ID ONLY."));
    }

    private static Path locateScript() throws IOException {
        Path current = Path.of(System.getProperty("user.dir")).toAbsolutePath().normalize();
        for (int depth = 0; depth < 7 && current != null; depth++) {
            Path candidate = current.resolve("scripts/butler-dashboard.ps1");
            if (Files.isRegularFile(candidate)) return candidate;
            current = current.getParent();
        }
        throw new IOException("BF-650 test could not locate scripts/butler-dashboard.ps1");
    }
}
