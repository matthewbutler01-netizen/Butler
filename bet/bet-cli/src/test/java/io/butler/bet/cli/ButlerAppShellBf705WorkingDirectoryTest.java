package io.butler.bet.cli;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ButlerAppShellBf705WorkingDirectoryTest {

    @Test
    void directJavaRestoresBetCliRelativeDatabaseSemantics() throws Exception {
        String dispatch = source("scripts/butler-direct-java-dispatch.ps1");
        String bundle = source("bet/bet-cli/src/main/java/io/butler/bet/cli/ButlerMyTeamEvidenceBundleCli.java");

        assertTrue(bundle.contains("Path.of(\"butler.db\")"));
        assertTrue(dispatch.contains("$workingDir = Join-Path $repoRoot 'bet\\bet-cli'"));
        assertTrue(dispatch.contains("Test-Path -LiteralPath $workingDir -PathType Container"));
        assertTrue(dispatch.contains("Push-Location $workingDir"));
        assertFalse(dispatch.contains("Push-Location $repoRoot"));
    }

    @Test
    void directJavaKeepsRuntimeReadOnlyAndMakesSqliteWarningExplicitlySupported() throws Exception {
        String dispatch = source("scripts/butler-direct-java-dispatch.ps1");

        assertTrue(dispatch.contains("--enable-native-access=ALL-UNNAMED"));
        assertTrue(dispatch.contains("interactive Gradle task is not authorized for direct Java execution"));
        assertFalse(dispatch.contains("/refresh"));
        assertFalse(dispatch.contains("create_transaction"));
        assertFalse(dispatch.contains("submitTransaction"));
    }

    @Test
    void bf705DispatcherRemainsAsciiOnly() throws Exception {
        String dispatch = source("scripts/butler-direct-java-dispatch.ps1");
        byte[] encoded = dispatch.getBytes(StandardCharsets.US_ASCII);
        assertEquals(dispatch, new String(encoded, StandardCharsets.US_ASCII));
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
        throw new IOException("BF-705 test could not locate " + relativePath);
    }
}
