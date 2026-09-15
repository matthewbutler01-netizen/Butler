package io.butler.bet.cli;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ButlerReleaseVerificationDigitKeysBf779Test {

    @Test
    void verifierAllowsCanonicalDigitsInKeySyntax() throws Exception {
        String verifier = source("scripts/butler-release-verification-check.ps1");

        assertTrue(verifier.contains("^([a-z0-9_]+)=(.+)$"),
            "BF-779 must allow digits in canonical lowercase record keys such as runtime_sha256");
        assertFalse(verifier.contains("^([a-z_]+)=(.+)$"),
            "BF-779 must not retain the parser that rejected runtime_sha256");
        assertTrue(verifier.contains("'runtime_sha256'"),
            "BF-777 canonical runtime_sha256 key must remain in the required schema");
    }

    @Test
    void digitBearingSyntaxDoesNotBroadenTheSchemaWhitelist() throws Exception {
        String verifier = source("scripts/butler-release-verification-check.ps1");

        int parse = verifier.indexOf("^([a-z0-9_]+)=(.+)$");
        int whitelist = verifier.indexOf("if ($requiredKeys -cnotcontains $key)");
        int unexpected = verifier.indexOf("unexpected verification record key");

        assertTrue(parse >= 0);
        assertTrue(whitelist > parse,
            "syntactically valid digit-bearing keys must still pass through the exact required-key whitelist");
        assertTrue(unexpected > whitelist,
            "unexpected digit-bearing keys must remain fail-closed");
    }

    @Test
    void bf779WindowsSourceRemainsAsciiOnly() throws Exception {
        String verifier = source("scripts/butler-release-verification-check.ps1");
        byte[] encoded = verifier.getBytes(StandardCharsets.US_ASCII);
        assertEquals(verifier, new String(encoded, StandardCharsets.US_ASCII));
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
        throw new IOException("BF-779 test could not locate " + relativePath);
    }
}
