package io.butler.bet.cli;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ButlerAppShellBf710ProxyArgumentsTest {

    @Test
    void proxyAndDispatcherPreserveSplitGradleArgsWithoutExpandingWriteSurface() throws Exception {
        String proxy = source("scripts/butler-direct-java-gradle-proxy.cmd");
        String dispatch = source("scripts/butler-direct-java-dispatch.ps1");
        String workflow = source(".github/workflows/windows-powershell-parse.yml");

        assertTrue(proxy.contains("-ArgumentRemainder \"%~3 %~4 %~5 %~6 %~7 %~8 %~9\""));
        assertTrue(dispatch.contains("[string]$ArgumentRemainder = ''"));
        assertTrue(dispatch.contains("if ($normalizedArguments -ceq '--args')"));
        assertTrue(dispatch.contains("$normalizedArguments = $remainder"));
        assertTrue(dispatch.contains("$normalizedArguments.StartsWith('--args=', [System.StringComparison]::Ordinal)"));
        assertTrue(dispatch.contains("Gradle-compatible --args token was split but no argument value followed it"));

        assertTrue(workflow.contains("Exercise BF-710 cmd proxy argument tokenization"));
        assertTrue(workflow.contains("'--args=league-123'"));
        assertTrue(workflow.contains("'--args=league-123 audit-456'"));
        assertTrue(workflow.contains("single-value --args tokenization lost the league id"));
        assertTrue(workflow.contains("multi-value --args tokenization changed governed arguments"));

        assertFalse(dispatch.contains("/refresh"));
        assertFalse(dispatch.contains("create_transaction"));
        assertFalse(dispatch.contains("submitTransaction"));
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
        throw new IOException("BF-710 test could not locate " + relativePath);
    }
}
