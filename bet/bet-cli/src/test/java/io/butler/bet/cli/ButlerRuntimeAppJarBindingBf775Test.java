package io.butler.bet.cli;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ButlerRuntimeAppJarBindingBf775Test {

    @Test
    void runtimeReleaseBindsExactAppJarNameBeforeRequiredEntryVerification() throws Exception {
        String builder = source("scripts/butler-runtime-release-bundle.ps1");

        assertTrue(builder.contains("$appJar = $appJars[0]"));
        assertTrue(builder.contains("$appJarName = [string]$appJar.Name"));
        assertTrue(builder.contains("IsNullOrWhiteSpace($appJarName)"));
        assertTrue(builder.contains("prepared bet-cli application JAR resolved without a filename"));
        assertTrue(builder.contains("$requiredAppJarEntry = $packagedRuntimePrefix + $appJarName"));
        assertTrue(builder.contains("        $requiredAppJarEntry"));
        assertFalse(builder.contains("$packagedRuntimePrefix + $appJars[0].Name"));
    }

    @Test
    void bf775RepairRemainsAsciiOnly() throws Exception {
        String builder = source("scripts/butler-runtime-release-bundle.ps1");
        byte[] encoded = builder.getBytes(StandardCharsets.US_ASCII);
        assertEquals(builder, new String(encoded, StandardCharsets.US_ASCII));
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
        throw new IOException("BF-775 test could not locate " + relativePath);
    }
}
