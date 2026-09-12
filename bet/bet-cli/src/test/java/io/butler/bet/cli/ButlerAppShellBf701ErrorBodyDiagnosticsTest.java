package io.butler.bet.cli;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ButlerAppShellBf701ErrorBodyDiagnosticsTest {

    @Test
    void loadCheckPreservesBoundedNonSuccessResponseBody() throws Exception {
        String script = script("scripts/butler-read-load-check.ps1");

        assertTrue(script.contains("$webExceptionMessage = $_.Exception.Message"));
        assertTrue(script.contains("$errorReader = [System.IO.StreamReader]::new($response.GetResponseStream(), [System.Text.Encoding]::UTF8)"));
        assertTrue(script.contains("$errorBody = $errorReader.ReadToEnd()"));
        assertTrue(script.contains("$errorBody = [regex]::Replace($errorBody, '\\s+', ' ').Trim()"));
        assertTrue(script.contains("if ($errorBody.Length -gt 512)"));
        assertTrue(script.contains("$errorBody = $errorBody.Substring(0, 512) + '...'"));
        assertTrue(script.contains("$errorText = $webExceptionMessage + '; body=' + $errorBody"));

        int webExceptionCatch = script.indexOf("catch [System.Net.WebException]");
        int bodyRead = script.indexOf("$errorBody = $errorReader.ReadToEnd()", webExceptionCatch);
        int responseClose = script.indexOf("if ($null -ne $response) { $response.Close() }", bodyRead);
        assertTrue(webExceptionCatch >= 0);
        assertTrue(bodyRead > webExceptionCatch);
        assertTrue(responseClose > bodyRead);
    }

    @Test
    void loadCheckKeepsReadOnlyWorkloadAndRefreshGuard() throws Exception {
        String script = script("scripts/butler-read-load-check.ps1");

        assertTrue(script.contains("$paths = @('/health', '/', '/team', '/waivers', '/league', '/trade', '/history')"));
        assertTrue(script.contains("if ($paths -contains '/refresh')"));
        assertTrue(script.contains("$request.Method = 'GET'"));
        assertTrue(script.contains("$request.Proxy = $null"));
        assertTrue(script.contains("$request.KeepAlive = $false"));
        assertFalse(script.contains("POST /refresh"));
        assertFalse(script.contains("create_transaction"));
        assertFalse(script.contains("submitTransaction"));
    }

    @Test
    void bf701ScriptRemainsAsciiOnly() throws Exception {
        String script = script("scripts/butler-read-load-check.ps1");
        byte[] encoded = script.getBytes(StandardCharsets.US_ASCII);
        assertEquals(script, new String(encoded, StandardCharsets.US_ASCII));
    }

    private static String script(String relativePath) throws IOException {
        Path current = Path.of(System.getProperty("user.dir")).toAbsolutePath().normalize();
        for (int depth = 0; depth < 7 && current != null; depth++) {
            Path candidate = current.resolve(relativePath);
            if (Files.isRegularFile(candidate)) {
                return Files.readString(candidate, StandardCharsets.US_ASCII);
            }
            current = current.getParent();
        }
        throw new IOException("BF-701 test could not locate " + relativePath);
    }
}
