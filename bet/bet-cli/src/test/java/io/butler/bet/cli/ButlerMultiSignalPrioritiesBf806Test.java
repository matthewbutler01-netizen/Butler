package io.butler.bet.cli;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ButlerMultiSignalPrioritiesBf806Test {

    @Test
    void commandCenterShowsWaiverLineupAndTradeSignalsWithoutNewDecisionSemantics() throws Exception {
        String transform = source("scripts/butler-dashboard-bf806-multi-signal-priorities-transform.ps1");

        assertTrue(transform.contains("<div class=\"priority-type\">Waiver</div>"));
        assertTrue(transform.contains("<div class=\"priority-type\">Lineup</div>"));
        assertTrue(transform.contains("<div class=\"priority-type\">Trade</div>"));
        assertTrue(transform.contains("Your decision queue"));
        assertTrue(transform.contains("href=\"/waivers\">Open Waiver Board</a>"));
        assertTrue(transform.contains("href=\"/team\">Open Lineup Advisor</a>"));
        assertTrue(transform.contains("href=\"/trade\">Open Trade Lab</a>"));
        assertTrue(transform.contains("Current evidence does not support one clear add/drop move"));
        assertTrue(transform.contains("Butler's roster check is verified"));
        assertTrue(transform.contains("No trade priority is proven here"));
        assertTrue(transform.contains("DECISION QUEUE"));

        assertFalse(transform.contains("Method = \"POST\""));
        assertFalse(transform.contains("--team-bundle-autofill"));
        assertFalse(transform.contains("FantasyProsWeeklyProjectionProvider"));
        assertFalse(transform.contains("AutoFillLineupOptimizer"));
        assertFalse(transform.contains("Invoke-ButlerReadOnlyTask"));
    }

    @Test
    void normalPriorityMarkupDoesNotExposeImplementationCopy() throws Exception {
        String transform = source("scripts/butler-dashboard-bf806-multi-signal-priorities-transform.ps1");
        int start = transform.indexOf("$priorityReplacement = @'");
        int end = transform.indexOf("'@", start + "$priorityReplacement = @'".length());
        assertTrue(start >= 0 && end > start, "BF-806 priority markup block must exist");

        String markup = transform.substring(start, end);
        assertFalse(markup.contains("recorded no-transaction"));
        assertFalse(markup.contains("from this audit"));
        assertFalse(markup.contains("final method"));
        assertFalse(markup.contains("cross-position ties"));
        assertFalse(markup.contains("UUID"));
        assertFalse(markup.contains("BF-"));
    }

    @Test
    void multiSignalPassStagesAfterCommandCenterPolish() throws Exception {
        String staging = source("scripts/butler-dashboard-bf715-transform.ps1");

        int bf804 = staging.indexOf("& $bf804Transform -DashboardPath $DashboardPath");
        int bf805 = staging.indexOf("& $bf805Transform -DashboardPath $DashboardPath");
        int bf806 = staging.indexOf("& $bf806Transform -DashboardPath $DashboardPath");
        assertTrue(bf804 >= 0, "BF-804 must remain staged");
        assertTrue(bf805 > bf804, "BF-805 must remain after BF-804");
        assertTrue(bf806 > bf805, "BF-806 must run after BF-805 polish");
        assertTrue(staging.contains("butler-dashboard-bf806-multi-signal-priorities-transform.ps1"));
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
        throw new IOException("BF-806 test could not locate " + relativePath);
    }
}
