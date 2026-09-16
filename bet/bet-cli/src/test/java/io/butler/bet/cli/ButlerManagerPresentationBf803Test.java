package io.butler.bet.cli;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ButlerManagerPresentationBf803Test {

    @Test
    void managerUiIsPresentationOnlyAndStagesAfterAutoFill() throws Exception {
        String ui = source("scripts/butler-app-bf803-manager-ui-transform.ps1");
        String staging = source("scripts/butler-dashboard-bf715-transform.ps1");
        String autoFill = source("scripts/butler-app-bf800-autofill-transform.ps1");

        assertTrue(ui.contains("function Get-AppCss"));
        assertTrue(ui.contains("Butler's recommended lineup"));
        assertTrue(ui.contains("Players by lineup state"));
        assertTrue(ui.contains("Starting lineup"));
        assertTrue(ui.contains("Bench"));
        assertTrue(ui.contains("Reserve &amp; taxi"));
        assertTrue(ui.contains("decision-change"));
        assertTrue(ui.contains("decision-keep"));
        assertTrue(ui.contains("Projected change"));
        assertTrue(ui.contains("Projections:"));
        assertTrue(ui.contains("FantasyPros consensus projections"));
        assertTrue(ui.contains("Nothing is submitted to Sleeper"));
        assertTrue(ui.contains("Butler did not submit this lineup to Sleeper"));

        assertFalse(ui.contains("--team-bundle-autofill"),
            "BF-803 must not change the provider/AutoFill execution contract");
        assertFalse(ui.contains("Method = \"POST\""),
            "BF-803 must not introduce a write route");
        assertFalse(ui.contains("$(ConvertTo-HtmlText $player.SleeperId)"),
            "normal manager rows must not render Sleeper player ids");
        assertFalse(ui.contains("$(ConvertTo-HtmlText $player.ButlerPlayerId)"),
            "normal manager rows must not render Butler player ids");

        int bf800 = staging.indexOf("& $bf800Transform -CorePath $stagedCore");
        int bf803 = staging.indexOf("& $bf803Transform -CorePath $stagedCore");
        assertTrue(bf800 >= 0, "BF-800 staging invocation must remain present");
        assertTrue(bf803 > bf800, "BF-803 presentation must run after BF-800 installs AutoFill");

        assertTrue(autoFill.contains("if ($path -eq \"/team/autofill\")"));
        assertTrue(autoFill.contains("$LeagueId --team-bundle-autofill"));
        assertFalse(autoFill.contains("Method = \"POST\""));
    }

    @Test
    void managerUiKeepsButlerIdentityAndResponsiveDecisionHierarchy() throws Exception {
        String ui = source("scripts/butler-app-bf803-manager-ui-transform.ps1");

        assertTrue(ui.contains("We're here to serve you. Less Research. Better Decisions."));
        assertTrue(ui.contains("Lineup advisor"));
        assertTrue(ui.contains("Current"));
        assertTrue(ui.contains("Recommended"));
        assertTrue(ui.contains("Promote to lineup"));
        assertTrue(ui.contains("Move to bench"));
        assertTrue(ui.contains("Franchise rank"));
        assertTrue(ui.contains("Roster strength"));
        assertTrue(ui.contains("Team direction"));
        assertTrue(ui.contains("Draft capital"));
        assertTrue(ui.contains("@media(max-width:760px)"));

        assertFalse(ui.contains("FantasyPros logo"));
        assertFalse(ui.contains("sportsbook"));
        assertFalse(ui.contains("betting odds"));
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
        throw new IOException("BF-803 test could not locate " + relativePath);
    }
}
