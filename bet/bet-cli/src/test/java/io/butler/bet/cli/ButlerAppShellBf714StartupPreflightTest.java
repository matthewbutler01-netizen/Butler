package io.butler.bet.cli;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ButlerAppShellBf714StartupPreflightTest {

    @Test
    void acceptanceRunsReadOnlyRuntimePreflightBeforeLaunchingButler() throws Exception {
        String wrapper = source("scripts/butler-acceptance.cmd");

        int preflight = wrapper.indexOf("butler-acceptance-preflight.ps1");
        int acceptance = wrapper.indexOf("butler-acceptance.ps1");
        assertTrue(preflight >= 0 && acceptance > preflight);
        assertTrue(wrapper.contains("if errorlevel 1 exit /b %ERRORLEVEL%"));
        assertTrue(wrapper.contains("%*"));
    }

    @Test
    void preflightRetriesOnlyExactKnownGeneratedClassDeletionFailure() throws Exception {
        String preflight = source("scripts/butler-acceptance-preflight.ps1");

        assertTrue(preflight.contains("':bet:bet-cli:installDist'"));
        assertTrue(preflight.contains("'--no-daemon'"));
        assertTrue(preflight.contains("bet\\bet-cli\\build\\classes\\java\\main"));
        assertTrue(preflight.contains("Unable to delete directory"));
        assertTrue(preflight.contains("Test-StaleGeneratedClassesFailure"));
        assertTrue(preflight.contains("Remove-Item -LiteralPath $generatedClasses -Recurse -Force -ErrorAction Stop"));
        assertEquals(2, occurrences(preflight, "$result = Invoke-InstallDist"));
        assertTrue(preflight.contains("read-only Butler runtime preflight failed"));
        assertTrue(preflight.contains("Get-BoundedTail"));
    }

    @Test
    void preflightTouchesOnlyGeneratedBuildOutputAndDoesNotExpandWrites() throws Exception {
        String preflight = source("scripts/butler-acceptance-preflight.ps1");

        assertFalse(preflight.contains("butler.db"));
        assertFalse(preflight.contains("/refresh"));
        assertFalse(preflight.contains("create_transaction"));
        assertFalse(preflight.contains("submitTransaction"));
        assertFalse(preflight.contains("Stop-Process"));
        assertFalse(preflight.contains("taskkill"));
        assertTrue(preflight.contains("build\\classes\\java\\main"));
        assertTrue(preflight.contains("build\\install\\bet-cli\\lib"));
    }

    @Test
    void startupPreflightAndWrapperRemainAsciiOnly() throws Exception {
        for (String path : new String[]{
            "scripts/butler-acceptance-preflight.ps1",
            "scripts/butler-acceptance.cmd"
        }) {
            String text = source(path);
            byte[] ascii = text.getBytes(StandardCharsets.US_ASCII);
            assertEquals(text, new String(ascii, StandardCharsets.US_ASCII));
        }
    }

    private static int occurrences(String text, String needle) {
        int count = 0;
        for (int at = 0; (at = text.indexOf(needle, at)) >= 0; at += needle.length()) count++;
        return count;
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
        throw new IOException("BF-714 test could not locate " + relativePath);
    }
}
