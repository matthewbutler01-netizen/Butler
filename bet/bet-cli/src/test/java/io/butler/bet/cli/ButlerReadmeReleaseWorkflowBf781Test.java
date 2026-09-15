package io.butler.bet.cli;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ButlerReadmeReleaseWorkflowBf781Test {

    @Test
    void readmeDocumentsCurrentGovernedWindowsReleasePath() throws Exception {
        String readme = source("README.md");

        assertTrue(readme.contains("## Windows Runtime and Release Quick Start"));
        assertTrue(readme.contains("Java 25"));
        assertTrue(readme.contains("%LOCALAPPDATA%\\Butler\\data"));
        assertTrue(readme.contains("BUTLER_APP_DATA_DIR"));
        assertTrue(readme.contains(".\\scripts\\butler-release-acceptance.cmd"));
        assertTrue(readme.contains("BF-780 RELEASE SELF-VERIFICATION: PASS"));
        assertTrue(readme.contains("release-output\\"));
        assertTrue(readme.contains("Butler-release-<shortsha>.verified.txt"));
        assertTrue(readme.contains("### Run a packaged release"));
        assertTrue(readme.contains(".\\scripts\\butler-app.cmd"));
        assertTrue(readme.contains("butler-release-verification-check.ps1"));
        assertTrue(readme.contains("historical release after repository HEAD has advanced"));
    }

    @Test
    void readmePreservesReleaseSafetyBoundaries() throws Exception {
        String readme = source("README.md");

        assertTrue(readme.contains("does not submit exact POST `/refresh`"));
        assertTrue(readme.contains("does not execute a Butler or Sleeper transaction write"));
        assertTrue(readme.contains("does not contain the Butler database, credentials, provider payloads, or user runtime data"));
        assertTrue(readme.contains("does not contain the Gradle wrapper or Gradle toolchain"));
        assertTrue(readme.contains("Runtime data remains external"));
    }

    @Test
    void readmeNoLongerInstructsOperatorsToGenerateGradleWrapper() throws Exception {
        String readme = source("README.md");

        assertFalse(readme.contains("Bootstrap project for Butler Forge."));
        assertFalse(readme.contains("Generate the Gradle Wrapper:"));
        assertFalse(readme.contains("\ngradle wrapper\n"));
    }

    @Test
    void existingLeagueDocumentationRemainsPresent() throws Exception {
        String readme = source("README.md");

        assertTrue(readme.contains("## League Intelligence Quick Start"));
        assertTrue(readme.contains("### Player evidence foundations"));
        assertTrue(readme.contains("## External fantasy-football data"));
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
        throw new IOException("BF-781 test could not locate " + relativePath);
    }
}
