package io.butler.bet.cli;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ButlerWeeklyMatchupWorkspaceBf840Test {

    @Test
    void matchupTransformRunsAfterAcceptedManagerVisualSystem() throws Exception {
        String staging = source("scripts/butler-dashboard-bf715-transform.ps1");

        int bf837 = staging.indexOf("& $bf837CoreTransform -CorePath $stagedCore");
        int bf840 = staging.indexOf("& $bf840Transform -CorePath $stagedCore");

        assertTrue(bf837 >= 0, "BF-837 final manager visual staging must remain present");
        assertTrue(bf840 > bf837, "BF-840 Matchup must stage after BF-837 visual alignment");
        assertTrue(staging.contains("butler-app-bf840-weekly-matchup-transform.ps1"));
    }

    @Test
    void managerWorkspaceUsesExactPairingAndExistingGovernedLineupContext() throws Exception {
        String transform = source("scripts/butler-app-bf840-weekly-matchup-transform.ps1");

        for (String marker : new String[]{
                "/matchup",
                "Weekly matchup",
                "PAIRING VERIFIED",
                "Opponent pairing unavailable",
                "Current Sleeper week is unavailable",
                ":bet:bet-cli:weeklyMatchupWorkspace",
                "--team-bundle-autofill",
                "ConvertTo-AutoFillHtml -AutoFill $AutoFill",
                "ConvertTo-RosterStrengthView",
                "ConvertTo-PositionalPressureView",
                "not to predict a winner",
                "will not guess the opponent"
        }) {
            assertTrue(transform.contains(marker), "BF-840 matchup transform missing " + marker);
        }

        assertFalse(transform.contains("win probability</"));
        assertFalse(transform.contains("predicted winner"));
        assertFalse(transform.contains("odds"));
        assertFalse(transform.contains("pick'em"));
    }

    @Test
    void directRuntimeAndOuterNavigationKnowAboutMatchup() throws Exception {
        String dispatch = source("scripts/butler-direct-java-dispatch.ps1");
        String build = source("bet/bet-cli/build.gradle.kts");
        String history = source("scripts/butler-decision-history.ps1");
        String tradeHost = source("scripts/butler-trade-lab-host.ps1");
        String refresh = source("scripts/sleeper-live-waiver-no-transaction-refresh.ps1");

        assertTrue(dispatch.contains(":bet:bet-cli:weeklyMatchupWorkspace"));
        assertTrue(dispatch.contains("ButlerWeeklyMatchupWorkspaceCli"));
        assertTrue(build.contains("val weeklyMatchupWorkspace by tasks.registering(JavaExec::class)"));
        assertTrue(build.contains("val sleeperCurrentWeekMatchupSync by tasks.registering(JavaExec::class)"));
        assertTrue(refresh.contains("BF-840 PRE-STAGE - exact weekly matchup pairing"));
        assertTrue(refresh.contains(":bet:bet-cli:sleeperCurrentWeekMatchupSync"));
        assertTrue(history.contains("href=\"/matchup\""));
        assertTrue(tradeHost.contains("href=\"/matchup\""));
    }

    @Test
    void matchupPresentationRemainsReadOnlyAndFailClosed() throws Exception {
        String transform = source("scripts/butler-app-bf840-weekly-matchup-transform.ps1");
        int safetyScan = transform.indexOf("$installedStart");
        assertTrue(safetyScan > 0, "BF-840 safety scan boundary must remain present");
        String operational = transform.substring(0, safetyScan);

        for (String forbidden : new String[]{
                "Invoke-RestMethod",
                "Invoke-WebRequest",
                "https://api.sleeper.app",
                "Method = \"POST\"",
                "submitTransaction",
                "setFaab"
        }) {
            assertFalse(operational.contains(forbidden),
                "BF-840 operational transform introduced provider/write behavior: " + forbidden);
        }

        assertTrue(transform.contains("generated staged core failed PowerShell parse"));
        assertTrue(transform.contains("System.Management.Automation.Language.Parser"));
        assertTrue(transform.contains("exact matchup frame does not match the bound roster frame"));
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
        throw new IOException("BF-840 test could not locate " + relativePath);
    }
}
