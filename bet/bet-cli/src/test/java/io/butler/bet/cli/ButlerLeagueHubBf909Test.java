package io.butler.bet.cli;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ButlerLeagueHubBf909Test {

    @Test
    void leagueHubMakesFranchiseBoardThePrimaryWorkspace() throws Exception {
        String transform = source("scripts/butler-app-bf909-league-hub-transform.ps1");

        assertTrue(transform.contains("League hub"));
        assertTrue(transform.contains("Franchise board"));
        assertTrue(transform.contains("League landscape"));
        assertTrue(transform.contains("Total value"));
        assertTrue(transform.contains("Player value"));
        assertTrue(transform.contains("Pick value"));
        assertTrue(transform.contains("League Hub section order must remain franchise board -> movement -> governed actions"));
    }

    @Test
    void franchiseBoardPreservesExactFranchiseDetailLinks() throws Exception {
        String transform = source("scripts/butler-app-bf909-league-hub-transform.ps1");

        assertTrue(transform.contains("ConvertTo-FranchiseLeaderNameHtml -Leader $leader"));
        assertTrue(transform.contains("Open the franchise name for neutral team detail."));
        assertTrue(transform.contains("descriptive context, not a manager grade or trade-target list"));
        assertTrue(transform.contains("does not rerank franchises"));
        assertTrue(transform.contains("create grades or contender/rebuilder labels"));
    }

    @Test
    void leagueHubKeepsSecondaryToolsOutsidePrimaryNavigation() throws Exception {
        String transform = source("scripts/butler-app-bf909-league-hub-transform.ps1");

        assertTrue(transform.contains("href=\"/team\">My Team</a>"));
        assertTrue(transform.contains("href=\"/players\">Player Search</a>"));
        assertTrue(transform.contains("href=\"/compare\">Player Compare</a>"));
        assertTrue(transform.contains("href=\"/trade\">Trade Analyzer</a>"));
        assertTrue(transform.contains("$nav = Get-AppNav -Active \"league\""));
        assertFalse(transform.contains("Player Compare</a></nav>"));
        assertFalse(transform.contains("Trade Analyzer</a></nav>"));
    }

    @Test
    void comparableMovementAndGovernanceStayExplicitlyBounded() throws Exception {
        String transform = source("scripts/butler-app-bf909-league-hub-transform.ps1");

        assertTrue(transform.contains("Comparable movement"));
        assertTrue(transform.contains("Movement boundary"));
        assertTrue(transform.contains("Butler does not manufacture a trend."));
        assertTrue(transform.contains("View governed league actions"));
        assertTrue(transform.contains("manual and read only"));
        assertTrue(transform.contains("never executes the displayed commands"));
    }

    @Test
    void leagueHubStagesAfterRosterHubAndBeforeRecoveryPolish() throws Exception {
        String staging = source("scripts/butler-dashboard-bf715-transform.ps1");

        int bf908 = staging.indexOf("& $bf908CoreTransform -CorePath $stagedCore");
        int bf909 = staging.indexOf("& $bf909CoreTransform -CorePath $stagedCore");
        int bf884 = staging.indexOf("& $bf884CoreTransform -CorePath $stagedCore");

        assertTrue(bf908 >= 0, "BF-908 staging marker missing");
        assertTrue(bf909 > bf908, "BF-909 must run after BF-908 final My Team composition");
        assertTrue(bf884 > bf909, "BF-884 recovery polish must remain after BF-909");
    }

    @Test
    void leagueHubRemainsPresentationOnly() throws Exception {
        String transform = source("scripts/butler-app-bf909-league-hub-transform.ps1");

        int safetyScan = transform.indexOf("$installedLeague -match");
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
                "BF-909 operational transform introduced provider/read/write marker " + forbidden);
        }

        assertTrue(transform.contains("generated staged core failed PowerShell parse"));
        assertTrue(transform.contains("League Hub introduced provider, backend-read, optimizer, or write behavior"));
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
        throw new IOException("BF-909 test could not locate " + relativePath);
    }
}
