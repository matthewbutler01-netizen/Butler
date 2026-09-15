package io.butler.bet.cli;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ButlerReleaseCandidateMetadataBf790Test {

    @Test
    void rootMetadataDeclaresRc2Exactly() throws Exception {
        String build = rootSource("build.gradle.kts");

        assertTrue(build.contains("version = \"0.1.0-rc.2\""));
        assertFalse(build.contains("version = \"0.1.0-rc.1\""));
        assertFalse(build.contains("0.1.0-SNAPSHOT"));
    }

    @Test
    void rc2DocumentExplainsBf789StabilizationAndPublicationBoundary() throws Exception {
        String doc = source("docs/release-candidate.md");

        assertTrue(doc.contains("Current candidate metadata: `v0.1.0-rc.2`"));
        assertTrue(doc.contains("`v0.1.0-rc.1` was the first public governed Butler release candidate"));
        assertTrue(doc.contains("BF-789 repairs that mismatch without weakening the package boundary"));
        assertTrue(doc.contains("GET /trade?load=1"));
        assertTrue(doc.contains("GET /history?load=1"));
        assertTrue(doc.contains("BF-789 PACKAGED COMPANION ROUTES: PASS"));
        assertTrue(doc.contains("Publishing `v0.1.0-rc.2` remains a separate explicit approval step"));
    }

    @Test
    void rc2DocumentPreservesGovernedSafetyBoundaries() throws Exception {
        String doc = source("docs/release-candidate.md");

        assertTrue(doc.contains("no Gradle wrapper or Gradle toolchain is restored to the runtime ZIP"));
        assertTrue(doc.contains("governed external `BUTLER_APP_DATA_DIR` behavior is preserved"));
        assertTrue(doc.contains("exact POST `/refresh` remains excluded"));
        assertTrue(doc.contains("no Butler or Sleeper transaction write is added"));
        assertTrue(doc.contains("does not create a Git tag"));
        assertTrue(doc.contains("create or modify a GitHub Release"));
    }

    private static String rootSource(String relativePath) throws IOException {
        Path current = Path.of(System.getProperty("user.dir")).toAbsolutePath().normalize();
        for (int depth = 0; depth < 7 && current != null; depth++) {
            if (Files.isRegularFile(current.resolve("settings.gradle.kts"))) {
                Path candidate = current.resolve(relativePath);
                if (Files.isRegularFile(candidate)) {
                    return Files.readString(candidate, StandardCharsets.UTF_8);
                }
            }
            current = current.getParent();
        }
        throw new IOException("BF-790 test could not locate repository-root " + relativePath);
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
        throw new IOException("BF-790 test could not locate " + relativePath);
    }
}
