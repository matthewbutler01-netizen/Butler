package io.butler.bet.cli;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ButlerRuntimeZipEntryNormalizationBf774Test {

    @Test
    void runtimeReleaseCanonicalizesEachWindowsZipSeparatorBeforeVerification() throws Exception {
        String builder = source("scripts/butler-runtime-release-bundle.ps1");

        assertTrue(builder.contains("ZipFile]::CreateFromDirectory"));
        assertTrue(builder.contains("Replace('\\', '/')"));
        assertTrue(builder.contains("'scripts/butler-app.cmd'"));
        assertTrue(builder.indexOf("Replace('\\', '/')") < builder.indexOf("'scripts/butler-app.cmd'"),
            "Windows ZIP entry separators must be canonicalized before required-entry verification");
    }

    @Test
    void bf774RepairRemainsAsciiOnly() throws Exception {
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
        throw new IOException("BF-774 test could not locate " + relativePath);
    }
}
