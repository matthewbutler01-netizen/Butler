package io.butler.bet.cli;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertTrue;

class ButlerProviderFrameLanguageBf792Test {

    @Test
    void providerLifecycleValuesAreHumanizedOnlyAtPresentationBoundary() throws Exception {
        String cache = source("scripts/butler-app-request-worker-cache.ps1");

        assertTrue(cache.contains("in_season\\s*/\\s*(\\d+)"));
        assertTrue(cache.contains("'$1 / Week $2$3'"));
        assertTrue(cache.contains("pre_draft\\s*/\\s*\\d+"));
        assertTrue(cache.contains("'$1 / Pre-draft$2'"));
        assertTrue(cache.contains("drafting\\s*/\\s*\\d+"));
        assertTrue(cache.contains("'$1 / Drafting$2'"));
        assertTrue(cache.contains("complete\\s*/\\s*\\d+"));
        assertTrue(cache.contains("'$1 / Complete$2'"));
        assertTrue(cache.contains("post_season\\s*/\\s*\\d+"));
        assertTrue(cache.contains("'$1 / Post-season$2'"));
        assertTrue(cache.contains("raw technical <pre> diagnostics remain exact"));
    }

    private static String source(String relativePath) throws IOException {
        Path current = Path.of(System.getProperty("user.dir")).toAbsolutePath().normalize();
        for (int depth = 0; depth < 7 && current != null; depth++) {
            Path candidate = current.resolve(relativePath);
            if (Files.isRegularFile(candidate)) {
                return Files.readString(candidate, StandardCharsets.UTF_8);
            }
            current = current.getParent();
        }
        throw new IOException("BF-792 test could not locate " + relativePath);
    }
}
