package io.butler.bet.cli;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ButlerCodeOnlyReleaseBundleBf769Test {

    @Test
    void builderArchivesCommittedHeadInsteadOfRecursivelyZippingTheWorkingTree() throws Exception {
        String script = source("scripts/butler-release-bundle.ps1");

        assertTrue(script.contains("'rev-parse', '--verify', 'HEAD^{commit}'"));
        assertTrue(script.contains("& $git 'archive' '--format=zip'"));
        assertTrue(script.contains("'HEAD'"));
        assertTrue(script.contains("Butler-source-$shortSha.zip"));
        assertFalse(script.contains("Compress-Archive"));
        assertFalse(script.contains("Copy-Item -Recurse"));
        assertFalse(script.contains("Get-ChildItem -Recurse"));
    }

    @Test
    void archiveAuditRequiresLaunchBuildAndWrapperEntrypoints() throws Exception {
        String script = source("scripts/butler-release-bundle.ps1");

        for (String required : new String[] {
                "README.md",
                "SECURITY.md",
                "build.gradle.kts",
                "settings.gradle.kts",
                "gradle.properties",
                "gradlew",
                "gradlew.bat",
                "gradle/wrapper/gradle-wrapper.jar",
                "gradle/wrapper/gradle-wrapper.properties",
                "bet/bet-cli/build.gradle.kts",
                "scripts/butler-app.cmd",
                "scripts/butler-app.ps1",
                "scripts/butler-acceptance.cmd"
        }) {
            assertTrue(script.contains("'" + required + "'"), "missing required archive guard for " + required);
        }
    }

    @Test
    void archiveAuditRejectsRuntimeSecretsGeneratedStateAndPersonalDatabase() throws Exception {
        String script = source("scripts/butler-release-bundle.ps1");

        assertTrue(script.contains("$name -ceq 'butler.db'"));
        assertTrue(script.contains(".db-journal"));
        assertTrue(script.contains(".db-shm"));
        assertTrue(script.contains(".db-wal"));
        assertTrue(script.contains(".db.init.lock"));
        assertTrue(script.contains("$name -ceq '.env'"));
        assertTrue(script.contains("$name -cne '.env.example'"));
        assertTrue(script.contains(".pem"));
        assertTrue(script.contains(".key"));
        assertTrue(script.contains(".p12"));
        assertTrue(script.contains(".pfx"));
        assertTrue(script.contains(".jks"));
        assertTrue(script.contains(".keystore"));
        assertTrue(script.contains("$segments -contains '.git'"));
        assertTrue(script.contains("$segments -contains '.gradle'"));
        assertTrue(script.contains("$segments -contains '.idea'"));
        assertTrue(script.contains("$segments -contains 'build'"));
        assertTrue(script.contains("$segments -contains 'release-output'"));
    }

    @Test
    void releaseOutputIsChecksummedManifestedAndIgnored() throws Exception {
        String script = source("scripts/butler-release-bundle.ps1");
        String ignore = source(".gitignore");

        assertTrue(script.contains("Get-FileHash -LiteralPath $artifactPath -Algorithm SHA256"));
        assertTrue(script.contains("BUTLER_RELEASE_MANIFEST_V1"));
        assertTrue(script.contains("boundary=CODE_ONLY_NO_RUNTIME_DATA"));
        assertTrue(script.contains("source=git archive HEAD"));
        assertTrue(script.contains("standalone_runtime=NOT_YET_VERIFIED_BF770_REQUIRED"));
        assertTrue(script.contains("BF-769 RELEASE BUNDLE: PASS"));
        assertTrue(ignore.contains("release-output/"));
    }

    @Test
    void bf769WindowsSourceRemainsAsciiOnly() throws Exception {
        assertAscii(source("scripts/butler-release-bundle.ps1"));
    }

    private static void assertAscii(String text) {
        byte[] encoded = text.getBytes(StandardCharsets.US_ASCII);
        assertEquals(text, new String(encoded, StandardCharsets.US_ASCII));
    }

    private static String source(String relativePath) throws IOException {
        Path current = Path.of(System.getProperty("user.dir")).toAbsolutePath().normalize();
        for (int depth = 0; depth < 7 && current != null; depth++) {
            Path candidate = current.resolve(relativePath);
            if (Files.isRegularFile(candidate)) {
                return Files.readString(candidate, StandardCharsets.US_ASCII);
            }
            current = current.getParent();
        }
        throw new IOException("BF-769 test could not locate " + relativePath);
    }
}
