package io.butler.bet.cli;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ButlerReleaseCandidateMetadataBf788Test {

    @Test
    void gradleMetadataDeclaresReleaseCandidate() throws Exception {
        String build = rootSource("build.gradle.kts");

        assertTrue(build.contains("version = \"0.1.0-rc."));
        assertFalse(build.contains("0.1.0-SNAPSHOT"));
    }

    @Test
    void readmeDocumentsCurrentGovernedReleaseGate() throws Exception {
        String readme = source("README.md");

        int packaged = readme.indexOf("prebuilt read-runtime bundle with no runtime data or Gradle toolchain (BF-773)");
        int missingDatabase = readme.indexOf("isolated BF-786 missing-runtime-database probe");
        int windowsAcceptance = readme.indexOf("existing Butler Windows acceptance and diagnostics");
        int record = readme.indexOf("BF-777 release verification record");
        int verifier = readme.indexOf("offline BF-778 verifier");
        int evidence = readme.indexOf("BF-787 portable release-evidence archive");

        assertTrue(packaged >= 0);
        assertTrue(missingDatabase > packaged);
        assertTrue(windowsAcceptance > missingDatabase);
        assertTrue(record > windowsAcceptance);
        assertTrue(verifier > record);
        assertTrue(evidence > verifier);
        assertTrue(readme.contains("BF-780 RELEASE SELF-VERIFICATION: PASS"));
        assertTrue(readme.contains("BF-787 RELEASE EVIDENCE ARCHIVE: PASS"));
    }

    @Test
    void readmeListsPortableEvidenceOutputsAndBoundaryDoc() throws Exception {
        String readme = source("README.md");

        assertTrue(readme.contains("Butler-release-evidence-<shortsha>.zip"));
        assertTrue(readme.contains("Butler-release-evidence-<shortsha>.zip.sha256"));
        assertTrue(readme.contains("docs/release-evidence.md"));
        assertTrue(readme.contains("does not add runtime data or publish anything"));
        assertTrue(readme.contains("does not submit exact POST `/refresh`"));
        assertTrue(readme.contains("does not execute a Butler or Sleeper transaction write"));
    }

    @Test
    void detailedEvidenceDocStillRejectsPublicationAndRuntimeData() throws Exception {
        String evidence = source("docs/release-evidence.md");

        assertTrue(evidence.contains("BF-787 RELEASE EVIDENCE ARCHIVE: PASS"));
        assertTrue(evidence.contains("does not upload anything, create a Git tag, or create a GitHub Release"));
        assertTrue(evidence.contains("contains no Butler database, credentials, provider payloads, logs, or user runtime data"));
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
        throw new IOException("BF-788 test could not locate repository-root " + relativePath);
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
        throw new IOException("BF-788 test could not locate " + relativePath);
    }
}
