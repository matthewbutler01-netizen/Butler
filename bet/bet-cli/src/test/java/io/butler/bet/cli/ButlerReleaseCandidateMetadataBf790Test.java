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
    void rootMetadataDeclaresNextMaintenanceReleaseExactly() throws Exception {
        String build = rootSource("build.gradle.kts");

        assertTrue(build.contains("version = \"0.1.1\""));
        assertFalse(build.contains("version = \"0.1.0-rc."));
        assertFalse(build.contains("0.1.0-SNAPSHOT"));
    }

    @Test
    void releaseDocumentExplainsStableAndMaintenanceStatus() throws Exception {
        String doc = source("docs/release-candidate.md");

        assertTrue(doc.contains("current public stable release is `v0.1.0`"));
        assertTrue(doc.contains("6eefc53a75608ca5c97f18a6d0c6c966be783626"));
        assertTrue(doc.contains("next planned maintenance release is `v0.1.1`"));
        assertTrue(doc.contains("JUnit 6.1.3"));
        assertTrue(doc.contains("BF-534 seven-page UX guardrail"));
        assertTrue(doc.contains("exact-head BF-885/BF-912 Fast Lane journey"));
        assertTrue(doc.contains("Publishing a release remains a separate explicit operation"));
    }

    @Test
    void releaseDocumentPreservesGovernedSafetyBoundaries() throws Exception {
        String doc = source("docs/release-candidate.md");

        assertTrue(doc.contains("without a Gradle wrapper or toolchain"));
        assertTrue(doc.contains("external runtime-data boundary"));
        assertTrue(doc.contains("no Butler database"));
        assertTrue(doc.contains("authorize a Butler or Sleeper"));
        assertTrue(doc.contains("transaction write."));
        assertTrue(doc.contains("do not create a Git tag"));
        assertTrue(doc.contains("create or modify a GitHub"));
        assertTrue(doc.contains("Release, upload artifacts"));
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
