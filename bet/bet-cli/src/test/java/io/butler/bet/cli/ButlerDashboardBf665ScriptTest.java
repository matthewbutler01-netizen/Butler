package io.butler.bet.cli;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ButlerDashboardBf665ScriptTest {

    @Test
    void noTransactionDecisionMakesBf635MoveRefreshWarningNotApplicable() throws Exception {
        String script = script();
        assertTrue(script.contains("$freshnessLimit = if ($state -ceq \"NO_TRANSACTION_TO_ACT_ON\") { \"BF-635 move-refresh warning: not applicable to no-transaction decisions\" } else { \"Warning boundary: $thresholdHours hours\" }"));
        assertEquals(2, count(script, "<div class=\"limit\">$(ConvertTo-HtmlText $freshnessLimit)</div>"));
        assertFalse(script.contains("<div class=\"limit\">Warning boundary: $thresholdHours hours</div>"));
    }

    @Test
    void transactionBearingPathRetainsExistingWarningBoundaryCopy() throws Exception {
        String script = script();
        assertTrue(script.contains("else { \"Warning boundary: $thresholdHours hours\" }"));
        assertTrue(script.contains("$market = ConvertTo-AgeView $marketRaw"));
        assertTrue(script.contains("$waiver = ConvertTo-AgeView $waiverRaw"));
    }

    private static int count(String text, String needle) {
        int count = 0;
        int index = 0;
        while ((index = text.indexOf(needle, index)) >= 0) {
            count++;
            index += needle.length();
        }
        return count;
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
        throw new IOException("BF-665 test could not locate scripts/butler-dashboard.ps1");
    }
}
