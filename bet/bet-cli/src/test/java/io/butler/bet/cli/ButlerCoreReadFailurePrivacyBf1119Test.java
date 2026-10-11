package io.butler.bet.cli;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ButlerCoreReadFailurePrivacyBf1119Test {

    @Test
    void innerCoreCannotRenderRawRunnerExceptionsForManagerPages() throws Exception {
        String inner = source("scripts/butler-app-shell-core-single.ps1");

        assertTrue(inner.contains("Butler League view unavailable"));
        assertTrue(inner.contains("Butler My Team view unavailable"));
        assertTrue(inner.contains("Butler read unavailable"));
        assertTrue(inner.contains("Rankings and actions are not verified by this response."));
        assertTrue(inner.contains("Lineup and injury information are not verified by this response."));
        assertTrue(inner.contains("Do not rely on lineup or waiver advice from this response."));
        assertTrue(inner.contains("href=\"/league\""));
        assertTrue(inner.contains("href=\"/team\""));
        assertTrue(inner.contains("href=\"/\""));
        assertTrue(inner.contains("No Sleeper transaction was submitted by this page."));
        assertFalse(inner.contains("No Butler or Sleeper write was executed."));
        assertFalse(inner.contains("$(ConvertTo-HtmlText $_.Exception.Message)"));
        assertFalse(inner.contains("<pre>$message</pre>"));
        assertTrue(inner.contains("-StatusCode 500"));
        assertTrue(inner.contains("$parts[0] -ne \"GET\""));
    }

    @Test
    void innerCorePoolGatewayUsesFixedErrorCopyAndPreserves502() throws Exception {
        String proxy = source("scripts/butler-app-core-pool-worker-impl.ps1");
        assertTrue(proxy.contains("Butler inner core unavailable"));
        assertTrue(proxy.contains("The local Butler read could not finish."));
        assertTrue(proxy.contains("No Sleeper transaction was submitted by this page."));
        assertTrue(proxy.contains("href=\"/\""));
        assertTrue(proxy.contains("-StatusCode 502 -StatusText 'Bad Gateway'"));
        assertFalse(proxy.contains("HtmlEncode($_.Exception.Message)"));
        assertFalse(proxy.contains("<pre>$message</pre>"));
        assertFalse(proxy.contains("No Butler or Sleeper write was executed."));
        assertTrue(proxy.contains("$request.Method = 'GET'"));
        assertTrue(proxy.contains("$request.AllowAutoRedirect = $false"));
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
        throw new IOException("BF-1119 test could not find " + relativePath);
    }
}
