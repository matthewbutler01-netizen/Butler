package io.butler.bet.cli;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ButlerMyTeamRosterHubBf908Test {

    @Test
    void rosterHubPresentsCurrentAssignmentBeforeSupportingTeamContext() throws Exception {
        String transform = source("scripts/butler-app-bf908-my-team-roster-hub-transform.ps1");

        assertTrue(transform.contains("Roster hub"));
        assertTrue(transform.contains("Lineup and depth at a glance"));
        assertTrue(transform.contains("$bf908Starters"));
        assertTrue(transform.contains("$bf908Bench"));
        assertTrue(transform.contains("$bf908Reserve"));
        assertTrue(transform.contains("<h3>Starters</h3>"));
        assertTrue(transform.contains("<h3>Bench</h3>"));
        assertTrue(transform.contains("<h3>Reserve</h3>"));
        assertTrue(transform.contains("$teamBlock.Insert($positionIndex, '$bf908RosterHubHtml'"));
        assertTrue(transform.contains("Lineup-aware pressure"));
        assertTrue(transform.contains("Future flexibility"));
    }

    @Test
    void mappedPlayersReuseExistingDetailAndCompareContracts() throws Exception {
        String transform = source("scripts/butler-app-bf908-my-team-roster-hub-transform.ps1");

        assertTrue(transform.contains("ConvertTo-MyTeamPlayerNameHtml -Player $player"));
        assertTrue(transform.contains("[string]$player.Mapping -ceq \"EXACT_CANONICAL\""));
        assertTrue(transform.contains("href=\"/player?id="));
        assertTrue(transform.contains("href=\"/compare?left="));
        assertTrue(transform.contains("Player tools unavailable"));
        assertFalse(transform.contains("better player"));
        assertFalse(transform.contains("player grade"));
    }

    @Test
    void teamHubKeepsExistingManagerActions() throws Exception {
        String transform = source("scripts/butler-app-bf908-my-team-roster-hub-transform.ps1");

        assertTrue(transform.contains("href=\"/matchup\">Review Matchup</a>"));
        assertTrue(transform.contains("href=\"/matchup/autofill\">Review Lineup</a>"));
        assertTrue(transform.contains("href=\"/players\">Player Search</a>"));
    }

    @Test
    void rosterHubStagesAfterPlayerDetailAndCompareBeforeRecoveryPolish() throws Exception {
        String staging = source("scripts/butler-dashboard-bf715-transform.ps1");

        int bf879 = staging.indexOf("& $bf879CoreTransform -CorePath $stagedCore");
        int bf906 = staging.indexOf("& $bf906CoreTransform -CorePath $stagedCore");
        int bf908 = staging.indexOf("& $bf908CoreTransform -CorePath $stagedCore");
        int bf884 = staging.indexOf("& $bf884CoreTransform -CorePath $stagedCore");

        assertTrue(bf879 >= 0, "BF-879 staging marker missing");
        assertTrue(bf906 > bf879, "BF-906 must remain after Player Detail");
        assertTrue(bf908 > bf906, "BF-908 must run after Player Compare so roster actions can reuse it");
        assertTrue(bf884 > bf908, "BF-884 recovery polish must remain after BF-908");
    }

    @Test
    void rosterHubRemainsPresentationOnly() throws Exception {
        String transform = source("scripts/butler-app-bf908-my-team-roster-hub-transform.ps1");

        int safetyScan = transform.indexOf("$installedTeam -match");
        assertTrue(safetyScan > 0);
        String operational = transform.substring(0, safetyScan);

        for (String forbidden : new String[] {
            "Invoke-RestMethod",
            "Invoke-WebRequest",
            "Invoke-ButlerReadOnly",
            "Invoke-Bf742DashboardWorkerRead",
            "https://api.sleeper.app",
            "Method = \"POST\"",
            "submitTransaction",
            "setFaab",
            "AutoFillLineupOptimizer"
        }) {
            assertFalse(operational.contains(forbidden),
                "BF-908 operational transform introduced provider/read/write marker " + forbidden);
        }

        assertTrue(transform.contains("generated staged core failed PowerShell parse"));
        assertTrue(transform.contains("Roster Hub introduced provider, optimizer, backend-read, or write behavior"));
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
        throw new IOException("BF-908 test could not locate " + relativePath);
    }
}
