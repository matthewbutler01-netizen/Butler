package io.butler.bet.cli;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ButlerAppShellBf709WindowsStabilizationTest {

    @Test
    void windowsWorkflowCoversTheWholeButlerScriptSurface() throws Exception {
        String workflow = source(".github/workflows/windows-powershell-parse.yml");

        assertTrue(workflow.contains("runs-on: windows-latest"));
        assertTrue(workflow.contains("'scripts/**/*.ps1'"));
        assertTrue(workflow.contains("'scripts/**/*.cmd'"));
        assertTrue(workflow.contains("Get-ChildItem '.\\scripts' -Recurse -Filter '*.ps1' -File"));
        assertTrue(workflow.contains("[System.Management.Automation.Language.Parser]::ParseFile"));
        assertTrue(workflow.contains("Exercise BF-707 staging transform under Windows PowerShell 5.1"));
        assertTrue(workflow.contains("$matchCount = [regex]::Matches($coreText, [regex]::Escape($original)).Count"));
        assertTrue(workflow.contains("BF-709 BLOCKED: BF-707 single-match staging guard has returned."));
        assertFalse(workflow.contains("paths:\n      - 'scripts/butler-dashboard.ps1'"));
    }

    @Test
    void windowsWorkflowRemainsAsciiOnly() throws Exception {
        String workflow = source(".github/workflows/windows-powershell-parse.yml");
        assertTrue(workflow.chars().allMatch(ch -> ch <= 127));
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
        throw new IOException("BF-709 test could not locate " + relativePath);
    }
}
