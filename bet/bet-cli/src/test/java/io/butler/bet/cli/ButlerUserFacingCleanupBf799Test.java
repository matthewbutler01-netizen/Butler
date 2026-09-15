package io.butler.bet.cli;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ButlerUserFacingCleanupBf799Test {

    @Test
    void publicUiRemovesRemainingOperatorAndIdentifierClutter() throws Exception {
        String cache = source("scripts/butler-app-request-worker-cache.ps1");

        assertTrue(cache.contains(
                "BF-799 keeps maintenance commands out of the normal fantasy-manager UI."));
        assertFalse(cache.contains(
                "<summary>Advanced manual command</summary>"));

        assertTrue(cache.contains(
                "<title>Butler - Decision Detail</title>"));
        assertTrue(cache.contains(
                "Explanation id</strong><span>.*?</span></div>"));

        assertTrue(cache.contains(
                "'Immutable Butler audit captured' = 'Saved decision'"));
        assertTrue(cache.contains(
                "'Every governed recommendation remains traceable even after your roster changes.' = 'This recommendation stays saved even if your roster changes.'"));
    }

    @Test
    void backendIdentifiersRemainAvailableForAuditReconciliation() throws Exception {
        String detail = source("scripts/butler-decision-detail.ps1");

        assertTrue(detail.contains("$Explanation.ExplanationId"));
        assertTrue(detail.contains("$History.LeagueId"));
        assertTrue(detail.contains("$Entry.AuditId"));
    }

    private static String source(String relativePath) throws IOException {
        Path current = Path.of(System.getProperty("user.dir"))
                .toAbsolutePath()
                .normalize();

        for (int depth = 0; depth < 7 && current != null; depth++) {
            Path candidate = current.resolve(relativePath);

            if (Files.isRegularFile(candidate)) {
                return Files.readString(candidate, StandardCharsets.UTF_8);
            }

            current = current.getParent();
        }

        throw new IOException("BF-799 test could not locate " + relativePath);
    }
}