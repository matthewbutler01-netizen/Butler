package io.butler.bet.cli;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertTrue;

class ButlerPackagedFirstLaunchDocsBf783Test {

    @Test
    void readmeDocumentsFirstAndSubsequentPackagedLaunches() throws Exception {
        String readme = source("README.md");

        assertTrue(readme.contains("the first launch must supply the Butler league id"));
        assertTrue(readme.contains(".\\scripts\\butler-app.cmd -LeagueId <butler-league-id>"));
        assertTrue(readme.contains("Butler's exact league UUID, not the Sleeper league id"));
        assertTrue(readme.contains("%LOCALAPPDATA%\\Butler\\app-league.txt"));
        assertTrue(readme.contains("Once a league is configured, subsequent launches use the saved selection"));
        assertTrue(readme.contains(".\\scripts\\butler-app.cmd\n```"));
    }

    @Test
    void readmeDocumentsIntentionalLeagueChangeWithoutChangingLauncherContract() throws Exception {
        String readme = source("README.md");

        assertTrue(readme.contains(".\\scripts\\butler-app.cmd -ResetLeague"));
        assertTrue(readme.contains(".\\scripts\\butler-app.cmd -LeagueId <new-butler-league-id>"));
        assertTrue(readme.contains("Runtime data remains external at `%LOCALAPPDATA%\\Butler\\data`"));
        assertTrue(readme.contains("Java 25 or newer available to the host."));
        assertTrue(readme.contains("does not execute a Butler or Sleeper transaction write"));
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
        throw new IOException("BF-783 test could not locate " + relativePath);
    }
}
