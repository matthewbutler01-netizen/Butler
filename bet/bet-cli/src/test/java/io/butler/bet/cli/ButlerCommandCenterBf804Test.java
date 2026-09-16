package io.butler.bet.cli;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ButlerCommandCenterBf804Test {

    @Test
    void commandCenterIsDecisionFirstPresentationOnly() throws Exception {
        String transform = source("scripts/butler-dashboard-bf804-command-center-transform.ps1");

        assertTrue(transform.contains("Butler Command Center"));
        assertTrue(transform.contains("What matters now"));
        assertTrue(transform.contains("Butler's Priorities"));
        assertTrue(transform.contains("Start with the decision that matters"));
        assertTrue(transform.contains("Why Butler says this"));
        assertTrue(transform.contains("Can I trust this decision frame?"));
        assertTrue(transform.contains("Lineup Advisor"));
        assertTrue(transform.contains("Waiver Board"));
        assertTrue(transform.contains("Trade Lab"));
        assertTrue(transform.contains("Decision History"));
        assertTrue(transform.contains("Saved and traceable"));
        assertTrue(transform.contains("READ ONLY."));

        assertFalse(transform.contains("Method = \"POST\""));
        assertFalse(transform.contains("--team-bundle-autofill"));
        assertFalse(transform.contains("FantasyProsWeeklyProjectionProvider"));
        assertFalse(transform.contains("AutoFillLineupOptimizer"));
        assertFalse(transform.contains("submit transaction"));
    }

    @Test
    void commandCenterManagerMarkupOmitsOperatorAndIdentityNoise() throws Exception {
        String transform = source("scripts/butler-dashboard-bf804-command-center-transform.ps1");
        int markupStart = transform.indexOf("$newReturn = @'");
        int markupEnd = transform.indexOf("'@", markupStart + "$newReturn = @'".length());
        assertTrue(markupStart >= 0 && markupEnd > markupStart, "BF-804 manager return block must exist");

        String markup = transform.substring(markupStart, markupEnd);
        assertFalse(markup.contains("Sleeper ID"));
        assertFalse(markup.contains("Audit ID"));
        assertFalse(markup.contains("BF-629"));
        assertFalse(markup.contains("BF-631"));
        assertFalse(markup.contains("gradlew"));
        assertFalse(markup.contains("textarea"));
        assertFalse(markup.contains("operator"));
        assertFalse(markup.contains("UUID"));

        assertTrue(markup.contains("href=\"/team\""));
        assertTrue(markup.contains("href=\"/waivers\""));
        assertTrue(markup.contains("href=\"/trade\""));
        assertTrue(markup.contains("href=\"/league\""));
        assertTrue(markup.contains("href=\"/history\""));
    }

    @Test
    void commandCenterStagesAfterExistingManagerTransforms() throws Exception {
        String staging = source("scripts/butler-dashboard-bf715-transform.ps1");

        int bf742 = staging.indexOf("& $bf742Transform -CorePath $stagedCore -DashboardPath $DashboardPath");
        int bf800 = staging.indexOf("& $bf800Transform -CorePath $stagedCore");
        int bf803 = staging.indexOf("& $bf803Transform -CorePath $stagedCore");
        int bf804 = staging.indexOf("& $bf804Transform -DashboardPath $DashboardPath");

        assertTrue(bf742 >= 0, "BF-742 worker staging must remain present");
        assertTrue(bf800 > bf742, "BF-800 must remain after BF-742");
        assertTrue(bf803 > bf800, "BF-803 must remain after BF-800");
        assertTrue(bf804 > bf803, "BF-804 must run after the prior manager presentation chain");
        assertTrue(staging.contains("butler-dashboard-bf804-command-center-transform.ps1"));
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
        throw new IOException("BF-804 test could not locate " + relativePath);
    }
}
