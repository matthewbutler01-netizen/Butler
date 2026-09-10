package io.butler.bet.cli;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ButlerDashboardBf663ScriptTest {

    @Test
    void noTransactionUsesDecisionAwareExplanationHeading() throws Exception {
        String script = script();
        assertTrue(script.contains("$explanationHeading = if ($state -ceq \"NO_TRANSACTION_TO_ACT_ON\") { \"Why no transaction?\" } else { \"Why this move?\" }"));
        assertTrue(script.contains("<h2>$(ConvertTo-HtmlText $explanationHeading)</h2>"));
        assertFalse(script.contains("<h2>Why this move?</h2>"));
    }

    @Test
    void noTransactionUsesDecisionAwareRosterSafetyDetail() throws Exception {
        String script = script();
        assertTrue(script.contains("$rosterSafetyDetail = if ($bf629 -ceq \"NO_TRANSACTION_TO_REVALIDATE\") { \"BF-629 confirms this audited decision has no transaction requiring live roster revalidation.\" } else { \"BF-629 checks whether the audited move is still valid against Sleeper.\" }"));
        assertTrue(script.contains("<small>$(ConvertTo-HtmlText $rosterSafetyDetail)</small>"));
    }

    @Test
    void persistedExplanationLookupContractRemainsUnchanged() throws Exception {
        String script = script();
        assertTrue(script.contains("$explanationText = Get-LineValue -Text $lookup -Label \"Why this move:\""));
    }

    private static String script() throws IOException {
        Path current = Path.of(System.getProperty("user.dir")).toAbsolutePath().normalize();
        for (int depth = 0; depth < 7 && current != null; depth++) {
            Path candidate = current.resolve("scripts/butler-dashboard.ps1");
            if (Files.isRegularFile(candidate)) {
                return Files.readString(candidate, StandardCharsets.US_ASCII);
            }
            current = current.getParent();
        }
        throw new IOException("BF-663 test could not locate scripts/butler-dashboard.ps1");
    }
}
