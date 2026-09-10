package io.butler.bet.cli;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ButlerAppShellBf670EmptySelectionTest {

    @Test
    void emptySelectionSetIsPreservedAsOnePowerShellObject() throws Exception {
        String shell = script("scripts/butler-app-shell.ps1");

        assertTrue(shell.contains("function Get-TradeSelectionSet"));
        assertTrue(shell.contains("Write-Output -NoEnumerate $set"));

        int tradeModuleLoad = shell.indexOf(". $tradeLab");
        int selectionOverride = shell.indexOf("function Get-TradeSelectionSet");
        assertTrue(tradeModuleLoad >= 0 && selectionOverride > tradeModuleLoad);
    }

    @Test
    void shellRemainsAsciiOnly() throws Exception {
        String shell = script("scripts/butler-app-shell.ps1");
        byte[] encoded = shell.getBytes(StandardCharsets.US_ASCII);
        assertEquals(shell, new String(encoded, StandardCharsets.US_ASCII));
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
        throw new IOException("BF-670 test could not locate " + relativePath);
    }
}
